package org.readera.openreadera.data.db

import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Explicitly opt in on the selected device; never runs in the normal test suite. */
@RunWith(AndroidJUnit4::class)
class LibraryUpgradeBackupTest {
    @Test fun installedLibraryPreservesExistingDataAfterMigration() {
        val filename = InstrumentationRegistry.getArguments().getString("baselineBackupFilename")
        assumeTrue(filename != null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = context.filesDir.canonicalFile
        val baseline = File(directory, filename!!).canonicalFile
        require(baseline.parentFile == directory && baseline.isFile)
        val current = AppDatabase.getInstance(context).openHelper.writableDatabase
        current.query("PRAGMA user_version").use { assertTrue(it.moveToFirst()); assertEquals(6, it.getInt(0)) }
        SQLiteDatabase.openDatabase(baseline.path, null, SQLiteDatabase.OPEN_READONLY).use { previous ->
            for (table in listOf("books", "quotes", "bookmarks", "drawing_strokes")) {
                val columns = previous.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
                    buildList { while (cursor.moveToNext()) add(cursor.getString(1)) }
                }.filterNot { table == "books" && it in setOf("status", "isToRead", "isHaveRead") }
                val query = "SELECT ${columns.joinToString()} FROM $table ORDER BY id"
                fun rows(cursor: android.database.Cursor): List<List<String?>> = cursor.use {
                    buildList { while (cursor.moveToNext()) add((0 until cursor.columnCount).map { column -> cursor.getString(column) }) }
                }
                val oldRows = rows(previous.rawQuery(query, null))
                val newRows = rows(current.query(query))
                assertTrue("Existing $table contents must survive migration", oldRows == newRows)
            }
        }
    }

    @Test fun recoverExistingSnapshotToPrivateFiles() {
        val filename = InstrumentationRegistry.getArguments().getString("recoverBackupFilename")
        assumeTrue(filename != null)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = context.getExternalFilesDir(null)!!.canonicalFile
        val source = File(directory, filename!!).canonicalFile
        require(source.parentFile == directory && source.isFile)
        val target = File.createTempFile("library-before-upgrade-", ".db", context.filesDir)
        source.copyTo(target, overwrite = true)
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "Recovered backup: ${target.path}\n") })
    }

    @Test fun exportConsistentDatabaseBeforeUpgrade() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("backupBeforeUpgrade") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = context.getDatabasePath("openreadera.db")
        assertTrue(source.isFile)
        val output = File(context.filesDir, "library-before-upgrade-${System.currentTimeMillis()}.db")
        SQLiteDatabase.openDatabase(source.path, null, SQLiteDatabase.OPEN_READWRITE).use { database ->
            database.execSQL("VACUUM INTO ?", arrayOf(output.path))
        }
        SQLiteDatabase.openDatabase(output.path, null, SQLiteDatabase.OPEN_READONLY).use { backup ->
            backup.rawQuery("PRAGMA quick_check", null).use { assertTrue(it.moveToFirst()); assertEquals("ok", it.getString(0)) }
            val counts = listOf("books", "quotes", "bookmarks", "drawing_strokes").mapNotNull { table ->
                val exists = backup.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name=?", arrayOf(table))
                    .use { it.moveToFirst() }
                if (!exists) null else backup.rawQuery("SELECT COUNT(*) FROM $table", null)
                    .use { it.moveToFirst(); "$table=${it.getLong(0)}" }
            }
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "Library backup: ${output.path}; ${counts.joinToString()}\n") })
        }
    }
}
