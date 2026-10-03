package org.readera.openreadera.sync

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import org.readera.openreadera.data.model.AppThemeMode
import org.readera.openreadera.data.model.ReaderColorTheme
import org.readera.openreadera.data.model.ReaderViewMode
import org.readera.openreadera.data.model.StrokeToolType
import org.json.JSONArray
import org.readera.openreadera.OpenReadEraApplication
import org.readera.openreadera.catalog.OpdsCatalog
import org.readera.openreadera.catalog.OpdsCatalogRepository
import org.readera.openreadera.data.db.AppDatabase
import org.readera.openreadera.data.model.Book
import org.readera.openreadera.data.model.BookSyncIdentity
import org.readera.openreadera.data.scanner.DocumentCategory
import org.readera.openreadera.data.scanner.DocumentCategoryClassifier
import org.readera.openreadera.zlib.ZLibraryClient
import java.util.Locale

internal object DriveSyncSafeState {
    private const val READER_PREFS = "openreadera_prefs"
    private const val STATS_PREFS = "openreadera_stats"
    private const val SYNC_PREFS = "drive_sync_data"

    private val stringSettings = setOf(
        "app_theme_mode", "drawing_tool", "drawing_color", "theme", "view_mode", "font_family"
    )
    private val booleanSettings = setOf(
        "dynamic_color", "use_neural_tts_online", "keep_screen_on", "invert_images", "volume_keys",
        "bionic_reading", "font_bold"
    )
    private val integerSettings = setOf("font_size", "margin_horizontal")
    private val floatSettings = setOf("tts_rate", "drawing_stroke_width", "line_spacing", "brightness")
    private val statisticKeys = setOf(
        "total_seconds", "total_pages_read", "completed_books", "current_streak", "best_streak", "last_reading_date"
    )
    private val syncSettingKeys = setOf("auto_sync_enabled")
    private val statDateKey = Regex("day_sec_\\d{4}-\\d{2}-\\d{2}")
    private val color = Regex("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?")

    fun readerPreferences(context: Context): JSONObject = readerPreferencesFromValues(
        context.getSharedPreferences(READER_PREFS, Context.MODE_PRIVATE).all
    )

    internal fun readerPreferencesFromValues(values: Map<String, *>): JSONObject = capture(
        values,
        stringSettings + booleanSettings + integerSettings + floatSettings
    )

    fun syncPreferences(context: Context): JSONObject = syncPreferencesFromValues(
        context.getSharedPreferences(SYNC_PREFS, Context.MODE_PRIVATE).all
    )

    internal fun syncPreferencesFromValues(values: Map<String, *>): JSONObject = capture(
        values,
        syncSettingKeys
    )

    fun readingStats(context: Context): JSONObject {
        val prefs = context.getSharedPreferences(STATS_PREFS, Context.MODE_PRIVATE)
        return readingStatsFromValues(prefs.all)
    }

    internal fun readingStatsFromValues(values: Map<String, *>): JSONObject {
        val safe = (statisticKeys + values.keys.filter(statDateKey::matches)).toSet()
        return capture(values, safe)
    }

