package org.readera.openreadera.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "quotes",
    foreignKeys = [
        ForeignKey(
            entity = Book::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("bookId")]
)
data class Quote(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val bookId: Long,
    val page: Int,
    val text: String,
    val note: String = "",
    val colorHex: String = "#FFE082", // Amber default
    val createdAt: Long = System.currentTimeMillis()
)
