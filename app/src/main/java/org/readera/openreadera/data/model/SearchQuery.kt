package org.readera.openreadera.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "search_history")
data class SearchQuery(
    @PrimaryKey
    val query: String,
    val timestamp: Long = System.currentTimeMillis()
)
