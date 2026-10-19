package org.readera.openreadera.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.readera.openreadera.data.model.Book
import org.readera.openreadera.data.model.BookCollectionCrossRef
import org.readera.openreadera.data.model.Bookmark
import org.readera.openreadera.data.model.Collection
import org.readera.openreadera.data.model.DrawingStroke
import org.readera.openreadera.data.model.Quote
import org.readera.openreadera.data.model.SearchQuery

@Database(
    entities = [
        Book::class,
        Bookmark::class,
        Quote::class,
        DrawingStroke::class,
        Collection::class,
        BookCollectionCrossRef::class,
        SearchQuery::class
    ],
    version = 6,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun quoteDao(): QuoteDao
    abstract fun drawingStrokeDao(): DrawingStrokeDao
    abstract fun collectionDao(): CollectionDao
    abstract fun searchHistoryDao(): SearchHistoryDao

    companion object {
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN syncId TEXT NOT NULL DEFAULT ''")
                db.execSQL("UPDATE books SET syncId = lower(hex(randomblob(16))) WHERE syncId = ''")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_books_syncId ON books(syncId)")
            }
        }
        internal val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for (table in listOf("quotes", "bookmarks")) {
                    db.execSQL("ALTER TABLE $table ADD COLUMN syncId TEXT NOT NULL DEFAULT ''")
                    db.execSQL("ALTER TABLE $table ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("ALTER TABLE $table ADD COLUMN deletedAt INTEGER NOT NULL DEFAULT 0")
                }
                db.execSQL("ALTER TABLE books ADD COLUMN metadataUpdatedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE books SET isToRead = CASE WHEN status = 'TO_READ' THEN 1 WHEN isHaveRead = 1 OR status = 'HAVE_READ' THEN 0 ELSE isToRead END, isHaveRead = CASE WHEN status = 'TO_READ' THEN 0 WHEN status = 'HAVE_READ' THEN 1 ELSE isHaveRead END")
                db.execSQL("UPDATE books SET status = CASE WHEN isHaveRead = 1 THEN 'HAVE_READ' WHEN isToRead = 1 THEN 'TO_READ' ELSE status END")
            }
        }
        @Volatile
        private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "openreadera.db"
                )
                    .addCallback(object : Callback() {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            super.onCreate(db)
                            CoroutineScope(Dispatchers.IO).launch {
                                val collectionDao = getInstance(context).collectionDao()
                                val defaultCollections = listOf(
                                    Collection(name = "Ciencia-ficcion"),
                                    Collection(name = "Cómics"),
                                    Collection(name = "Libros"),
                                    Collection(name = "Literatura"),
                                    Collection(name = "Novela-grafica")
                                )
                                collectionDao.insertAll(defaultCollections)
                            }
                        }
                    })
                    .addMigrations(MIGRATION_4_5, MIGRATION_5_6)
                    .build()
                    .also { instance = it }
            }
        }
    }
}
