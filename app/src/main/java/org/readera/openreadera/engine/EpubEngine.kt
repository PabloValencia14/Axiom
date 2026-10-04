package org.readera.openreadera.engine

import android.graphics.*
import android.graphics.text.LineBreaker
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.StyleSpan
import android.util.Log
import java.io.File
import java.util.zip.ZipFile

class EpubEngine : DocumentEngine {

    companion object {
        private const val TAG = "EpubEngine"
    }

    override val isAvailable: Boolean = true
    override val engineName: String = "Axiom EPUB Core"

    data class Chapter(
        val id: String,
        val href: String,
        val title: String,
        val cleanText: String
    )

    data class EpubPage(
        val chapterIndex: Int,
        val chapterTitle: String,
        val pageNumber: Int,
        val startOffset: Int,
        val endOffset: Int
    )

    private var zipFile: ZipFile? = null
    private var opfDir: String = ""
    private var bookTitle: String = ""
    private var bookAuthor: String = ""
    private val chapters = mutableListOf<Chapter>()
    private val pages = mutableListOf<EpubPage>()
    private val outlineItems = mutableListOf<OutlineItem>()

    override fun open(path: String, password: String?): Boolean {
        close()
        var zip: ZipFile? = null
        var openedSuccessfully = false
        try {
            val file = File(path)
            if (!file.exists() || !file.canRead()) {
                Log.e(TAG, "File cannot be read: $path")
                return false
            }

            zip = ZipFile(file)
            val budget = EpubReadBudget()
            budget.checkArchive(zip)

            // 1. Locate rootfile from META-INF/container.xml
            val containerEntry = zip.getEntry("META-INF/container.xml")
            if (containerEntry == null) {
                Log.e(TAG, "Not a valid EPUB: META-INF/container.xml missing")
                return false
            }

            val containerXml = budget.readText(zip, containerEntry)
            val opfPath = extractOpfPath(containerXml)
            if (opfPath.isEmpty()) {
                Log.e(TAG, "Failed to resolve OPF path from container")
                return false
            }

            opfDir = if (opfPath.contains("/")) opfPath.substringBeforeLast("/") + "/" else ""

            // 2. Parse OPF package file
            val opfEntry = zip.getEntry(opfPath) ?: zip.getEntry(opfPath.removePrefix("/"))
            if (opfEntry == null) {
                Log.e(TAG, "OPF entry not found: $opfPath")
                return false
            }

            val opfXml = budget.readText(zip, opfEntry)
            parseOpf(opfXml, zip, budget)

            // 3. Paginate book content
            paginate()

            if (pages.isEmpty() && chapters.isEmpty()) return false
            zipFile = zip
            openedSuccessfully = true
            Log.i(TAG, "EPUB opened successfully: '$bookTitle' by '$bookAuthor'. Chapters: ${chapters.size}, Pages: ${pages.size}")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error opening EPUB $path", e)
            return false
        } finally {
            if (!openedSuccessfully) {
                try {
                    zip?.close()
                } catch (_: Exception) {}
                close()
            }
        }
    }

    private fun extractOpfPath(containerXml: String): String {
        val match = Regex("""full-path\s*=\s*["']([^"']+)["']""").find(containerXml)
        return match?.groupValues?.get(1) ?: "OEBPS/content.opf"
    }

