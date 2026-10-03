package org.readera.openreadera.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

enum class BookStatus {
    READING,
    TO_READ,
    HAVE_READ,
    UNREAD
}

@Entity(tableName = "books", indices = [Index(value = ["syncId"], unique = true)])
data class Book(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    @ColumnInfo(defaultValue = "''")
    val syncId: String = UUID.randomUUID().toString(),
    val title: String,
    val author: String = "Autor desconocido",
    val filePath: String,
    val format: String,
    val fileSize: Long = 0L,
    val coverPath: String? = null,
    val currentPage: Int = 0,
    val totalPages: Int = 1,
    val progressPercent: Float = 0f,
    val isFavorite: Boolean = false,
    val isToRead: Boolean = false,
    val isHaveRead: Boolean = false,
    val isTrash: Boolean = false,
    val status: BookStatus = BookStatus.UNREAD,
    val dateAdded: Long = System.currentTimeMillis(),
    val lastOpened: Long = 0L,
    val series: String? = null,
    val seriesIndex: Int? = null,
    val collectionName: String? = null,
    val sha1: String? = null,
    val language: String? = null,
    val genre: String? = null,
    val description: String? = null,
    val annotation: String? = null,
    val review: String? = null,
    val rating: Float? = null,
    val reviewDate: Long? = null,
    val reviewTags: String? = null,
    val fileCount: Int = 1
)
