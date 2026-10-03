package org.readera.openreadera.ui.navigation

sealed class Screen(val route: String) {
    object Library : Screen("library")
    object DocumentDetails : Screen("document_details/{bookId}") {
        fun createRoute(bookId: Long): String = "document_details/$bookId"
    }
    object Reader : Screen("reader/{bookId}") {
        fun createRoute(bookId: Long): String = "reader/$bookId"
    }
    object Settings : Screen("settings")
}
