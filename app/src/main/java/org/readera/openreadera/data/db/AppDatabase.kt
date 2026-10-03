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
    version = 5,
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
                    .addMigrations(MIGRATION_4_5)
                    .build()
                    .also { instance = it }
            }
        }
    }
}