    private fun parseOpf(opfXml: String, zip: ZipFile, budget: EpubReadBudget) {
        // Extract metadata
        val titleMatch = Regex("""<dc:title[^>]*>(.*?)</dc:title>""", RegexOption.DOT_MATCHES_ALL).find(opfXml)
        bookTitle = titleMatch?.groupValues?.get(1)?.trim() ?: "Sin título"

        val creatorMatch = Regex("""<dc:creator[^>]*>(.*?)</dc:creator>""", RegexOption.DOT_MATCHES_ALL).find(opfXml)
        bookAuthor = creatorMatch?.groupValues?.get(1)?.trim() ?: "Autor desconocido"

        // Extract manifest items: id -> href
        val manifestMap = mutableMapOf<String, String>()
        val itemRegex = Regex("""<item\s+[^>]*id\s*=\s*["']([^"']+)["'][^>]*href\s*=\s*["']([^"']+)["']|<item\s+[^>]*href\s*=\s*["']([^"']+)["'][^>]*id\s*=\s*["']([^"']+)["']""")
        itemRegex.findAll(opfXml).forEach { match ->
            budget.addManifestItem()
            val id = match.groups[1]?.value ?: match.groups[4]?.value
            val href = match.groups[2]?.value ?: match.groups[3]?.value
            if (id != null && href != null) {
                manifestMap[id] = href
            }
        }

        // Extract spine items in order
        val spineItems = mutableListOf<String>()
        val spineMatch = Regex("""<spine[^>]*>(.*?)</spine>""", RegexOption.DOT_MATCHES_ALL).find(opfXml)
        if (spineMatch != null) {
            val itemrefRegex = Regex("""<itemref\s+[^>]*idref\s*=\s*["']([^"']+)["']""")
            itemrefRegex.findAll(spineMatch.groupValues[1]).forEach { ref ->
                budget.addSpineItem()
                spineItems.add(ref.groupValues[1])
            }
        }

        // Load chapters in spine order
        var chapterIndex = 1
        for (idref in spineItems) {
            val relativeHref = manifestMap[idref] ?: continue
            val fullHref = resolveZipPath(opfDir, relativeHref)

            val entry = zip.getEntry(fullHref) ?: zip.getEntry(fullHref.removePrefix("/")) ?: continue
            val rawHtml = budget.readText(zip, entry)
            val cleanText = htmlToCleanText(rawHtml)

            if (cleanText.isBlank()) continue
            budget.retainText(cleanText)

            // Determine chapter title
            val chapterTitle = extractChapterHeading(rawHtml, chapterIndex)
            chapters.add(
                Chapter(
                    id = idref,
                    href = fullHref,
                    title = chapterTitle,
                    cleanText = cleanText
                )
            )
            chapterIndex++
        }

        // Also build outline items from chapters
        chapters.forEachIndexed { idx, chapter ->
            outlineItems.add(OutlineItem(title = chapter.title, page = idx, level = 0))
        }
    }

    private fun resolveZipPath(baseDir: String, relativePath: String): String {
        val decoded = try { java.net.URLDecoder.decode(relativePath, "UTF-8") } catch (_: Exception) { relativePath }
        if (baseDir.isEmpty()) return decoded
        val combined = "$baseDir$decoded"
        val parts = combined.split('/')
        val resolved = mutableListOf<String>()
        for (p in parts) {
            if (p == "..") {
                if (resolved.isNotEmpty()) resolved.removeAt(resolved.size - 1)
            } else if (p != "." && p.isNotEmpty()) {
                resolved.add(p)
            }
        }
        return resolved.joinToString("/")
    }

