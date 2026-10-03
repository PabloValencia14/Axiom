package org.readera.openreadera.zlib

import android.content.Context
import android.content.SharedPreferences
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import org.readera.openreadera.OpenReadEraApplication
import org.readera.openreadera.catalog.CatalogSource
import org.readera.openreadera.data.scanner.StorageScanner
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class ZLibraryClient(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("zlibrary_prefs", Context.MODE_PRIVATE)

    companion object {
        const val DEFAULT_MIRROR = "https://singlelogin.rs"
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Xiaomi Pad 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
    }

    init {
        // Sanitize legacy fake credentials from scam/broken mirrors (e.g. z-lib.id)
        val savedKey = prefs.getString("remix_userkey", null)
        val savedId = prefs.getString("remix_userid", null)
        val savedMirror = prefs.getString("custom_mirror", null)
        if (savedKey?.startsWith("web_session_") == true ||
            savedId?.startsWith("zuser_") == true ||
            savedMirror?.contains("z-lib.id") == true
        ) {
            prefs.edit()
                .remove("remix_userkey")
                .remove("remix_userid")
                .remove("saved_cookies")
                .putString("custom_mirror", DEFAULT_MIRROR)
                .apply()
        }
        if (savedMirror != null && normalizeMirror(savedMirror) == null) {
            prefs.edit().putString("custom_mirror", DEFAULT_MIRROR).apply()
        }
    }

    private fun normalizeMirror(value: String): String? {
        val candidate = value.trim().let {
            if (it.startsWith("https://", ignoreCase = true)) it
            else if (it.contains("://")) return null
            else "https://$it"
        }
        val url = candidate.toHttpUrlOrNull() ?: return null
        val authority = candidate.substringAfter("://").substringBefore('/')
        if (url.scheme != "https" || url.host.isBlank() || '@' in authority ||
            url.username.isNotEmpty() || url.password.isNotEmpty() ||
            url.encodedQuery != null || url.encodedFragment != null ||
            url.encodedPath !in listOf("", "/")
        ) return null
        if (url.host.contains("z-lib.id")) return null
        return url.newBuilder().encodedPath("/").build().toString().trimEnd('/')
    }

    private val client = OkHttpClient.Builder()
        .dns(org.readera.openreadera.catalog.BypassDns.instance)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    var mirror: String
        get() = normalizeMirror(prefs.getString("custom_mirror", null) ?: DEFAULT_MIRROR) ?: DEFAULT_MIRROR
        set(value) {
            prefs.edit().putString("custom_mirror", normalizeMirror(value) ?: DEFAULT_MIRROR).apply()
        }

    val isLoggedIn: Boolean
        get() {
            val key = getRemixUserKey()
            return !key.isNullOrBlank() && !key.startsWith("web_session_")
        }

    fun getRemixUserId(): String? = prefs.getString("remix_userid", null)?.takeIf { !it.startsWith("zuser_") }
    fun getRemixUserKey(): String? = prefs.getString("remix_userkey", null)?.takeIf { !it.startsWith("web_session_") }
    fun getUserEmail(): String? = prefs.getString("user_email", null)
    fun getUserName(): String? = prefs.getString("user_name", null)
    fun getDownloadsToday(): Int = prefs.getInt("downloads_today", 0)
    fun getDownloadsLimit(): Int = prefs.getInt("downloads_limit", 10)

    fun getSavedCookies(): String? = prefs.getString("saved_cookies", null)

    fun logout() {
        prefs.edit().clear().putString("custom_mirror", DEFAULT_MIRROR).apply()
    }

    /**
     * Authenticates against the official Z-Library eAPI (/eapi/user/login)
     */
    suspend fun login(email: String, pass: String): Result<ZLibProfile> = withContext(Dispatchers.IO) {
        try {
            val formBody = FormBody.Builder()
                .add("email", email)
                .add("password", pass)
                .add("site_mode", "books")
                .build()

            val request = Request.Builder()
                .url("$mirror/eapi/user/login")
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .post(formBody)
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""

            if (body.isBlank()) {
                return@withContext Result.failure(Exception("Respuesta vacía del servidor Z-Library"))
            }

            val json = try {
                JSONObject(body)
            } catch (e: Exception) {
                return@withContext Result.failure(Exception("Respuesta no válida del servidor Z-Library: $body"))
            }

            val success = json.optInt("success", 0) == 1 || json.optBoolean("success", false)
            if (success) {
                val user = json.optJSONObject("user") ?: JSONObject()
                val id = if (user.has("id")) user.get("id").toString() else (user.optString("remix_userid", ""))
                val remixKey = user.optString("remix_userkey", "")
                val remixId = if (user.has("remix_userid")) user.get("remix_userid").toString() else id
                val name = user.optString("name", email.substringBefore('@'))
                val dlToday = user.optInt("downloads_today", 0)
                val dlLimit = user.optInt("downloads_limit", 10)

                val cookieJar = mutableMapOf<String, String>()
                response.headers("Set-Cookie").forEach { cookieHeader ->
                    val cookie = cookieHeader.substringBefore(';')
                    val k = cookie.substringBefore('=')
                    val v = cookie.substringAfter('=')
                    if (k.isNotBlank()) cookieJar[k] = v
                }
                if (remixKey.isNotBlank()) cookieJar["remix_userkey"] = remixKey
                if (remixId.isNotBlank()) cookieJar["remix_userid"] = remixId

                val cookieStr = cookieJar.entries.joinToString("; ") { "${it.key}=${it.value}" }
                prefs.edit().putString("saved_cookies", cookieStr).apply()

                val profile = ZLibProfile(
                    id = remixId.ifBlank { id },
                    email = user.optString("email", email),
                    name = name,
                    remixUserkey = remixKey,
                    downloadsToday = dlToday,
                    downloadsLimit = dlLimit
                )
                saveProfile(profile)
                Result.success(profile)
            } else {
                val errorMsg = json.optString("error", "Credenciales incorrectas en Z-Library")
                Result.failure(Exception(errorMsg))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Authenticates with user-supplied remix cookies/tokens, validating against /eapi/user/profile
     */
    suspend fun loginWithToken(userId: String, userKey: String): Result<ZLibProfile> = withContext(Dispatchers.IO) {
        val cleanKey = userKey.trim()
        val cleanId = userId.trim()

        if (cleanKey.isBlank()) {
            return@withContext Result.failure(Exception("La clave remix_userkey no puede estar vacía"))
        }

        try {
            val reqBuilder = Request.Builder()
                .url("$mirror/eapi/user/profile")
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .header("remix-userid", cleanId)
                .header("remix-userkey", cleanKey)
                .header("Cookie", "remix_userid=$cleanId; remix_userkey=$cleanKey")

            val resp = client.newCall(reqBuilder.build()).execute()
            val body = resp.body?.string() ?: ""

            if (body.isNotBlank() && body.startsWith("{")) {
                val json = JSONObject(body)
                val success = json.optInt("success", 0) == 1 || json.optBoolean("success", false)
                if (success) {
                    val user = json.optJSONObject("user") ?: JSONObject()
                    val id = if (user.has("id")) user.get("id").toString() else cleanId
                    val name = user.optString("name", "Usuario Z-Lib")
                    val email = user.optString("email", "usuario_${cleanId.take(8)}@zlib")
                    val dlToday = user.optInt("downloads_today", 0)
                    val dlLimit = user.optInt("downloads_limit", 10)

                    val cookieStr = "remix_userid=$cleanId; remix_userkey=$cleanKey"
                    prefs.edit().putString("saved_cookies", cookieStr).apply()

                    val profile = ZLibProfile(
                        id = cleanId.ifBlank { id },
                        email = email,
                        name = name,
                        remixUserkey = cleanKey,
                        downloadsToday = dlToday,
                        downloadsLimit = dlLimit
                    )
                    saveProfile(profile)
                    return@withContext Result.success(profile)
                } else {
                    val error = json.optString("error", "Token o credenciales no válidos")
                    return@withContext Result.failure(Exception("Token rechazado por Z-Library: $error"))
                }
            }
        } catch (_: Exception) {
            // If offline, still allow saving token locally
        }

        val cookieStr = "remix_userid=$cleanId; remix_userkey=$cleanKey"
        prefs.edit().putString("saved_cookies", cookieStr).apply()
        val profile = ZLibProfile(
            id = cleanId.ifBlank { "token_user" },
            email = "usuario_${cleanId.take(8)}@zlib",
            name = "Usuario Z-Lib (${cleanId.take(8)})",
            remixUserkey = cleanKey,
            downloadsToday = 0,
            downloadsLimit = 10
        )
        saveProfile(profile)
        Result.success(profile)
    }

    private fun saveProfile(profile: ZLibProfile) {
        prefs.edit()
            .putString("remix_userid", profile.id)
            .putString("remix_userkey", profile.remixUserkey)
            .putString("user_email", profile.email)
            .putString("user_name", profile.name)
            .putInt("downloads_today", profile.downloadsToday)
            .putInt("downloads_limit", profile.downloadsLimit)
            .apply()
    }

    /**
     * Searches books via the official Z-Library eAPI (/eapi/book/search or /eapi/book/most-popular)
     */
    suspend fun searchBooks(
        query: String,
        extension: String? = null,
        language: String? = null
    ): List<ZLibBook> = withContext(Dispatchers.IO) {
        val results = mutableListOf<ZLibBook>()
        val cleanQuery = query.trim()

        try {
            val formBuilder = FormBody.Builder()
            val url = if (cleanQuery.isBlank()) {
                "$mirror/eapi/book/most-popular"
            } else {
                formBuilder.add("message", cleanQuery)
                if (!extension.isNullOrBlank() && !extension.equals("Todos", true)) {
                    formBuilder.add("extensions[0]", extension.lowercase())
                }
                val cleanLang = when {
                    language.isNullOrBlank() || language.equals("Todos", true) || language.contains("Todos los idiomas", true) -> null
                    language.contains("Español", true) || language.equals("Spanish", true) || language.equals("es", true) -> "spanish"
                    language.contains("Inglés", true) || language.equals("English", true) || language.equals("en", true) -> "english"
                    else -> language.lowercase().trim()
                }
                if (!cleanLang.isNullOrBlank()) {
                    formBuilder.add("languages[0]", cleanLang)
                }
                formBuilder.add("limit", "50")
                "$mirror/eapi/book/search"
            }

            val reqBuilder = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")

            val userId = getRemixUserId()
            val userKey = getRemixUserKey()
            if (!userId.isNullOrBlank()) reqBuilder.header("remix-userid", userId)
            if (!userKey.isNullOrBlank()) reqBuilder.header("remix-userkey", userKey)
            if (!userId.isNullOrBlank() || !userKey.isNullOrBlank()) {
                val c = "remix_userid=${userId ?: ""}; remix_userkey=${userKey ?: ""}"
                reqBuilder.header("Cookie", c)
            }

            if (cleanQuery.isNotBlank()) {
                reqBuilder.post(formBuilder.build())
            }

            val resp = client.newCall(reqBuilder.build()).execute()
            val respBody = resp.body?.string() ?: ""

            if (resp.isSuccessful && respBody.startsWith("{")) {
                val json = JSONObject(respBody)
                if (json.optInt("success", 0) == 1 || json.optBoolean("success", false)) {
                    val booksArray = json.optJSONArray("books")
                    if (booksArray != null) {
                        for (i in 0 until booksArray.length()) {
                            val b = booksArray.getJSONObject(i)
                            val id = b.get("id").toString()
                            val hash = b.optString("hash", "")
                            val title = b.optString("title", "Sin título").trim()
                            val author = b.optString("author", "Autor desconocido").trim()
                            val year = if (b.has("year") && !b.isNull("year")) b.get("year").toString() else ""
                            val lang = b.optString("language", "")
                            val ext = b.optString("extension", "EPUB").uppercase()
                            val filesize = b.optString("filesizeString", "")
                            val cover = b.optString("cover", "").takeIf { it.isNotBlank() }
                            val publisher = b.optString("publisher", "").takeIf { it.isNotBlank() }
                            val desc = b.optString("description", "").takeIf { it.isNotBlank() }
                            val dlUrl = "$mirror/eapi/book/$id/$hash/file"

                            results.add(
                                ZLibBook(
                                    id = id,
                                    hash = hash,
                                    title = title,
                                    author = author.ifBlank { "Autor desconocido" },
                                    publisher = publisher,
                                    year = year,
                                    language = lang,
                                    extension = ext,
                                    filesize = filesize,
                                    coverUrl = cover,
                                    downloadUrl = dlUrl,
                                    description = desc,
                                    source = CatalogSource.Z_LIBRARY
                                )
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // If eAPI returned results, return them
        if (results.isNotEmpty()) {
            return@withContext results
        }

        // Fallback: Try HTML scraper for custom mirrors
        try {
            results.addAll(scrapeHtmlSearch(cleanQuery, extension, language))
        } catch (_: Exception) {}

        // Fallback: Open catalog classics if empty
        if (results.isEmpty() && cleanQuery.isNotBlank()) {
            results.addAll(getFallbackBooks(cleanQuery))
        }

        results
    }

    private fun scrapeHtmlSearch(
        query: String,
        extension: String?,
        language: String?
    ): List<ZLibBook> {
        val list = mutableListOf<ZLibBook>()
        try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val urlBuilder = StringBuilder("$mirror/s/?q=$encodedQuery")
            if (!extension.isNullOrBlank() && !extension.equals("Todos", true)) {
                urlBuilder.append("&extensions%5B%5D=").append(extension.lowercase())
            }
            val cleanLang = when {
                language.isNullOrBlank() || language.equals("Todos", true) || language.contains("Todos los idiomas", true) -> null
                language.contains("Español", true) || language.equals("Spanish", true) || language.equals("es", true) -> "spanish"
                language.contains("Inglés", true) || language.equals("English", true) || language.equals("en", true) -> "english"
                else -> language.lowercase().trim()
            }
            if (!cleanLang.isNullOrBlank()) {
                urlBuilder.append("&languages%5B%5D=").append(cleanLang)
            }

            val requestBuilder = Request.Builder()
                .url(urlBuilder.toString())
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")

            getRemixUserId()?.let { requestBuilder.header("remix-userid", it) }
            getRemixUserKey()?.let { requestBuilder.header("remix-userkey", it) }

            val response = client.newCall(requestBuilder.build()).execute()
            val html = response.body?.string() ?: ""

            val items = html.split(Regex("<div[^>]*class=\"[^\"]*resItemBox"))
            for (i in 1 until items.size) {
                val itemContent = items[i]
                val idMatcher = Pattern.compile("data-book_id=\"([^\"]+)\"").matcher(itemContent)
                val bookId = if (idMatcher.find()) idMatcher.group(1) ?: "" else ""
                if (bookId.isBlank()) continue

                val titleMatcher = Pattern.compile("<h3[^>]*itemprop=\"name\"[^>]*>\\s*<a[^>]*>([^<]+)</a>", Pattern.DOTALL).matcher(itemContent)
                val title = if (titleMatcher.find()) {
                    titleMatcher.group(1)?.trim() ?: "Sin título"
                } else {
                    val altTitle = Pattern.compile("<a[^>]*href=\"/book/[^\"]+\"[^>]*>([^<]+)</a>").matcher(itemContent)
                    if (altTitle.find()) altTitle.group(1)?.trim() ?: "Sin título" else "Sin título"
                }

                val authorMatcher = Pattern.compile("<div[^>]*class=\"authors\"[^>]*>\\s*<a[^>]*>([^<]+)</a>", Pattern.DOTALL).matcher(itemContent)
                val author = if (authorMatcher.find()) authorMatcher.group(1)?.trim() ?: "Desconocido" else "Desconocido"

                val publisherMatcher = Pattern.compile("itemprop=\"publisher\"[^>]*>([^<]+)</a>", Pattern.DOTALL).matcher(itemContent)
                val publisher = if (publisherMatcher.find()) publisherMatcher.group(1)?.trim() else null

                val coverMatcher = Pattern.compile("data-src=\"([^\"]+)\"", Pattern.DOTALL).matcher(itemContent)
                val cover = if (coverMatcher.find()) coverMatcher.group(1) else null

                val yearMatcher = Pattern.compile("property_year\"[^>]*>.*?<div class=\"property_value[^\"]*\">([^<]+)</div>", Pattern.DOTALL).matcher(itemContent)
                val year = if (yearMatcher.find()) yearMatcher.group(1)?.trim() ?: "" else ""

                val langMatcher = Pattern.compile("property_language\"[^>]*>.*?<div class=\"property_value[^\"]*\">([^<]+)</div>", Pattern.DOTALL).matcher(itemContent)
                val lang = if (langMatcher.find()) langMatcher.group(1)?.trim() ?: "" else ""

                val fileMatcher = Pattern.compile("property__file\"[^>]*>.*?<div class=\"property_value[^\"]*\">([^<]+)</div>", Pattern.DOTALL).matcher(itemContent)
                val fileType = if (fileMatcher.find()) fileMatcher.group(1)?.trim() ?: "EPUB" else "EPUB"

                list.add(
                    ZLibBook(
                        id = bookId,
                        title = title,
                        author = author,
                        publisher = publisher,
                        year = year,
                        language = lang,
                        extension = fileType.uppercase(),
                        coverUrl = cover,
                        downloadUrl = "$mirror/book/$bookId",
                        source = CatalogSource.Z_LIBRARY
                    )
                )
            }
        } catch (_: Exception) {}
        return list
    }

    /**
     * Downloads a book directly using the official eAPI endpoint (/eapi/book/{id}/{hash}/file)
     */
    suspend fun downloadBook(
        book: ZLibBook,
        onProgress: (Float) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val downloadDir = StorageScanner.getAppBooksDirectory()

            val safeTitle = book.title.replace(Regex("[^a-zA-Z0-9áéíóúÁÉÍÓÚñÑ\\s-]"), "").take(40).trim()
            val safeAuthor = book.author.replace(Regex("[^a-zA-Z0-9áéíóúÁÉÍÓÚñÑ\\s-]"), "").take(30).trim()
            val ext = book.extension.lowercase().ifBlank { "epub" }
            val targetFile = File(downloadDir, "$safeTitle - $safeAuthor.$ext")

            // Determine download URL
            var directUrl = book.downloadUrl
            var hash = book.hash

            if (directUrl.isNullOrBlank() || (!directUrl.startsWith("http") && !directUrl.contains("/eapi/book/"))) {
                if (hash.isNotBlank()) {
                    directUrl = "$mirror/eapi/book/${book.id}/$hash/file"
                } else {
                    // Try to resolve hash via search
                    val found = searchBooks(book.title).find { it.id == book.id }
                    if (found != null && found.hash.isNotBlank()) {
                        hash = found.hash
                        directUrl = "$mirror/eapi/book/${book.id}/$hash/file"
                    } else {
                        directUrl = "$mirror/book/${book.id}"
                    }
                }
            }

            val userId = getRemixUserId()
            val userKey = getRemixUserKey()

            // If it's a web page URL instead of eAPI (e.g. from an HTML mirror)
            if (directUrl.contains("/book/") && !directUrl.contains("/eapi/book/")) {
                try {
                    val reqBuilder = Request.Builder()
                        .url(directUrl)
                        .header("User-Agent", USER_AGENT)
                    if (!userId.isNullOrBlank()) reqBuilder.header("remix-userid", userId)
                    if (!userKey.isNullOrBlank()) reqBuilder.header("remix-userkey", userKey)
                    val c = "remix_userid=${userId ?: ""}; remix_userkey=${userKey ?: ""}"
                    reqBuilder.header("Cookie", c)

                    val resp = client.newCall(reqBuilder.build()).execute()
                    val html = resp.body?.string() ?: ""

                    val dlBtn = Pattern.compile("class=\"[^\"]*dlButton[^\"]*\"[^>]*href=\"([^\"]+)\"").matcher(html)
                    if (dlBtn.find()) {
                        val href = dlBtn.group(1) ?: ""
                        if (href.contains("/login", ignoreCase = true) || href == "/login") {
                            return@withContext Result.failure(
                                Exception("Z-Library requiere iniciar sesión en 'Cuenta' para descargar '${book.title}'.")
                            )
                        }
                        directUrl = if (href.startsWith("http")) href else "$mirror$href"
                    }
                } catch (e: Exception) {
                    return@withContext Result.failure(
                        Exception("No se pudo resolver el enlace de descarga: ${e.localizedMessage}")
                    )
                }
            }

            // Build request to download file
            val reqBuilder = Request.Builder()
                .url(directUrl)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "*/*")

            if (!userId.isNullOrBlank()) reqBuilder.header("remix-userid", userId)
            if (!userKey.isNullOrBlank()) reqBuilder.header("remix-userkey", userKey)
            val cookieVal = buildString {
                getSavedCookies()?.let { append("$it; ") }
                if (!userId.isNullOrBlank()) append("remix_userid=$userId; ")
                if (!userKey.isNullOrBlank()) append("remix_userkey=$userKey; ")
            }.trim().trimEnd(';')
            if (cookieVal.isNotEmpty()) {
                reqBuilder.header("Cookie", cookieVal)
            }

            val response = client.newCall(reqBuilder.build()).execute()
            val contentType = response.body?.contentType()?.toString() ?: ""

            // Check for error responses
            if (!response.isSuccessful || contentType.contains("application/json") || contentType.contains("text/html")) {
                val errorBody = response.body?.string() ?: ""
                val errorJson = try { JSONObject(errorBody) } catch (_: Exception) { null }
                val serverMsg = errorJson?.optString("error", "") ?: ""

                if (serverMsg.contains("login", ignoreCase = true) || serverMsg.contains("credentials", ignoreCase = true) || response.code in listOf(401, 403)) {
                    return@withContext Result.failure(
                        Exception("Z-Library requiere autenticación: Tu cuenta o token no son válidos o han expirado. Por favor, abre el icono de Cuenta e inicia sesión con tus credenciales de singlelogin.rs.")
                    )
                }
                if (serverMsg.contains("limit", ignoreCase = true)) {
                    return@withContext Result.failure(
                        Exception("Has alcanzado tu límite diario de descargas en Z-Library. Vuelve a intentarlo mañana o descarga copias públicas desde Ebookelo / Anna's Archive.")
                    )
                }
                if (serverMsg.isNotBlank()) {
                    return@withContext Result.failure(
                        Exception("Error de Z-Library: $serverMsg")
                    )
                }
                return@withContext Result.failure(
                    Exception("Error del servidor Z-Library (HTTP ${response.code}) al descargar '${book.title}'.")
                )
            }

            val body = response.body ?: return@withContext Result.failure(Exception("Respuesta sin contenido"))
            val contentLength = body.contentLength()
            val inputStream = body.byteStream()
            val outputStream = FileOutputStream(targetFile)

            val buffer = ByteArray(8192)
            var bytesRead: Int
            var totalBytesRead = 0L

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
                totalBytesRead += bytesRead
                if (contentLength > 0) {
                    val progress = (totalBytesRead.toFloat() / contentLength.toFloat()).coerceIn(0f, 1f)
                    onProgress(progress)
                }
            }

            outputStream.flush()
            outputStream.close()
            inputStream.close()

            onProgress(1f)

            // Index file in Room database via StorageScanner
            val scannedBook = StorageScanner.scanSingleFile(context, targetFile)

            // Save cover if available
            if (scannedBook != null && !book.coverUrl.isNullOrBlank()) {
                try {
                    val coversDir = File(context.filesDir, "covers").apply { mkdirs() }
                    val coverFileName = if (!scannedBook.sha1.isNullOrBlank()) "cover_${scannedBook.sha1}.jpg" else "cover_${scannedBook.id}.jpg"
                    val coverFile = File(coversDir, coverFileName)

                    val coverReq = Request.Builder()
                        .url(book.coverUrl)
                        .header("User-Agent", USER_AGENT)
                        .build()

                    val coverResp = client.newCall(coverReq).execute()
                    if (coverResp.isSuccessful && coverResp.body != null) {
                        coverResp.body!!.byteStream().use { input ->
                            FileOutputStream(coverFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                        if (coverFile.exists() && coverFile.length() > 0) {
                            val app = context.applicationContext as? OpenReadEraApplication
                            val updated = scannedBook.copy(
                                coverPath = coverFile.absolutePath,
                                title = if (scannedBook.title == "Desconocido" || scannedBook.title.isBlank()) book.title else scannedBook.title,
                                author = if (scannedBook.author == "Desconocido" || scannedBook.author.isBlank()) book.author else scannedBook.author
                            )
                            app?.repository?.updateBook(updated)
                        }
                    }
                } catch (_: Exception) {}
            }

            try {
                val syncManager = org.readera.openreadera.sync.GoogleDriveSyncManager(context)
                if (syncManager.isDriveConnected() && syncManager.isAutoSyncEnabled) {
                    syncManager.triggerImmediateBackgroundSync()
                }
            } catch (_: Exception) {}

            Result.success(targetFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun getFallbackBooks(query: String = ""): List<ZLibBook> {
        val classics = listOf(
            ZLibBook(
                id = "zlib_quijote",
                title = "Don Quijote de la Mancha",
                author = "Miguel de Cervantes Saavedra",
                publisher = "Real Academia Española",
                year = "1605",
                language = "Spanish",
                extension = "EPUB",
                filesize = "1.8 MB",
                rating = "5.0",
                coverUrl = "https://covers.openlibrary.org/b/id/8231856-L.jpg",
                downloadUrl = "https://www.gutenberg.org/ebooks/2000.epub.images",
                description = "El ingenioso hidalgo don Quijote de la Mancha narra las aventuras de un hidalgo pobre que de tanto leer novelas de caballería acaba enloqueciendo."
            ),
            ZLibBook(
                id = "zlib_metamorfosis",
                title = "La metamorfosis",
                author = "Franz Kafka",
                publisher = "Kurt Wolff Verlag",
                year = "1915",
                language = "Spanish",
                extension = "EPUB",
                filesize = "640 KB",
                rating = "4.8",
                coverUrl = "https://covers.openlibrary.org/b/id/8282346-L.jpg",
                downloadUrl = "https://www.gutenberg.org/ebooks/5700.epub.images",
                description = "Al despertar Gregorio Samsa una mañana, tras un sueño intranquilo, se encontró sobre su cama convertido en un monstruoso insecto."
            ),
            ZLibBook(
                id = "zlib_orgullo",
                title = "Orgullo y prejuicio",
                author = "Jane Austen",
                publisher = "T. Egerton",
                year = "1813",
                language = "Spanish",
                extension = "EPUB",
                filesize = "1.2 MB",
                rating = "4.9",
                coverUrl = "https://covers.openlibrary.org/b/id/8225261-L.jpg",
                downloadUrl = "https://www.gutenberg.org/ebooks/1342.epub.images",
                description = "Es una verdad mundialmente reconocida que un hombre soltero, poseedor de una gran fortuna, necesita una esposa."
            ),
            ZLibBook(
                id = "zlib_becquer",
                title = "Rimas y Leyendas",
                author = "Gustavo Adolfo Bécquer",
                publisher = "Biblioteca Clásica",
                year = "1871",
                language = "Spanish",
                extension = "EPUB",
                filesize = "1.1 MB",
                rating = "4.9",
                coverUrl = "https://covers.openlibrary.org/b/id/8235114-L.jpg",
                downloadUrl = "https://www.gutenberg.org/ebooks/30359.epub.images",
                description = "Obra cumbre del romanticismo español que reúne las delicadas rimas y leyendas fantásticas de Gustavo Adolfo Bécquer."
            ),
            ZLibBook(
                id = "zlib_divina",
                title = "La Divina Comedia",
                author = "Dante Alighieri",
                publisher = "Foligno",
                year = "1320",
                language = "Spanish",
                extension = "EPUB",
                filesize = "1.5 MB",
                rating = "4.9",
                coverUrl = "https://covers.openlibrary.org/b/id/8231991-L.jpg",
                downloadUrl = "https://www.gutenberg.org/ebooks/19840.epub.images",
                description = "A mitad del camino de la vida, en una selva oscura me encontraba porque mi ruta había extraviado."
            )
        )

        return if (query.isBlank()) {
            classics
        } else {
            classics.filter {
                it.title.contains(query, ignoreCase = true) ||
                it.author.contains(query, ignoreCase = true)
            }
        }
    }
}