    fun applyReaderPreferences(context: Context, remote: JSONObject) {
        val editor = context.getSharedPreferences(READER_PREFS, Context.MODE_PRIVATE).edit()
        for (key in stringSettings) {
            val value = remote.optString(key, "").takeIf(String::isNotBlank) ?: continue
            val valid = when (key) {
                "app_theme_mode" -> value in AppThemeMode.entries.map { it.name }
                "drawing_tool" -> value in StrokeToolType.entries.map { it.name } || value == "ERASER"
                "drawing_color" -> color.matches(value)
                "theme" -> value in ReaderColorTheme.entries.map { it.name }
                "view_mode" -> value in ReaderViewMode.entries.map { it.name }
                "font_family" -> value in setOf("SansSerif", "Serif", "Monospace")
                else -> false
            }
            if (valid) editor.putString(key, value)
        }
        for (key in booleanSettings) {
            if (remote.has(key) && remote.opt(key) is Boolean) editor.putBoolean(key, remote.getBoolean(key))
        }
        for (key in integerSettings) {
            val number = remote.opt(key) as? Number ?: continue
            val range = if (key == "font_size") 10..36 else 0..48
            editor.putInt(key, number.toInt().coerceIn(range))
        }
        for (key in floatSettings) {
            val number = (remote.opt(key) as? Number)?.toFloat() ?: continue
            if (!number.isFinite()) continue
            val bounded = when (key) {
                "tts_rate" -> number.coerceIn(0.5f, 2f)
                "drawing_stroke_width" -> number.coerceIn(1f, 32f)
                "line_spacing" -> number.coerceIn(0.8f, 2.5f)
                else -> number.coerceIn(-1f, 1f)
            }
            editor.putFloat(key, bounded)
        }
        check(editor.commit()) { "Could not restore reader preferences" }
        runCatching {
            (context.applicationContext as? OpenReadEraApplication)?.preferences?.refreshAfterSync()
        }
    }

    fun mergeAndApplyReadingStats(context: Context, remote: JSONObject?): JSONObject {
        val local = readingStats(context)
        val merged = JSONObject()
        val remoteValues = remote ?: JSONObject()
        for (key in statisticKeys - "last_reading_date") {
            val localValue = local.opt(key) as? Number
            val remoteValue = remoteValues.opt(key) as? Number
            val value = listOfNotNull(localValue, remoteValue).maxOfOrNull { it.toLong() } ?: continue
            merged.put(key, value)
        }
        val localDate = local.optString("last_reading_date")
        val remoteDate = remoteValues.optString("last_reading_date")
        val latestDate = maxOf(localDate, remoteDate)
        if (latestDate.isNotBlank()) merged.put("last_reading_date", latestDate)
        val streakSource = when {
            remoteDate > localDate -> remoteValues
            localDate > remoteDate -> local
            else -> null
        }
        if (streakSource != null && streakSource.has("current_streak")) {
            merged.put("current_streak", streakSource.optInt("current_streak"))
        } else if (local.has("current_streak") || remoteValues.has("current_streak")) {
            merged.put("current_streak", maxOf(local.optInt("current_streak"), remoteValues.optInt("current_streak")))
        }
        val dayKeys = (local.keys().asSequence().toList() + remoteValues.keys().asSequence().toList())
            .filter(statDateKey::matches).toSet()
        dayKeys.forEach { key -> merged.put(key, maxOf(local.optLong(key), remoteValues.optLong(key))) }
        applyStats(context, merged)
        return merged
    }

    fun mergeAndApplySyncPreferences(context: Context, remote: JSONObject?) {
        if (remote == null) return
        val editor = context.getSharedPreferences(SYNC_PREFS, Context.MODE_PRIVATE).edit()
        syncSettingKeys.forEach { key ->
            if (remote.has(key) && remote.opt(key) is Boolean) editor.putBoolean(key, remote.getBoolean(key))
        }
        check(editor.commit()) { "Could not restore sync preferences" }
    }

    private fun applyStats(context: Context, values: JSONObject) {
        val editor = context.getSharedPreferences(STATS_PREFS, Context.MODE_PRIVATE).edit()
        val integerKeys = setOf("total_pages_read", "completed_books", "current_streak", "best_streak")
        values.keys().forEach { key ->
            if (key == "last_reading_date") {
                val value = values.optString(key)
                if (value.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) editor.putString(key, value)
            } else if (key in statisticKeys || statDateKey.matches(key)) {
                val number = values.opt(key) as? Number ?: return@forEach
                if (key in integerKeys) editor.putInt(key, number.toInt().coerceAtLeast(0))
                else editor.putLong(key, number.toLong().coerceAtLeast(0L))
            }
        }
        check(editor.commit()) { "Could not restore reading statistics" }
    }

    fun opdsCatalogs(context: Context): JSONArray = encodeCatalogs(OpdsCatalogRepository(context).getCatalogs())

