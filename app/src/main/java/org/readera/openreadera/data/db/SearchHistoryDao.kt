package org.readera.openreadera.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import org.readera.openreadera.data.model.SearchQuery

@Dao
interface SearchHistoryDao {
    @Query("SELECT * FROM search_history ORDER BY timestamp DESC LIMIT 20")
    fun getRecentQueries(): Flow<List<SearchQuery>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertQuery(query: SearchQuery)
    @Query("SELECT * FROM search_history ORDER BY timestamp ASC")
    suspend fun getAllQueriesList(): List<SearchQuery>

    @Query("DELETE FROM search_history")
    suspend fun clearHistory()
}
