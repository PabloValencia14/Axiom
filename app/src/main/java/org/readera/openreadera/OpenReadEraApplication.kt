package org.readera.openreadera

import android.app.Application
import org.readera.openreadera.data.db.AppDatabase
import org.readera.openreadera.data.preferences.ReaderPreferences
import org.readera.openreadera.data.repository.BookRepository
import org.readera.openreadera.data.scanner.StorageScanner
import org.readera.openreadera.engine.EngineManager

class OpenReadEraApplication : Application() {

    lateinit var database: AppDatabase
        private set

    lateinit var preferences: ReaderPreferences
        private set

    lateinit var repository: BookRepository
        private set

    lateinit var engineManager: EngineManager
        private set

    lateinit var storageScanner: StorageScanner
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        database = AppDatabase.getInstance(this)
        preferences = ReaderPreferences(this)
        repository = BookRepository(
            database.bookDao(),
            database.bookmarkDao(),
            database.quoteDao(),
            database.collectionDao(),
            database.searchHistoryDao(),
            database.drawingStrokeDao()
        )
        engineManager = EngineManager(this)
        storageScanner = StorageScanner(this, repository)

        // Schedule periodic Google Drive sync (runs every 15 minutes in background)
        org.readera.openreadera.sync.DriveSyncWorker.schedulePeriodicSync(this)
    }

    companion object {
        lateinit var instance: OpenReadEraApplication
            private set
    }
}
