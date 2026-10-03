package org.readera.openreadera.zlib

import org.readera.openreadera.catalog.CatalogSource

data class ZLibBook(
    val id: String,
    val hash: String = "",
    val title: String,
    val author: String = "Autor desconocido",
    val year: String = "",
    val language: String = "",
    val extension: String = "EPUB",
    val filesize: String = "",
    val rating: String = "",
    val coverUrl: String? = null,
    val downloadUrl: String? = null,
    val description: String? = null,
    val publisher: String? = null,
    val source: CatalogSource = CatalogSource.Z_LIBRARY,
    val isDownloaded: Boolean = false,
    val localFilePath: String? = null
)

data class ZLibProfile(
    val id: String,
    val email: String,
    val name: String,
    val remixUserkey: String,
    val downloadsToday: Int = 0,
    val downloadsLimit: Int = 10
)

data class ZLibDownloadState(
    val bookId: String,
    val progress: Float = 0f,
    val isDownloading: Boolean = false,
    val isCompleted: Boolean = false,
    val error: String? = null,
    val savedFilePath: String? = null
)