    fun mergeAndApplyOpdsCatalogs(context: Context, remote: JSONArray?): JSONArray {
        val portable = mutableListOf<OpdsCatalog>()
        if (remote != null) {
            for (index in 0 until remote.length()) {
                val value = remote.optJSONObject(index) ?: continue
                val id = value.optString("id").trim()
                val title = value.optString("title").trim()
                val feedUrl = value.optString("feedUrl").trim()
                if (id.isNotEmpty() && title.isNotEmpty() && feedUrl.isNotEmpty()) {
                    portable += OpdsCatalog(id, title, feedUrl)
                }
            }
        }
        val repository = OpdsCatalogRepository(context)
        repository.mergePortableCatalogs(portable)
        return encodeCatalogs(repository.getCatalogs())
    }

    fun zLibraryPreferences(context: Context): JSONObject =
        JSONObject().put("customMirror", ZLibraryClient(context).mirror)

    fun mergeAndApplyZLibraryPreferences(context: Context, remote: JSONObject?): JSONObject {
        val client = ZLibraryClient(context)
        val mirror = remote?.optString("customMirror").orEmpty()
        if (mirror.isNotBlank()) client.mirror = mirror
        return zLibraryPreferences(context)
    }

    fun manualCategoryOverrides(context: Context, books: List<Book>): JSONArray =
        JSONArray().apply {
            books.mapNotNull { book ->
                DocumentCategoryClassifier.manualCategory(context, book)?.let { category ->
                    BookSyncIdentity.of(book) to category
                }
            }.sortedBy { it.first }.forEach { (syncId, category) ->
                put(JSONObject().put("bookSyncId", syncId).put("category", category.name))
            }
        }

    suspend fun mergeAndApplyManualCategoryOverrides(
        context: Context,
        database: AppDatabase,
        remote: JSONArray?
    ): JSONArray {
        val books = database.bookDao().getAllBooksIncludingTrashList()
        val booksByIdentity = buildMap {
            books.forEach { book ->
                put(BookSyncIdentity.of(book), book)
                put(BookSyncIdentity.legacy(book), book)
            }
        }
        val overrides = linkedMapOf<String, DocumentCategory>()
        manualCategoryOverrides(context, books).let { local ->
            for (index in 0 until local.length()) {
                val value = local.optJSONObject(index) ?: continue
                val category = runCatching {
                    DocumentCategory.valueOf(value.optString("category"))
                }.getOrNull() ?: continue
                overrides[value.optString("bookSyncId")] = category
            }
        }
        if (remote != null) {
            for (index in 0 until remote.length()) {
                val value = remote.optJSONObject(index) ?: continue
                val category = runCatching {
                    DocumentCategory.valueOf(value.optString("category"))
                }.getOrNull() ?: continue
                val identity = value.optString("bookSyncId").takeIf(String::isNotBlank) ?: continue
                overrides[identity] = category
            }
        }
        for ((identity, category) in overrides) {
            booksByIdentity[identity]?.let { book ->
                DocumentCategoryClassifier.setManualCategory(context, book, category)
            }
        }
        return manualCategoryOverrides(context, books)
    }

    private fun encodeCatalogs(catalogs: List<OpdsCatalog>): JSONArray =
        JSONArray().apply {
            catalogs.sortedBy { it.id }.forEach { catalog ->
                put(JSONObject().apply {
                    put("id", catalog.id)
                    put("title", catalog.title)
                    put("feedUrl", catalog.feedUrl)
                })
            }
        }

    private fun capture(prefs: SharedPreferences, allowed: Set<String>): JSONObject = capture(prefs.all, allowed)

    private fun capture(values: Map<String, *>, allowed: Set<String>): JSONObject = JSONObject().apply {
        allowed.sorted().forEach { key ->
            when (val value = values[key]) {
                is String -> put(key, value)
                is Boolean, is Int, is Long, is Float, is Double -> put(key, value)
            }
        }
    }
}