    private fun htmlToCleanText(html: String): String {
        val noScripts = html
            .replace(Regex("""<script[^>]*>.*?</script>""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""<style[^>]*>.*?</style>""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""<head[^>]*>.*?</head>""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL), "")

        // Replace block tags with double newlines
        val withBlocks = noScripts
            .replace(Regex("""</?(?:p|div|section|article|blockquote|header|nav|footer)[^>]*>""", RegexOption.IGNORE_CASE), "\n\n")
            .replace(Regex("""<h[1-6][^>]*>(.*?)</h[1-6]>""", RegexOption.IGNORE_CASE)) { "\n\n${it.groupValues[1]}\n\n" }
            .replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("""</?(?:tr|li)[^>]*>""", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("""</?(?:td|th)[^>]*>""", RegexOption.IGNORE_CASE), "   ")
            .replace(Regex("""<hr\s*/?>""", RegexOption.IGNORE_CASE), "\n\n---\n\n")
            // Replace any other tags (inline like <span>, <a>, <i>, <b>) with a single space to avoid word-joining
            .replace(Regex("""<[^>]+>"""), " ")

        // Decode HTML entities
        val decoded = withBlocks
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&#8212;", "—")
            .replace("&mdash;", "—")
            .replace("&#8211;", "–")
            .replace("&ndash;", "–")
            .replace("&#8220;", "“")
            .replace("&ldquo;", "“")
            .replace("&#8221;", "”")
            .replace("&rdquo;", "”")
            .replace("&#8216;", "‘")
            .replace("&lsquo;", "‘")
            .replace("&#8217;", "’")
            .replace("&rsquo;", "’")
            .replace("&#8230;", "…")
            .replace("&hellip;", "…")
            .replace(Regex("""&#(\d+);""")) { match ->
                val code = match.groupValues[1].toIntOrNull()
                if (code != null && code in 32..65535) code.toChar().toString() else " "
            }
            .replace(Regex("""&#x([0-9a-fA-F]+);""")) { match ->
                val code = match.groupValues[1].toIntOrNull(16)
                if (code != null && code in 32..65535) code.toChar().toString() else " "
            }

        // Split into paragraphs by multiple newlines
        val paragraphs = decoded.split(Regex("""\n{2,}"""))
        val cleanParagraphs = mutableListOf<String>()

        for (para in paragraphs) {
            // Inside each paragraph, collapse internal whitespace (\r, \n, \t, spaces) into a single space
            val trimmedPara = para.replace(Regex("""\s+"""), " ").trim()
            if (trimmedPara.isNotEmpty()) {
                cleanParagraphs.add(trimmedPara)
            }
        }

        return cleanParagraphs.joinToString("\n\n")
    }

    private fun extractChapterHeading(html: String, chapterIdx: Int): String {
        val hMatch = Regex("""<h[1-4][^>]*>(.*?)</h[1-4]>""", RegexOption.DOT_MATCHES_ALL).find(html)
        if (hMatch != null) {
            val title = hMatch.groupValues[1].replace(Regex("""<[^>]+>"""), " ").replace(Regex("""\s+"""), " ").trim()
            if (title.isNotEmpty() && title.length < 80 && !title.contains("Project Gutenberg", true)) {
                return title
            }
        }
        val pMatch = Regex("""<p[^>]*class="[^"]*(?:chapter|title|heading)[^"]*"[^>]*>(.*?)</p>""", RegexOption.DOT_MATCHES_ALL).find(html)
        if (pMatch != null) {
            val title = pMatch.groupValues[1].replace(Regex("""<[^>]+>"""), " ").replace(Regex("""\s+"""), " ").trim()
            if (title.isNotEmpty() && title.length < 80 && !title.contains("Project Gutenberg", true)) {
                return title
            }
        }
        return "Capítulo $chapterIdx"
    }

    private var currentOptions = RenderOptions()

    private val layoutCache = android.util.LruCache<String, StaticLayout>(60)
    private val textLayoutCache = PageTextLayoutCache()

    override fun applyOptions(options: RenderOptions): Int {
        if (options != currentOptions) textLayoutCache.evictAll()
        val fontSizeChanged = options.fontSizeSp != currentOptions.fontSizeSp
        val fontFamilyChanged = options.fontFamily != currentOptions.fontFamily
        val fontBoldChanged = options.fontBold != currentOptions.fontBold
        val lineSpacingChanged = options.lineSpacing != currentOptions.lineSpacing
        val marginChanged = options.marginHorizontalDp != currentOptions.marginHorizontalDp
        val ratioChanged = kotlin.math.abs(options.targetAspectRatio - currentOptions.targetAspectRatio) > 0.02f
        val themeChanged = options.nightMode != currentOptions.nightMode ||
                options.oledMode != currentOptions.oledMode ||
                options.twilightMode != currentOptions.twilightMode ||
                options.consoleMode != currentOptions.consoleMode

        currentOptions = options.copy()

        if (themeChanged) {
            layoutCache.evictAll()
        }

        if (fontSizeChanged || fontFamilyChanged || fontBoldChanged || lineSpacingChanged || marginChanged || ratioChanged || pages.isEmpty()) {
            layoutCache.evictAll()
            paginate()
        }
        return getPageCount()
    }

    private fun getDimensions(): Pair<Float, Float> {
        val targetRatio = if (currentOptions.targetAspectRatio > 0.2f) currentOptions.targetAspectRatio else (1600f / 1200f)
        val w = 1400f
        val h = (w * targetRatio).coerceIn(400f, 4200f)
        return Pair(w, h)
    }

    private fun paginate() {
        pages.clear()
        val (w, h) = getDimensions()
        val marginX = (currentOptions.marginHorizontalDp * (w / 400f)).coerceIn(32f, 160f)
        val marginTop = (h * 0.045f).coerceIn(36f, 85f)
        val marginBottom = (h * 0.05f).coerceIn(40f, 90f)
        val contentWidth = maxOf(200, (w - (marginX * 2)).toInt())
        val bodyStartY = marginTop + 36f
        val availableHeight = (h - bodyStartY - marginBottom).coerceAtLeast(300f)

        val scaledFontSize = (currentOptions.fontSizeSp * (w / 700f)).coerceIn(22f, 60f)
        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            color = Color.BLACK
            textSize = scaledFontSize
            typeface = Typeface.create(
                when (currentOptions.fontFamily.lowercase()) {
                    "serif" -> Typeface.SERIF
                    "monospace" -> Typeface.MONOSPACE
                    "cursive" -> Typeface.create("cursive", Typeface.NORMAL)
                    else -> Typeface.SANS_SERIF
                },
                if (currentOptions.fontBold) Typeface.BOLD else Typeface.NORMAL
            )
        }

        var pageNum = 0
        for ((chIdx, chapter) in chapters.withIndex()) {
            val text = chapter.cleanText
            if (text.isBlank()) continue

            try {
                val fullLayout = StaticLayout.Builder.obtain(
                    text,
                    0,
                    text.length,
                    textPaint,
                    contentWidth
                )
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setLineSpacing(0f, currentOptions.lineSpacing)
                    .setIncludePad(false)
                    .setBreakStrategy(LineBreaker.BREAK_STRATEGY_HIGH_QUALITY)
                    .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL)
                    .build()

                val totalLines = fullLayout.lineCount
                var currentLine = 0

                while (currentLine < totalLines) {
                    val lineTop = fullLayout.getLineTop(currentLine)
                    var lastLine = currentLine
                    while (lastLine + 1 < totalLines && (fullLayout.getLineBottom(lastLine + 1) - lineTop) <= availableHeight) {
                        lastLine++
                    }
                    val startChar = fullLayout.getLineStart(currentLine)
                    val endChar = fullLayout.getLineEnd(lastLine)
                    val rawChunk = text.substring(startChar, endChar)
                    val pageChunk = rawChunk.trim()
                    if (pageChunk.isNotEmpty()) {
                        val contentStart = startChar + rawChunk.indexOf(pageChunk).coerceAtLeast(0)
                        pages.add(EpubPage(chIdx, chapter.title, pageNum++, contentStart, contentStart + pageChunk.length))
                    }
                    currentLine = lastLine + 1
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error paginating chapter $chIdx", e)
                var end = minOf(1200, text.length)
                if (end < text.length && Character.isHighSurrogate(text[end - 1]) && Character.isLowSurrogate(text[end])) end--
                pages.add(EpubPage(chIdx, chapter.title, pageNum++, 0, end))
            }
        }

        if (pages.isEmpty()) {
            pages.add(EpubPage(-1, bookTitle, 0, 0, 0))
        }

        // Adjust outline items to point to actual first page of each chapter
        outlineItems.clear()
        for ((chIdx, chapter) in chapters.withIndex()) {
            val firstPage = pages.indexOfFirst { it.chapterIndex == chIdx }
            val page = if (firstPage >= 0) firstPage else 0
            outlineItems.add(OutlineItem(title = chapter.title, page = page, level = 0))
        }
    }

