package org.readera.openreadera.data.db

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import org.robolectric.RuntimeEnvironment
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class LibraryMigrationTest {
    @Test fun versionFiveUpgradeKeepsBooksNotesAndExistingIdentities() = runBlocking {
        val context: Context = RuntimeEnvironment.getApplication()
        val name = "migration-${java.util.UUID.randomUUID()}.db"
        val schemaFile = listOf(File("schemas/org.readera.openreadera.data.db.AppDatabase/5.json"),
            File("app/schemas/org.readera.openreadera.data.db.AppDatabase/5.json")).first(File::isFile)
        val schema = JSONObject(schemaFile.readText()).getJSONObject("database")
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name).callback(object : SupportSQLiteOpenHelper.Callback(5) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    val entities = schema.getJSONArray("entities")
                    for (i in 0 until entities.length()) {
                        val entity = entities.getJSONObject(i)
                        db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
                        val indices = entity.getJSONArray("indices")
                        for (j in 0 until indices.length()) db.execSQL(indices.getJSONObject(j).getString("createSql")
                            .replace("\${TABLE_NAME}", entity.getString("tableName")))
                    }
                    val values = linkedMapOf<String, Any?>()
                    val book = org.readera.openreadera.data.model.Book(id = 1, syncId = "existing-id",
                        title = "Existing book", filePath = "/existing.pdf", format = "PDF", status = org.readera.openreadera.data.model.BookStatus.TO_READ)
                    // Populate from a current book through field values, excluding the newly added column.
                    for (field in book.javaClass.declaredFields) {
                        if (java.lang.reflect.Modifier.isStatic(field.modifiers) || field.name == "metadataUpdatedAt") continue
                        field.isAccessible = true
                        values[field.name] = field.get(book).let { if (it is Enum<*>) it.name else if (it is Boolean) if (it) 1 else 0 else it }
                    }
                    db.execSQL("INSERT INTO books (${values.keys.joinToString()}) VALUES (${values.keys.joinToString { "?" }})", values.values.toTypedArray())
                    db.execSQL("INSERT INTO quotes (id,bookId,page,text,note,colorHex,createdAt) VALUES (1,1,2,'Quote','Note','#ffffff',10)")
                    db.execSQL("INSERT INTO bookmarks (id,bookId,page,title,snippet,createdAt) VALUES (1,1,2,'Bookmark','Snippet',10)")
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }).build())
        helper.writableDatabase
        helper.close()
        val db = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(AppDatabase.MIGRATION_5_6).build()
        try {
            assertEquals("existing-id", db.bookDao().getBookByIdSync(1)!!.syncId)
            assertTrue(db.bookDao().getBookByIdSync(1)!!.isToRead)
            assertEquals("Note", db.quoteDao().getAllQuotesList().single().note)
            val migrated = db.quoteDao().getAllQuotesList().single()
            assertEquals("", migrated.syncId)
            db.quoteDao().update(migrated.copy(note = "Edited"))
            val id = db.quoteDao().getAllQuotesList().single().syncId
            assertTrue(id.isNotBlank())
            db.quoteDao().delete(migrated)
            assertEquals(id, db.quoteDao().getAllQuotesList().single().syncId)
            assertTrue(db.quoteDao().getAllQuotesList().single().deletedAt > 0)
            assertEquals(1, db.bookmarkDao().getAllBookmarksList().size)
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
