package org.readera.openreadera.engine

import android.graphics.*
import android.graphics.text.LineBreaker
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Log
import java.io.File

class TxtEngine : DocumentEngine {

    companion object {
        private const val TAG = "TxtEngine"
        private const val CHARS_PER_PAGE = 1800
        private val BIONIC_WORD_REGEX = Regex("""\b\w+\b""")
    }

    override val isAvailable: Boolean = true
    override val engineName: String = "Axiom TXT/FB2 Core"

    private val pages = mutableListOf<String>()
    private val outlineItems = mutableListOf<OutlineItem>()
    private var docTitle: String = ""
    private val layoutCache = android.util.LruCache<String, StaticLayout>(24)
    private val textLayoutCache = PageTextLayoutCache()

    override fun open(path: String, password: String?): Boolean {
        close()
        try {
            val file = File(path)
            if (!file.exists() || !file.canRead()) return false

            docTitle = file.nameWithoutExtension.replace('_', ' ')
            paginate(file)
            Log.i(TAG, "TXT file loaded with ${pages.size} pages")
            return pages.isNotEmpty()
        } catch (e: Exception) {
            Log.e(TAG, "Error opening text document $path", e)
            return false
        }
    }

    private fun paginate(file: File) {
        pages.clear()
        outlineItems.clear()
        var pageNum = 0
        val pending = StringBuilder(CHARS_PER_PAGE + 1)

        fun commitPage(end: Int) {
            val chunk = pending.substring(0, end).trim()
            if (chunk.isNotEmpty()) {
                pages.add(chunk)
                if (pageNum % 10 == 0) {
                    outlineItems.add(OutlineItem(title = "Sección ${(pageNum / 10) + 1}", page = pageNum, level = 0))
                }
                pageNum++
            }
            pending.delete(0, end)
        }

        fun commitReadyPages() {
            while (pending.length > CHARS_PER_PAGE) {
                var end = CHARS_PER_PAGE
                // A streaming chunk must not separate a UTF-16 surrogate pair.
                if (Character.isHighSurrogate(pending[end - 1]) && Character.isLowSurrogate(pending[end])) end--
                val breakPoint = maxOf(
                    pending.lastIndexOf("\n", CHARS_PER_PAGE),
                    pending.lastIndexOf(".", CHARS_PER_PAGE)
                )
                if (breakPoint > CHARS_PER_PAGE / 2) end = breakPoint + 1
                commitPage(end)
            }
        }

        file.bufferedReader(Charsets.UTF_8).use { reader ->
            val input = CharArray(8192)
            var pendingCarriageReturn = false
            while (true) {
                val count = reader.read(input)
                if (count < 0) break
                for (index in 0 until count) {
                    val char = input[index]
                    if (pendingCarriageReturn) {
                        if (char == '\n') {
                            pending.append('\n')
                            pendingCarriageReturn = false
                            commitReadyPages()
                            continue
                        }
                        pending.append('\r')
                        pendingCarriageReturn = false
                        commitReadyPages()
                    }
                    if (char == '\r') {
                        pendingCarriageReturn = true
                    } else {
                        pending.append(char)
                        commitReadyPages()
                    }
                }
            }
            if (pendingCarriageReturn) {
                pending.append('\r')
                commitReadyPages()
            }
        }

        if (pending.isNotEmpty()) commitPage(pending.length)
    }

    private var currentOptions = RenderOptions()

    private fun getDimensions(): Pair<Float, Float> {
        val targetRatio = if (currentOptions.targetAspectRatio > 0.2f) currentOptions.targetAspectRatio else (1600f / 1200f)
        val w = 1400f
        val h = (w * targetRatio).coerceIn(400f, 4200f)
        return Pair(w, h)
    }

    override fun applyOptions(options: RenderOptions): Int {
        if (options != currentOptions) {
            layoutCache.evictAll()
            textLayoutCache.evictAll()
        }
        currentOptions = options.copy()
        return getPageCount()
    }

    override fun getPageCount(): Int = maxOf(1, pages.size)

    override fun getPageSize(pageIndex: Int): PageSize {
        val (w, h) = getDimensions()
        return PageSize(w, h)
    }

    override fun renderPage(pageIndex: Int, targetBitmap: Bitmap, options: RenderOptions): Boolean {
        val text = pages.getOrNull(pageIndex) ?: pages.firstOrNull() ?: return false

        val (bgColor, _, headerColor) = when {
            options.consoleMode -> Triple(Color.parseColor("#001400"), Color.parseColor("#00FF66"), Color.parseColor("#00CC52"))
            options.oledMode -> Triple(Color.parseColor("#000000"), Color.parseColor("#E4E4E4"), Color.parseColor("#888888"))
            options.nightMode -> Triple(Color.parseColor("#1A1A1A"), Color.parseColor("#D4D4D4"), Color.parseColor("#888888"))
            options.twilightMode -> Triple(Color.parseColor("#FBF0D9"), Color.parseColor("#3C2E1E"), Color.parseColor("#7A5D3E"))
            else -> Triple(Color.parseColor("#FFFFFF"), Color.parseColor("#181818"), Color.parseColor("#666666"))
        }

        val canvas = Canvas(targetBitmap)
        canvas.drawColor(bgColor)

        val (pageWidth, pageHeight) = getDimensions()
        val w = pageWidth.toInt()
        val h = pageHeight.toInt()
        canvas.scale(targetBitmap.width / pageWidth, targetBitmap.height / pageHeight)
        val scale = w / 1400f

        val marginX = (options.marginHorizontalDp * (1400f / 400f)).coerceIn(32f, 160f) * scale
        val marginTop = (h * 0.035f).coerceIn(24f * scale, 65f * scale)
        val marginBottom = (h * 0.045f).coerceIn(28f * scale, 75f * scale)
        val bodyStartY = marginTop + (32f * scale)

        // Title
        val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            color = headerColor
            textSize = ((options.fontSizeSp * 0.72f) * (1400f / 700f)).coerceIn(18f, 45f) * scale
        }
        canvas.drawText(docTitle, marginX, marginTop, headerPaint)

