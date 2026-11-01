package com.aitamil.livesubtitles

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class WhisperEngine private constructor(private val handle: Long) : AutoCloseable {
    private external fun transcribeNative(handle: Long, samples: FloatArray, language: String): String
    private external fun releaseNative(handle: Long)
    fun transcribe(samples: FloatArray, language: String): String = synchronized(this) { transcribeNative(handle, samples, language) }
    override fun close() = synchronized(this) { if (handle != 0L) releaseNative(handle) }

    companion object {
        private const val MODEL_FILE = "ggml-tiny-q5_1.bin"
        private const val MODEL_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny-q5_1.bin?download=true"
        init { System.loadLibrary("ai_tamil_whisper") }
        @JvmStatic private external fun createNative(modelPath: String): Long

        suspend fun create(directory: File, progress: (Long, Long) -> Unit): WhisperEngine = withContext(Dispatchers.IO) {
            val model = File(directory, MODEL_FILE)
            if (!model.exists() || model.length() < 25_000_000L) download(model, progress)
            val pointer = createNative(model.absolutePath)
            check(pointer != 0L) { "Whisper could not open its speech model." }
            WhisperEngine(pointer)
        }

        private fun download(model: File, progress: (Long, Long) -> Unit) {
            val partial = File(model.parentFile, "$MODEL_FILE.download")
            partial.delete()
            val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "AI-Tamil-Live-Subtitles/1.0")
            }
            try {
                connection.connect()
                check(connection.responseCode in 200..299) { "Speech model download failed (HTTP ${connection.responseCode})." }
                val total = connection.contentLengthLong
                connection.inputStream.use { input -> FileOutputStream(partial).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var count: Int
                    var copied = 0L
                    while (input.read(buffer).also { count = it } >= 0) {
                        if (count == 0) continue
                        output.write(buffer, 0, count)
                        copied += count
                        progress(copied, total)
                    }
                    output.fd.sync()
                } }
                check(partial.length() >= 25_000_000L) { "Speech model download was incomplete. Check your internet connection and retry." }
                check(partial.renameTo(model)) { "Could not save the speech model." }
            } finally {
                connection.disconnect()
                if (partial.exists()) partial.delete()
            }
        }
    }
}