    private fun pageText(page: EpubPage): String {
        val chapter = chapters.getOrNull(page.chapterIndex) ?: return ""
        val start = page.startOffset.coerceIn(0, chapter.cleanText.length)
        val end = page.endOffset.coerceIn(start, chapter.cleanText.length)
        return chapter.cleanText.substring(start, end)
    }

    override fun getPageCount(): Int = maxOf(1, pages.size)

    override fun getPageTextRange(pageIndex: Int): PageTextRange? {
        val page = pages.getOrNull(pageIndex) ?: return null
        return if (page.chapterIndex >= 0) {
            PageTextRange(page.chapterIndex, page.startOffset, page.endOffset)
        } else {
            null
        }
    }

    override fun getPageSize(pageIndex: Int): PageSize {
        val (w, h) = getDimensions()
        return PageSize(w, h)
    }

    override fun renderPage(pageIndex: Int, targetBitmap: Bitmap, options: RenderOptions): Boolean {
        val page = pages.getOrNull(pageIndex) ?: pages.firstOrNull() ?: return false

        // Color palette based on theme
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
        val marginTop = (h * 0.045f).coerceIn(36f * scale, 85f * scale)
        val marginBottom = (h * 0.05f).coerceIn(40f * scale, 90f * scale)
        val bodyStartY = marginTop + (36f * scale)

        // Header / Chapter Title
        val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            color = headerColor
            textSize = ((options.fontSizeSp * 0.72f) * (1400f / 700f)).coerceIn(18f, 45f) * scale
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        }

        val headerText = if (page.chapterTitle.length > 40) {
            page.chapterTitle.take(38) + "…"
        } else {
            page.chapterTitle
        }
        canvas.drawText(headerText, marginX, marginTop, headerPaint)

