package org.readera.openreadera.engine

import android.graphics.*
import android.util.Log
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

class CbzEngine : DocumentEngine {

    companion object {
        private const val TAG = "CbzEngine"
        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")
    }

    override val isAvailable: Boolean = true
    override val engineName: String = "Axiom CBZ/Comic Core"

    private var zipFile: ZipFile? = null
    private val imageEntries = mutableListOf<ZipEntry>()

    override fun open(path: String, password: String?): Boolean {
        return try {
            val file = File(path)
            if (!file.exists() || !file.canRead()) return false

            val zip = ZipFile(file)
            zipFile = zip
            imageEntries.clear()

            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (!entry.isDirectory) {
                    val ext = entry.name.substringAfterLast('.', "").lowercase()
                    if (ext in IMAGE_EXTENSIONS && !entry.name.startsWith("__MACOSX")) {
                        imageEntries.add(entry)
                    }
                }
            }

            // Natural sort: page_1.jpg, page_2.jpg, page_10.jpg
            imageEntries.sortWith { a, b ->
                extractNumberOrName(a.name).compareTo(extractNumberOrName(b.name))
            }

            imageEntries.isNotEmpty()
        } catch (e: Exception) {
            Log.e(TAG, "Error opening CBZ archive: $path", e)
            false
        }
    }

    private fun extractNumberOrName(name: String): String {
        val numMatch = Regex("""(\d+)""").find(name.substringAfterLast('/'))
        return if (numMatch != null) {
            val num = numMatch.groupValues[1].padStart(8, '0')
            name.substringBefore(numMatch.groupValues[1]) + num
        } else {
            name
        }
    }

    override fun getPageCount(): Int = maxOf(1, imageEntries.size)

    override fun getPageSize(pageIndex: Int): PageSize {
        val entry = imageEntries.getOrNull(pageIndex) ?: return PageSize(1200f, 1800f)
        val zip = zipFile ?: return PageSize(1200f, 1800f)

        return try {
            zip.getInputStream(entry).use { stream ->
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(stream, null, opts)
                if (opts.outWidth > 0 && opts.outHeight > 0) {
                    PageSize(opts.outWidth.toFloat(), opts.outHeight.toFloat())
                } else {
                    PageSize(1200f, 1800f)
                }
            }
        } catch (_: Exception) {
            PageSize(1200f, 1800f)
        }
    }

    override fun renderPage(pageIndex: Int, targetBitmap: Bitmap, options: RenderOptions): Boolean {
        val entry = imageEntries.getOrNull(pageIndex) ?: return false
        val zip = zipFile ?: return false

        var decodedBitmap: Bitmap? = null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            zip.getInputStream(entry).use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false

            val decodeScale = minOf(
                targetBitmap.width.toFloat() / bounds.outWidth,
                targetBitmap.height.toFloat() / bounds.outHeight
            ) * options.zoom
            val requiredWidth = bounds.outWidth * decodeScale
            val requiredHeight = bounds.outHeight * decodeScale
            var sampleSize = 1
            while (bounds.outWidth / (sampleSize * 2) >= requiredWidth &&
                bounds.outHeight / (sampleSize * 2) >= requiredHeight
            ) {
                sampleSize *= 2
            }

            val bitmap = zip.getInputStream(entry).use { stream ->
                BitmapFactory.decodeStream(
                    stream,
                    null,
                    BitmapFactory.Options().apply {
                        inSampleSize = sampleSize
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                )
            } ?: return false
            decodedBitmap = bitmap


            val canvas = Canvas(targetBitmap)
            val bgColor = when {
                options.oledMode -> Color.BLACK
                options.nightMode -> Color.parseColor("#121212")
                else -> Color.WHITE
            }
            canvas.drawColor(bgColor)

            val srcW = bitmap.width.toFloat()
            val srcH = bitmap.height.toFloat()
            val dstW = targetBitmap.width.toFloat()
            val dstH = targetBitmap.height.toFloat()

            // Fit inside dst keeping aspect ratio
            val scale = minOf(dstW / srcW, dstH / srcH) * options.zoom
            val fitW = srcW * scale
            val fitH = srcH * scale

            val left = (dstW - fitW) / 2f
            val top = (dstH - fitH) / 2f

            val destRect = RectF(left, top, left + fitW, top + fitH)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

            // Night mode color filter if desired
            if (options.nightMode || options.oledMode) {
                val colorMatrix = ColorMatrix(floatArrayOf(
                    0.9f, 0f, 0f, 0f, 0f,
                    0f, 0.9f, 0f, 0f, 0f,
                    0f, 0f, 0.9f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                ))
                paint.colorFilter = ColorMatrixColorFilter(colorMatrix)
            }

            canvas.drawBitmap(bitmap, null, destRect, paint)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error rendering CBZ page $pageIndex", e)
            false
        } finally {
            decodedBitmap?.recycle()
        }
    }

    override fun getPageText(pageIndex: Int): String {
        return ""
    }

    override fun getOutline(): List<OutlineItem> {
        return imageEntries.mapIndexed { idx, entry ->
            val simpleName = entry.name.substringAfterLast('/')
            OutlineItem(title = "Página ${idx + 1} ($simpleName)", page = idx)
        }
    }

    override fun search(query: String): List<SearchResult> = emptyList()

    override fun close() {
        try {
            zipFile?.close()
        } catch (_: Exception) {}
        zipFile = null
        imageEntries.clear()
    }
}
