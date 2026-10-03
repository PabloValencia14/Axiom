package org.readera.openreadera.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.util.Log
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import java.util.*
import java.util.concurrent.TimeUnit

class NaturalTtsPlayer(
    private val context: Context,
    private val onSentenceChanged: (sentenceIndex: Int, sentenceText: String) -> Unit,
    private val onStateChanged: (isPlaying: Boolean) -> Unit,
    private val onError: (message: String) -> Unit,
    initialSpeechRate: Float = 1.0f,
    initialOnlineTtsEnabled: Boolean = false
) : TextToSpeech.OnInitListener {
    @Volatile private var onlineTtsEnabled = initialOnlineTtsEnabled
    @Volatile private var activeCall: okhttp3.Call? = null

    companion object {
        private const val TAG = "NaturalTtsPlayer"
        private const val TTS_URL = "https://translate.google.com/translate_tts"
    }

    private var mediaPlayer: MediaPlayer? = null
    private var systemTts: TextToSpeech? = null
    private var isSystemTtsReady = false

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val playerScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var playJob: Job? = null

    private var sentences = listOf<String>()
    private var currentIndex = 0
    private var isPaused = false
    private var usingSystemTts = false
    private var pendingSystemTtsText: String? = null
    private var isActive = false
    val isActiveForPlayback: Boolean get() = isActive

    private var speechRate = initialSpeechRate.let { if (it.isFinite()) it.coerceIn(0.5f, 2.0f) else 1.0f }

    fun setSpeechRate(rate: Float) {
        speechRate = if (rate.isFinite()) rate.coerceIn(0.5f, 2.0f) else 1.0f
        systemTts?.setSpeechRate(speechRate)
        mediaPlayer?.let { player ->
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                runCatching { player.playbackParams = player.playbackParams.setSpeed(speechRate) }
            }
        }
        if (usingSystemTts && !isPaused) playSystemTts(sentences.getOrNull(currentIndex).orEmpty())
    }

    fun setOnlineTtsEnabled(enabled: Boolean) {
        if (onlineTtsEnabled == enabled) return
        onlineTtsEnabled = enabled
        if (!enabled) {
            activeCall?.cancel()
            activeCall = null
            playJob?.cancel()
            mediaPlayer?.let { player ->
                runCatching { if (player.isPlaying) player.stop() }
                player.release()
            }
            mediaPlayer = null
            systemTts?.stop()
            if (isActive && !isPaused) sentences.getOrNull(currentIndex)?.let(::playSystemTts)
        }
    }

    init {
        try {
            systemTts = TextToSpeech(context.applicationContext, this)
        } catch (e: Exception) {
            Log.w(TAG, "Could not initialize system TTS", e)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            systemTts?.language = Locale("es", "ES")
            systemTts?.setSpeechRate(speechRate)
            isSystemTtsReady = true
            pendingSystemTtsText?.let { text ->
                pendingSystemTtsText = null
                playSystemTts(text)
            }
        }
    }

    fun start(fullText: String, startSentenceIndex: Int = 0) {
        stop()
        val split = splitIntoSentences(fullText)
        if (split.isEmpty()) {
            onError("No hay texto para reproducir.")
            return
        }

        sentences = split
        currentIndex = startSentenceIndex.coerceIn(0, sentences.size - 1)
        isPaused = false
        isActive = true

        playCurrentSentence()
    }

    private fun splitIntoSentences(text: String): List<String> {
        return text
            .split(Regex("""(?<=[.!?])\s+|\n+"""))
            .map { it.trim() }
            .filter { it.length > 1 }
    }

    private fun playCurrentSentence() {
        if (currentIndex >= sentences.size) {
            stop()
            return
        }

        val sentence = sentences[currentIndex]
        onSentenceChanged(currentIndex, sentence)
        onStateChanged(true)

        playJob?.cancel()
        if (!onlineTtsEnabled) {
            playSystemTts(sentence)
            return
        }
        playJob = playerScope.launch {
            val audioFile = downloadSentenceAudio(sentence, currentIndex)
            if (audioFile != null && audioFile.exists() && audioFile.length() > 0) {
                playAudioFile(audioFile)
            } else {
                playSystemTts(sentence)
            }
        }
    }

    private suspend fun downloadSentenceAudio(text: String, index: Int): File? = withContext(Dispatchers.IO) {
        if (!onlineTtsEnabled) return@withContext null
        try {
            val cleanText = text.take(200)
            val encoded = URLEncoder.encode(cleanText, "UTF-8")
            val url = "$TTS_URL?ie=UTF-8&tl=es&client=tw-ob&q=$encoded"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()
            if (!onlineTtsEnabled) return@withContext null
            val call = client.newCall(request)
            activeCall = call
            if (!onlineTtsEnabled) {
                call.cancel()
                return@withContext null
            }
            call.execute().use { response ->
                if (!onlineTtsEnabled || !response.isSuccessful) return@withContext null
                val targetFile = File(context.cacheDir, "natural_tts_${index % 4}.mp3")
                response.body?.byteStream()?.use { input ->
                    FileOutputStream(targetFile).use { output -> input.copyTo(output) }
                }
                if (onlineTtsEnabled) targetFile else null
            }
        } catch (e: Exception) {
            if (onlineTtsEnabled) Log.w(TAG, "Failed to download neural audio chunk, falling back to system TTS: ${e.message}")
            null
        } finally {
            activeCall = null
        }
    }

    private fun playAudioFile(file: File) {
        try {
            mediaPlayer?.release()
            usingSystemTts = false
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                setDataSource(file.absolutePath)
                prepare()
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    playbackParams = playbackParams.setSpeed(speechRate)
                }
                setOnCompletionListener {
                    if (!isPaused) {
                        currentIndex++
                        playCurrentSentence()
                    }
                }
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaPlayer error: $what, $extra")
                    playSystemTts(sentences.getOrNull(currentIndex) ?: "")
                    true
                }
                start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error playing audio file", e)
            playSystemTts(sentences.getOrNull(currentIndex) ?: "")
        }
    }

    private fun playSystemTts(text: String) {
        if (text.isBlank()) return
        if (!isSystemTtsReady) {
            pendingSystemTtsText = text
            usingSystemTts = true
            return
        }

        usingSystemTts = true
        systemTts?.setSpeechRate(speechRate)
        systemTts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "readera_chunk_$currentIndex")
        // Estimate completion at 3.5 words/second, scaled by the selected rate.
        val wordCount = text.split(Regex("""\s+""")).size
        val durationMs = ((wordCount / (3.5f * speechRate)) * 1000).toLong().coerceIn(1200L, 15000L)

        playJob?.cancel()
        playJob = playerScope.launch {
            delay(durationMs)
            if (!isPaused) {
                currentIndex++
                playCurrentSentence()
            }
        }
    }

    fun pause() {
        isPaused = true
        pendingSystemTtsText = null
        mediaPlayer?.let {
            if (it.isPlaying) it.pause()
        }
        systemTts?.stop()
        onStateChanged(false)
    }

    fun resume() {
        isPaused = false
        mediaPlayer?.let {
            it.start()
            onStateChanged(true)
            return
        }
        playCurrentSentence()
    }

    fun nextSentence() {
        if (currentIndex < sentences.size - 1) {
            mediaPlayer?.stop()
            systemTts?.stop()
            currentIndex++
            playCurrentSentence()
        }
    }

    fun previousSentence() {
        if (currentIndex > 0) {
            mediaPlayer?.stop()
            systemTts?.stop()
            currentIndex--
            playCurrentSentence()
        }
    }

    fun stop() {
        isPaused = false
        isActive = false
        pendingSystemTtsText = null
        usingSystemTts = false
        playJob?.cancel()
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (_: Exception) {}
        mediaPlayer = null

        try {
            systemTts?.stop()
        } catch (_: Exception) {}

        onStateChanged(false)
    }

    fun release() {
        stop()
        playerScope.cancel()
        systemTts?.shutdown()
        systemTts = null
    }
}
