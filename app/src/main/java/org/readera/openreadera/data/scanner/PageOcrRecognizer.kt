package org.readera.openreadera.data.scanner

import android.graphics.Bitmap
import android.graphics.RectF
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.readera.openreadera.engine.PageTextLayout
import org.readera.openreadera.engine.PageTextSource
import org.readera.openreadera.engine.PageTextWord

/** Local bundled Latin OCR. The caller owns the upright rendered bitmap, including recycling it. */
class PageOcrRecognizer {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val requests = Mutex()
    private val lifecycleLock = Any()
    private var closed = false
    private var processing = false

    suspend fun recognize(bitmap: Bitmap): PageTextLayout = withContext(Dispatchers.IO) {
        requests.withLock {
            require(!bitmap.isRecycled && bitmap.width > 0 && bitmap.height > 0) { "Invalid OCR bitmap" }
            require(bitmap.width <= 8192 && bitmap.height <= 8192 &&
                bitmap.width.toLong() * bitmap.height <= 16_000_000L) { "OCR bitmap exceeds page limits" }
            synchronized(lifecycleLock) {
                check(!closed) { "OCR recognizer is closed" }
                processing = true
            }
            try {
                currentCoroutineContext().ensureActive()
                val task = recognizer.process(InputImage.fromBitmap(bitmap, 0))
                val result = try {
                    task.await()
                } catch (cancelled: CancellationException) {
                    // ML Kit Tasks cannot cancel this processing. Do not let the caller recycle its
                    // bitmap (or close the recognizer) until native processing has released it.
                    withContext(NonCancellable) { runCatching { task.await() } }
                    throw cancelled
                }
                val text = StringBuilder()
                val words = ArrayList<PageTextWord>()
                var lineIndex = 0
                for (block in result.textBlocks) for (line in block.lines) {
                    currentCoroutineContext().ensureActive()
                    check(lineIndex < 10_000 && text.length + line.text.length + 1 <= 100_000) {
                        "OCR text exceeds page limits"
                    }
                    if (text.isNotEmpty()) text.append('\n')
                    val lineStart = text.length
                    text.append(line.text)
                    var cursor = 0
                    for (element in line.elements) {
                        val value = element.text
                        if (value.isBlank()) continue
                        val start = line.text.indexOf(value, cursor)
                        if (start < 0) continue
                        cursor = start + value.length
                        val box = element.boundingBox ?: continue
                        val bounds = RectF(
                            (box.left.toFloat() / bitmap.width).coerceIn(0f, 1f),
                            (box.top.toFloat() / bitmap.height).coerceIn(0f, 1f),
                            (box.right.toFloat() / bitmap.width).coerceIn(0f, 1f),
                            (box.bottom.toFloat() / bitmap.height).coerceIn(0f, 1f)
                        )
                        if (bounds.width() <= 0f || bounds.height() <= 0f) continue
                        check(words.size < 20_000) { "OCR words exceed page limits" }
                        words += PageTextWord(lineStart + start, lineStart + cursor, bounds, lineIndex)
                    }
                    lineIndex++
                }
                PageTextLayout(text.toString(), words, PageTextSource.OCR)
            } finally {
                synchronized(lifecycleLock) {
                    processing = false
                    if (closed) recognizer.close()
                }
            }
        }
    }

    /** Idempotent; an in-flight ML Kit request releases the client on completion. */
    fun close() = synchronized(lifecycleLock) {
        if (!closed) {
            closed = true
            if (!processing) recognizer.close()
        }
    }
}