        // Line
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = headerColor
            alpha = 50
            strokeWidth = 2f * scale
        }
        canvas.drawLine(marginX, marginTop + 12f * scale, w - marginX, marginTop + 12f * scale, linePaint)

        // Body Text
        val staticLayout = getPageBodyLayout(pageIndex, text, w, options)

        canvas.save()
        canvas.translate(marginX, bodyStartY)
        staticLayout.draw(canvas)
        canvas.restore()

        // Page number
        val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = headerColor
            textSize = ((options.fontSizeSp * 0.7f) * (1400f / 700f)).coerceIn(16f, 40f) * scale
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("${pageIndex + 1} / ${pages.size}", w / 2f, h - (marginBottom / 2f), footerPaint)

        return true
    }
    override fun getPageTextLineBounds(pageIndex: Int): List<RectF> = getPageTextLayout(pageIndex).lineBounds()

    override fun getPageTextLayout(pageIndex: Int): PageTextLayout {
        textLayoutCache.get(pageIndex)?.let { return it }
        val text = pages.getOrNull(pageIndex) ?: return PageTextLayout("", emptyList())
        val (pageWidth, pageHeight) = getDimensions()
        val options = currentOptions
        val scale = pageWidth / 1400f
        val marginX = (options.marginHorizontalDp * (1400f / 400f)).coerceIn(32f, 160f) * scale
        val marginTop = (pageHeight * 0.035f).coerceIn(24f * scale, 65f * scale)
        return getPageBodyLayout(pageIndex, text, pageWidth.toInt(), options)
            .pageTextLayout(text, pageWidth, pageHeight, marginX, marginTop + 32f * scale)
            .also { textLayoutCache.put(pageIndex, it) }
    }

    private fun getPageBodyLayout(pageIndex: Int, text: String, width: Int, options: RenderOptions): StaticLayout {
        val cacheKey = "${pageIndex}_${width}_$options"
        layoutCache.get(cacheKey)?.let { return it }
        val scale = width / 1400f
        val marginX = (options.marginHorizontalDp * (1400f / 400f)).coerceIn(32f, 160f) * scale
        val scaledFontSize = (options.fontSizeSp * (1400f / 700f)).coerceIn(22f, 60f) * scale
        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            color = when {
                options.consoleMode -> Color.parseColor("#00FF66")
                options.oledMode -> Color.parseColor("#E4E4E4")
                options.nightMode -> Color.parseColor("#D4D4D4")
                options.twilightMode -> Color.parseColor("#3C2E1E")
                else -> Color.parseColor("#181818")
            }
            textSize = scaledFontSize
            typeface = Typeface.create(
                when (options.fontFamily.lowercase()) {
                    "serif" -> Typeface.SERIF
                    "monospace" -> Typeface.MONOSPACE
                    "cursive" -> Typeface.create("cursive", Typeface.NORMAL)
                    else -> Typeface.SANS_SERIF
                },
                if (options.fontBold) Typeface.BOLD else Typeface.NORMAL
            )
        }
        val displayText: CharSequence = if (options.bionicReading) applyBionicReading(text) else text
        val contentWidth = maxOf(200, (width - (marginX * 2)).toInt())
        return StaticLayout.Builder.obtain(displayText, 0, displayText.length, textPaint, contentWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, options.lineSpacing)
            .setIncludePad(true)
            .setBreakStrategy(LineBreaker.BREAK_STRATEGY_HIGH_QUALITY)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL)
            .build()
            .also { layoutCache.put(cacheKey, it) }
    }

    override fun getPageText(pageIndex: Int): String {
        return pages.getOrNull(pageIndex) ?: ""
    }

    override fun getOutline(): List<OutlineItem> = outlineItems

    override fun search(query: String): List<SearchResult> {
        if (query.isBlank()) return emptyList()
        val results = mutableListOf<SearchResult>()
        for ((idx, page) in pages.withIndex()) {
            var index = page.indexOf(query, 0, ignoreCase = true)
            while (index != -1 && results.size < 50) {
                val start = maxOf(0, index - 35)
                val end = minOf(page.length, index + query.length + 35)
                val snippet = "…" + page.substring(start, end).replace('\n', ' ') + "…"
                results.add(SearchResult(page = idx, snippet = snippet))
                index = page.indexOf(query, index + query.length, ignoreCase = true)
            }
        }
        return results
    }

    override fun close() {
        pages.clear()
        outlineItems.clear()
        layoutCache.evictAll()
        textLayoutCache.evictAll()
    }
    private fun applyBionicReading(text: String): CharSequence {
        val spannable = android.text.SpannableStringBuilder(text)
        for (match in BIONIC_WORD_REGEX.findAll(text)) {
            val boldLength = when (match.value.length) {
                1 -> 1
                2, 3 -> 1
                else -> (match.value.length + 1) / 2
            }
            spannable.setSpan(
                android.text.style.StyleSpan(Typeface.BOLD),
                match.range.first,
                match.range.first + boldLength,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        return spannable
    }

}
