package org.readera.openreadera.engine

import android.graphics.Bitmap

interface DocumentEngine : AutoCloseable {
    val isAvailable: Boolean
    val engineName: String

    fun open(path: String, password: String? = null): Boolean
    fun getPageCount(): Int
    fun getPageSize(pageIndex: Int): PageSize
    fun renderPage(pageIndex: Int, targetBitmap: Bitmap, options: RenderOptions): Boolean
    fun getPageText(pageIndex: Int): String
    /** Call on IO under the same engine synchronization as rendering, never during a draw frame. */
    fun getPageTextLayout(pageIndex: Int): PageTextLayout =
        PageTextLayout(getPageText(pageIndex), emptyList())
    fun getPageTextRange(pageIndex: Int): PageTextRange? = null
    /** Text line bounds normalized to the page bitmap (0..1); empty when the format exposes none. */
    fun getPageTextLineBounds(pageIndex: Int): List<android.graphics.RectF> = emptyList()
    fun getOutline(): List<OutlineItem>
    fun search(query: String): List<SearchResult>
    fun applyOptions(options: RenderOptions): Int = getPageCount()
    override fun close()
}
