package org.readera.openreadera.catalog

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Xml
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.readera.openreadera.data.scanner.StorageScanner
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
data class OpdsCatalog(val id: String, val title: String, val feedUrl: String)
data class OpdsNavigationLink(val title: String, val href: String, val rel: String)
data class OpdsAcquisition(val title: String, val href: String, val mediaType: String)
data class OpdsEntry(val id: String, val title: String, val author: String, val summary: String, val navigationUrl: String?, val acquisitions: List<OpdsAcquisition>)
data class OpdsFeedPage(val title: String, val entries: List<OpdsEntry>, val navigationLinks: List<OpdsNavigationLink>, val nextUrl: String?, val searchUrl: String?)

class OpdsCatalogRepository(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val http = OkHttpClient.Builder()
        .dns(PublicDns)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .cookieJar(CookieJar.NO_COOKIES)
        .build()

    fun getCatalogs(): List<OpdsCatalog> = prefs.getStringSet(KEY_CATALOGS, emptySet()).orEmpty().mapNotNull { raw ->
        runCatching { raw.split('|', limit = 3).let { OpdsCatalog(it[0], decode(it[1]), decode(it[2])) } }.getOrNull()
    }.sortedBy { it.title }

    @Synchronized
    fun mergePortableCatalogs(catalogs: List<OpdsCatalog>) {
        val merged = getCatalogs().associateBy { it.id }.toMutableMap()
        val credentialsToClear = mutableSetOf<String>()
        for (remote in catalogs) {
            val id = remote.id.trim()
            val title = remote.title.trim()
            if (id.isEmpty() || id.length > 128 || id.any(Char::isISOControl) || '|' in id) continue
            if (title.isEmpty() || title.length > 128 || title.any(Char::isISOControl)) continue
            val feedUrl = runCatching { checkedUrl(remote.feedUrl).toString() }.getOrNull() ?: continue
            val current = merged[id]
            if (current != null && current.feedUrl != feedUrl) credentialsToClear += id
            merged[id] = OpdsCatalog(id, title, feedUrl)
        }
        val encoded = merged.values.map { catalog ->
            "${catalog.id}|${encode(catalog.title)}|${encode(catalog.feedUrl)}"
        }.toSet()
        val editor = prefs.edit().putStringSet(KEY_CATALOGS, encoded)
        credentialsToClear.forEach { editor.remove(secretKey(it)) }
        check(editor.commit()) {
            "No se pudo sincronizar la lista de catálogos"
        }
    }

    @Synchronized
    fun addCatalog(title: String, feedUrl: String, username: String?, password: String?): OpdsCatalog {
        val cleanTitle = title.trim()
        require(cleanTitle.isNotEmpty() && cleanTitle.length <= 128)
        require(feedUrl.length in 1..2048)
        val url = checkedUrl(feedUrl)
        val id = UUID.randomUUID().toString()
        val c = OpdsCatalog(id, cleanTitle, url.toString())
        val user = username.orEmpty()
        val pass = password.orEmpty()
        require(user.length <= 256 && pass.length <= 1024)
        require(user.isEmpty() == pass.isEmpty() && ':' !in user) { "Introduce usuario y contraseña Basic válidos" }
        val secret = if (user.isNotEmpty()) encrypt("${user.length}:$user$pass") else null
        val set = prefs.getStringSet(KEY_CATALOGS, emptySet()).orEmpty().toMutableSet()
        set.add("$id|${encode(c.title)}|${encode(c.feedUrl)}")
        val editor = prefs.edit().putStringSet(KEY_CATALOGS, set)
        if (secret != null) editor.putString(secretKey(id), secret)
        check(editor.commit()) { "No se pudo guardar el catálogo" }
        return c
    }

    @Synchronized
    fun deleteCatalog(catalogId: String) {
        val set = prefs.getStringSet(KEY_CATALOGS, emptySet()).orEmpty()
            .filterNot { it.startsWith("$catalogId|") }.toSet()
        check(prefs.edit().putStringSet(KEY_CATALOGS, set).remove(secretKey(catalogId)).commit())
    }

    suspend fun browse(catalogId: String, href: String? = null): OpdsFeedPage = withContext(Dispatchers.IO) {
        val catalog = catalog(catalogId)
        val target = checkedUrl(href ?: catalog.feedUrl)
        val (bytes, finalUrl) = fetch(target, credentials(catalog, target), FEED_LIMIT)
        parseFeed(bytes, finalUrl)
    }

    suspend fun search(catalogId: String, searchUrl: String, query: String): OpdsFeedPage = withContext(Dispatchers.IO) {
        val catalog = catalog(catalogId)
        val descriptorUrl = checkedUrl(searchUrl)
        val (xml, finalUrl) = fetch(descriptorUrl, credentials(catalog, descriptorUrl), FEED_LIMIT)
        val parser = parser(xml)
        var event = nextSafeEvent(parser)
        while (event == XmlPullParser.COMMENT || event == XmlPullParser.PROCESSING_INSTRUCTION ||
            (event == XmlPullParser.TEXT && parser.isWhitespace)
        ) event = nextSafeEvent(parser)
        require(event == XmlPullParser.START_TAG && parser.name.equals("OpenSearchDescription", true)) {
            "Invalid OpenSearch descriptor"
        }
        var atomTemplate: String? = null
        while (true) {
            event = nextSafeEvent(parser)
            if (event == XmlPullParser.END_DOCUMENT) break
            if (event == XmlPullParser.START_TAG && parser.name.equals("Url", true)) {
                val template = parser.getAttributeValue(null, "template")
                val type = parser.getAttributeValue(null, "type").orEmpty()
                if (template != null && type.contains("atom", true)) atomTemplate = template
            }
        }
        val template = atomTemplate ?: error("OpenSearch descriptor has no Atom search template")
        require(template.length <= 4096)
        require(template.contains("{searchTerms}") || template.contains("{searchTerms?}"))
        val target = expandSearchTemplate(finalUrl, template, query)
        val (feed, feedFinal) = fetch(target, credentials(catalog, target), FEED_LIMIT)
        parseFeed(feed, feedFinal)
    }

    internal fun expandSearchTemplate(base: HttpUrl, template: String, query: String): HttpUrl {
        require(query.isNotBlank() && query.length <= 256) { "Search query must be 1–256 characters" }
        val terms = java.net.URLEncoder.encode(query, "UTF-8").replace("+", "%20")
        val language = java.util.Locale.getDefault().language.ifBlank { "en" }
        val values = mapOf(
            "searchTerms" to terms,
            "count" to "50",
            "startIndex" to "1",
            "startPage" to "1",
            "language" to language,
            "inputEncoding" to "UTF-8",
            "outputEncoding" to "UTF-8",
            "related" to ""
        )
        var expanded = template
        values.forEach { (name, value) ->
            expanded = expanded.replace("{$name?}", value).replace("{$name}", value)
        }
        require(!OPEN_SEARCH_TOKEN.containsMatchIn(expanded)) { "Unsupported OpenSearch template variable" }
        val resolved = base.resolve(expanded) ?: error("Invalid OpenSearch search URL")
        return checkedUrl(resolved.toString())
    }

    suspend fun download(catalogId: String, entry: OpdsEntry, acquisition: OpdsAcquisition, onProgress: (Float) -> Unit): Result<File> = withContext(Dispatchers.IO) {
        var temp: File? = null
        var imported: File? = null
        try {
            val catalog = catalog(catalogId)
            val url = checkedUrl(acquisition.href)
            val dir = StorageScanner.getAppBooksDirectory().apply { mkdirs() }
            val tempFile = File.createTempFile(".opds-", ".part", dir)
            temp = tempFile
            fetchToFile(url, credentials(catalog, url), DOWNLOAD_LIMIT, tempFile, onProgress)
            val detected = CatalogDownloader(app).detectFormatExtension(tempFile) ?: error("Unsupported or invalid acquired file")
            val announced = acquisition.href.substringBefore('?').substringAfterLast('/').substringAfterLast('.', "").lowercase()
            val ext = if (detected == "mobi" && announced in listOf("mobi", "azw", "azw3")) announced else detected
            val importedFile = importDownloadedFile(tempFile, entry.title, entry.author, ext)
            imported = importedFile
            check(StorageScanner.scanSingleFile(app, importedFile) != null) { "Downloaded file could not be indexed" }
            tempFile.delete()
            temp = null
            imported = null
            Result.success(importedFile)
        } catch (cancelled: CancellationException) {
            temp?.delete()
            imported?.delete()
            throw cancelled
        } catch (e: Exception) {
            temp?.delete()
            imported?.delete()
            Result.failure(e)
        }
    }

    private fun catalog(id: String) = getCatalogs().firstOrNull { it.id == id } ?: error("Catalog not found")
    private fun credentials(c: OpdsCatalog, url: HttpUrl): Pair<String, String>? {
        if (!sameOrigin(c.feedUrl.toHttpUrl(), url)) return null
        val ciphertext = prefs.getString(secretKey(c.id), null) ?: return null
        return try {
            val data = decrypt(ciphertext)
            val separator = data.indexOf(':')
            require(separator > 0)
            val userLength = data.substring(0, separator).toInt()
            require(userLength in 0..(data.length - separator - 1))
            data.substring(separator + 1, separator + 1 + userLength) to data.substring(separator + 1 + userLength)
        } catch (_: Exception) {
            prefs.edit().remove(secretKey(c.id)).commit()
            null
        }
    }

    private fun fetch(start: HttpUrl, auth: Pair<String, String>?, max: Int): Pair<ByteArray, HttpUrl> {
        var url = start
        repeat(6) { hop ->
            checkedUrl(url.toString())
            val request = Request.Builder().url(url).header("User-Agent", "Axiom/1.0")
            if (auth != null && sameOrigin(start, url)) {
                val token = Base64.encodeToString("${auth.first}:${auth.second}".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                request.header("Authorization", "Basic $token")
            }
            http.newCall(request.build()).execute().use { response ->
                if (response.code in REDIRECTS) {
                    require(hop < 5) { "Too many redirects" }
                    url = checkedUrl(response.header("Location")?.let { url.resolve(it)?.toString() } ?: error("Invalid redirect"))
                    return@repeat
                }
                check(response.isSuccessful) { "HTTP ${response.code}" }
                val body = response.body ?: error("Empty response")
                require(body.contentLength() <= max || body.contentLength() < 0) { "Response too large" }
                val out = ByteArrayOutputStream()
                copyBounded(body.byteStream(), out, max.toLong())
                return out.toByteArray() to url
            }
        }
        error("Redirect handling failed")
    }

    private fun fetchToFile(start: HttpUrl, auth: Pair<String, String>?, max: Int, file: File, progress: (Float) -> Unit): HttpUrl {
        var url = start
        repeat(6) { hop ->
            checkedUrl(url.toString())
            val request = Request.Builder().url(url).header("User-Agent", "Axiom/1.0")
            if (auth != null && sameOrigin(start, url)) {
                request.header("Authorization", "Basic " + Base64.encodeToString("${auth.first}:${auth.second}".toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
            }
            http.newCall(request.build()).execute().use { response ->
                if (response.code in REDIRECTS) {
                    require(hop < 5) { "Too many redirects" }
                    url = checkedUrl(response.header("Location")?.let { url.resolve(it)?.toString() } ?: error("Invalid redirect"))
                    return@repeat
                }
                check(response.isSuccessful) { "HTTP ${response.code}" }
                val body = response.body ?: error("Empty response")
                require(body.contentLength() <= max || body.contentLength() < 0) { "Download too large" }
                file.outputStream().use { output ->
                    copyBounded(body.byteStream(), output, max.toLong()) { copied ->
                        if (body.contentLength() > 0) progress((copied.toFloat() / body.contentLength()).coerceIn(0f, 1f))
                    }
                }
                progress(1f)
                return url
            }
        }
        error("Redirect handling failed")
    }
    internal fun parseFeed(bytes: ByteArray, base: HttpUrl): OpdsFeedPage {
        val p = parser(bytes)
        var event = nextSafeEvent(p)
        while (event == XmlPullParser.COMMENT || event == XmlPullParser.PROCESSING_INSTRUCTION ||
            (event == XmlPullParser.TEXT && p.isWhitespace)
        ) event = nextSafeEvent(p)
        require(event == XmlPullParser.START_TAG && p.name.equals("feed", true) &&
            p.namespace == "http://www.w3.org/2005/Atom"
        ) { "Response is not an Atom feed" }
        var feedTitle = ""
        var entryId = ""; var entryTitle = ""; var author = ""; var summary = ""
        var inEntry = false; var inAuthor = false; var field: String? = null
        val entries = mutableListOf<OpdsEntry>(); val nav = mutableListOf<OpdsNavigationLink>()
        var next: String? = null; var search: String? = null
        var acquisitions = mutableListOf<OpdsAcquisition>(); var navigation: String? = null
        while (true) {
            event = nextSafeEvent(p)
            if (event == XmlPullParser.END_DOCUMENT) break
            when (event) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "entry" -> { inEntry = true; entryId = ""; entryTitle = ""; author = ""; summary = ""; acquisitions = mutableListOf(); navigation = null }
                    "author" -> if (inEntry) inAuthor = true
                    "id" -> if (inEntry) field = "id"
                    "title" -> field = if (inEntry) "title" else "feedTitle"
                    "name" -> if (inEntry && inAuthor) field = "author"
                    "summary", "content" -> if (inEntry) field = "summary"
                    "link" -> {
                        val rel = p.getAttributeValue(null, "rel").orEmpty()
                        val href = p.getAttributeValue(null, "href") ?: ""
                        val title = p.getAttributeValue(null, "title").orEmpty()
                        val type = p.getAttributeValue(null, "type").orEmpty()
                        val resolved = runCatching { checkedUrl(base.resolve(href)?.toString() ?: "").toString() }.getOrNull()
                        if (resolved != null) when {
                            rel == "next" -> next = resolved
                            rel == "search" && type.contains("opensearchdescription+xml", true) -> search = resolved
                            rel == "subsection" || rel == "http://opds-spec.org/subsection" ->
                                if (inEntry) navigation = resolved else if (nav.size < MAX_ENTRIES) nav.add(OpdsNavigationLink(title, resolved, rel))
                            rel.contains("acquisition", true) -> if (inEntry && entries.size < MAX_ENTRIES) acquisitions.add(OpdsAcquisition(title, resolved, type))
                            rel.contains("alternate", true) && type.contains("opds-catalog", true) -> if (inEntry) navigation = resolved
                        }
                    }
                }
                XmlPullParser.TEXT, XmlPullParser.CDSECT -> when (field) {
                    "id" -> entryId += p.text
                    "title" -> entryTitle += p.text
                    "feedTitle" -> feedTitle += p.text
                    "author" -> author += p.text
                    "summary" -> summary += p.text
                }
                XmlPullParser.END_TAG -> when (p.name) {
                    "entry" -> { if (entries.size < MAX_ENTRIES) entries.add(OpdsEntry(entryId, entryTitle, author, summary, navigation, acquisitions.toList())); inEntry = false; field = null }
                    "author" -> inAuthor = false
                    "id", "title", "name", "summary", "content" -> field = null
                }
            }
        }
        return OpdsFeedPage(feedTitle, entries, nav, next, search)
    }

    private fun nextSafeEvent(parser: XmlPullParser): Int {
        val event = parser.next()
        check(event != XmlPullParser.DOCDECL && event != XmlPullParser.ENTITY_REF) {
            "DTD and entity references are not allowed"
        }
        return event
    }

    
    private fun parser(bytes: ByteArray): XmlPullParser = Xml.newPullParser().apply {
        setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
        setInput(ByteArrayInputStream(bytes), null)
    }

    companion object {
        private const val PREFS = "opds_catalog_prefs"
        private const val KEY_CATALOGS = "catalogs"
        private const val FEED_LIMIT = 4 * 1024 * 1024
        private const val DOWNLOAD_LIMIT = 256 * 1024 * 1024
        private const val MAX_ENTRIES = 200
        private val REDIRECTS = setOf(301, 302, 303, 307, 308)
        private val OPEN_SEARCH_TOKEN = Regex("""\{[^{}]+\}""")
        private val PublicDns = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                val addresses = Dns.SYSTEM.lookup(hostname)
                require(addresses.isNotEmpty() && addresses.all(::isPublicAddress)) {
                    "Non-public destination rejected"
                }
                return addresses
            }
        }
        private fun checkedUrl(raw: String): HttpUrl {
            require(raw.length in 1..4096) { "URL too long" }
            val url = raw.toHttpUrl()
            require(url.isHttps && url.username.isEmpty() && url.password.isEmpty() && url.fragment == null) {
                "Only public HTTPS URLs without userinfo/fragments are allowed"
            }
            require(!url.host.equals("localhost", true) && !url.host.endsWith(".localhost", true))
            val host = url.host
            val ipv4 = host.count { it == '.' } == 3 && host.all { it in '0'..'9' || it == '.' }
            if (ipv4 || host.contains(':')) require(isPublicAddress(InetAddress.getByName(host))) {
                "Non-public destination rejected"
            }
            return url
        }

        internal fun isPublicAddress(address: InetAddress): Boolean =
            CatalogUrlSecurity.isPublicAddress(address)

        internal fun sameOrigin(first: HttpUrl, second: HttpUrl): Boolean =
            first.scheme == second.scheme && first.host == second.host && first.port == second.port
        private fun encode(s: String) = Base64.encodeToString(s.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        private fun decode(s: String) = String(Base64.decode(s, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING), Charsets.UTF_8)
        private fun secretKey(id: String) = "secret_$id"
        @Synchronized
        private fun key(): SecretKey {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            }.generateKey()
        }
        private const val KEY_ALIAS = "opds_credentials_aes_gcm"
        private fun encrypt(value: String): String {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            return Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        }
        private fun decrypt(value: String): String {
            val packed = Base64.decode(value, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, packed, 0, 12))
            return String(cipher.doFinal(packed, 12, packed.size - 12), Charsets.UTF_8)
        }
    }
}
internal fun copyBounded(input: java.io.InputStream, output: java.io.OutputStream, limit: Long, onCopied: (Long) -> Unit = {}): Long {
    var total = 0L
    val buffer = ByteArray(8192)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) return total
        total += count
        require(total <= limit) { "Response too large" }
        output.write(buffer, 0, count)
        onCopied(total)
    }
}
