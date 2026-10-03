package org.readera.openreadera.catalog

enum class CatalogSource(val displayName: String, val badgeColor: Long) {
    ALL("Todos los motores", 0xFF2196F3),
    Z_LIBRARY("Z-Library", 0xFF3F51B5),
    LIBGEN("Library Genesis", 0xFF009688),
    ANNAS_ARCHIVE("Anna's Archive", 0xFFFF9800),
    EBOOKELO("Ebookelo (Español)", 0xFF4CAF50),
    STANDARD_EBOOKS("Standard Ebooks", 0xFF795548),
    GUTENBERG("Project Gutenberg", 0xFF9C27B0),
    OPEN_LIBRARY("Open Library", 0xFF00BCD4),
    GOOGLE_BOOKS("Google Books", 0xFFE91E63)
}

typealias CatalogBook = org.readera.openreadera.zlib.ZLibBook
typealias CatalogDownloadState = org.readera.openreadera.zlib.ZLibDownloadState