        // Divider line
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = headerColor
            alpha = 45
            strokeWidth = 2f * scale
        }
        canvas.drawLine(marginX, marginTop + 12f * scale, w - marginX, marginTop + 12f * scale, linePaint)

        // Body Text
        val staticLayout = getPageTextLayout(pageIndex, page, w, options)

        canvas.save()
        canvas.translate(marginX, bodyStartY)
        staticLayout.draw(canvas)
        canvas.restore()

        // Page Number at bottom
        val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            color = headerColor
            textSize = ((options.fontSizeSp * 0.7f) * (1400f / 700f)).coerceIn(16f, 40f) * scale
            textAlign = Paint.Align.CENTER
        }
        val footerText = "${pageIndex + 1} / ${pages.size}"
        canvas.drawText(footerText, w / 2f, h - (marginBottom * 0.45f), footerPaint)

        return true
    }
    override fun getPageTextLineBounds(pageIndex: Int): List<RectF> = getPageTextLayout(pageIndex).lineBounds()

    override fun getPageTextLayout(pageIndex: Int): PageTextLayout {
        textLayoutCache.get(pageIndex)?.let { return it }
        val page = pages.getOrNull(pageIndex) ?: return PageTextLayout("", emptyList())
        val (pageWidth, pageHeight) = getDimensions()
        val options = currentOptions
        val scale = pageWidth / 1400f
        val marginX = (options.marginHorizontalDp * (1400f / 400f)).coerceIn(32f, 160f) * scale
        val marginTop = (pageHeight * 0.045f).coerceIn(36f * scale, 85f * scale)
        val text = pageText(page)
        return getPageTextLayout(pageIndex, page, pageWidth.toInt(), options)
            .pageTextLayout(text, pageWidth, pageHeight, marginX, marginTop + 36f * scale)
            .also { textLayoutCache.put(pageIndex, it) }
    }

    private fun getPageTextLayout(
        pageIndex: Int,
        page: EpubPage,
        width: Int,
        options: RenderOptions
    ): StaticLayout {
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
        val text = pageText(page)
        val displayText: CharSequence = if (options.bionicReading) applyBionicReading(text) else text
        val contentWidth = maxOf(200, (width - (marginX * 2)).toInt())
        return StaticLayout.Builder.obtain(displayText, 0, displayText.length, textPaint, contentWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, options.lineSpacing)
            .setIncludePad(false)
            .setBreakStrategy(LineBreaker.BREAK_STRATEGY_HIGH_QUALITY)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL)
            .build()
            .also { layoutCache.put(cacheKey, it) }
    }

    override fun getPageText(pageIndex: Int): String {
        val page = pages.getOrNull(pageIndex) ?: return ""
        return pageText(page)
    }

    override fun getOutline(): List<OutlineItem> = outlineItems

    override fun search(query: String): List<SearchResult> {
        if (query.isBlank()) return emptyList()
        val results = mutableListOf<SearchResult>()
        for ((idx, page) in pages.withIndex()) {
            val text = pageText(page)
            var index = text.indexOf(query, 0, ignoreCase = true)
            while (index != -1 && results.size < 50) {
                val start = maxOf(0, index - 35)
                val end = minOf(text.length, index + query.length + 35)
                val snippet = "…" + text.substring(start, end).replace('\n', ' ') + "…"
                results.add(SearchResult(page = idx, snippet = snippet))
                index = text.indexOf(query, index + query.length, ignoreCase = true)
            }
        }
        return results
    }

    private fun applyBionicReading(text: String): CharSequence {
        val spannable = SpannableStringBuilder(text)
        val wordRegex = Regex("""\b\w+\b""")
        for (match in wordRegex.findAll(text)) {
            val word = match.value
            val start = match.range.first
            val boldLen = when (word.length) {
                1 -> 1
                2, 3 -> 1
                else -> (word.length + 1) / 2
            }
            spannable.setSpan(
                StyleSpan(Typeface.BOLD),
                start,
                start + boldLen,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        return spannable
    }

    override fun close() {
        try {
            zipFile?.close()
        } catch (_: Exception) {}
        zipFile = null
        opfDir = ""
        bookTitle = ""
        bookAuthor = ""
        layoutCache.evictAll()
        textLayoutCache.evictAll()
        chapters.clear()
        pages.clear()
        outlineItems.clear()
    }
}
