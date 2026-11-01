package com.nova.livecaptions.service

import android.app.*
import android.content.*
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.provider.Settings
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.Locale
import kotlin.math.abs
import com.nova.livecaptions.data.SubtitleLine
import com.nova.livecaptions.data.SubtitleRepository
import com.nova.livecaptions.speech.WhisperEngine
import com.nova.livecaptions.speech.SpeechEngine
import com.nova.livecaptions.translation.OnDeviceTranslator
import com.nova.livecaptions.transliteration.TamilTransliterator
import com.nova.livecaptions.audio.PlaybackAudioCapture
import com.nova.livecaptions.overlay.CaptionOverlay
import com.nova.livecaptions.settings.CaptionSettingsStore

class CaptionService : Service() {
    private var captionOverlay: CaptionOverlay? = null
    private var projection: MediaProjection? = null
    private var recorder: AudioRecord? = null
    private var speechEngine: SpeechEngine? = null
    private var captureJob: Job? = null
    private var recognitionJob: Job? = null
    private var stopped = false
    private var paused = false
    private var modelJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(CHANNEL_ID, "NOVA Live Captions", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSession("Stopped"); return START_NOT_STICKY }
            ACTION_PAUSE -> { pauseCapture(); return START_NOT_STICKY }
            ACTION_RESUME -> { resumeCapture(); return START_NOT_STICKY }
            ACTION_REFRESH_SETTINGS -> { captionOverlay?.updateSettings(); return START_STICKY }
            ACTION_START -> Unit
            else -> return START_NOT_STICKY
        }
        startForegroundCompat()
        if (captionOverlay == null && Settings.canDrawOverlays(this)) runCatching { captionOverlay = CaptionOverlay(this).also { it.show() } }
        val data = projectionData(intent)
        val code = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        if (data == null || code != Activity.RESULT_OK) { stopSession("Screen audio permission was not granted"); return START_NOT_STICKY }
        try {
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(code, data)
            projection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopSession("Device audio capture ended") }
            }, main)
        } catch (e: Exception) { stopSession("Could not start device audio capture: ${e.message ?: "permission denied"}"); return START_NOT_STICKY }
        beginSession()
        return START_NOT_STICKY
    }

    private fun startForegroundCompat() {
        val stopIntent = PendingIntent.getService(this, 1, Intent(this, CaptionService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val pauseIntent = PendingIntent.getService(this, 2, Intent(this, CaptionService::class.java).setAction(if (paused) ACTION_RESUME else ACTION_PAUSE), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("NOVA LIVE CAPTIONS")
            .setContentText("Preparing private, on-device subtitles")
            .addAction(Notification.Action.Builder(null, if (paused) "Resume" else "Pause", pauseIntent).build())
            .addAction(Notification.Action.Builder(null, "Stop", stopIntent).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(NOTIFICATION_ID, notification)
    }

    private fun beginSession() {
        if (speechEngine != null || captureJob != null || modelJob?.isActive == true) return
        modelJob = scope.launch {
            publishStatus("Downloading the speech model (about 32 MB)...")
            try {
                var lastPercent = -1
                speechEngine = WhisperEngine.create(filesDir) { current, total ->
                    val percent = if (total > 0) (current * 100 / total).toInt() else -1
                    if (percent != lastPercent) {
                        lastPercent = percent
                        val progress = if (percent >= 0) "$percent%" else "${current / 1_000_000} MB"
                        main.post { publishStatus("Preparing offline speech model: $progress") }
                    }
                }
                if (stopped || paused) return@launch
                startPlaybackCapture()
            } catch (e: Exception) {
                stopSession("Could not prepare speech recognition: ${e.message ?: "download or model error"}")
            }
        }
    }

    private fun startPlaybackCapture() {
        val mediaProjection = projection ?: error("Device audio permission expired. Start again and approve capture.")
        val sampleRate = PlaybackAudioCapture.CAPTURE_RATE
        val record = PlaybackAudioCapture(mediaProjection).createRecorder()
        recorder = record
        record.startRecording()
        publishStatus("Capturing permitted app audio. Phone playback stays unchanged.")
        val chunks = Channel<FloatArray>(capacity = Channel.CONFLATED)
        captureJob = scope.launch(Dispatchers.IO) {
            val shorts = ShortArray(sampleRate / 4)
            val pending = ArrayList<Float>(PlaybackAudioCapture.SPEECH_RATE * WINDOW_SECONDS)
            var resampleSum = 0f
            var resampleCount = 0
            var quietWindows = 0
            while (isActive && !stopped) {
                val count = record.read(shorts, 0, shorts.size, AudioRecord.READ_BLOCKING)
                if (count < 0) { main.post { stopSession("Playback capture stopped by Android (code $count).") }; break }
                if (count == 0) continue
                for (i in 0 until count) {
                    resampleSum += shorts[i] / 32768.0f
                    resampleCount++
                    if (resampleCount == PlaybackAudioCapture.CAPTURE_RATE / PlaybackAudioCapture.SPEECH_RATE) {
                        pending.add(resampleSum / resampleCount)
                        resampleSum = 0f
                        resampleCount = 0
                    }
                }
                if (pending.size >= PlaybackAudioCapture.SPEECH_RATE * WINDOW_SECONDS) {
                    val peak = pending.maxOfOrNull { abs(it) } ?: 0f
                    if (peak < .002f) quietWindows++ else quietWindows = 0
                    if (quietWindows == 3) main.post { publishStatus("No app audio detected. The source app may block playback capture.") }
                    chunks.trySend(pending.toFloatArray())
                    val retained = pending.takeLast(PlaybackAudioCapture.SPEECH_RATE * OVERLAP_SECONDS)
                    pending.clear()
                    pending.addAll(retained)
                }
            }
            chunks.close()
        }
        recognitionJob = scope.launch(Dispatchers.IO) {
            for (samples in chunks) {
                val lang = recognitionLanguage()
                val result = runCatching { speechEngine?.transcribe(samples, lang).orEmpty() }.getOrElse { "" }
                val stable = removeOverlap(result)
                if (stable.isNotBlank()) {
                    val sourceTag = detectLanguage(stable)
                    val tamil = if (sourceTag == "ta") stable else runCatching { OnDeviceTranslator.translate(stable, sourceTag, "ta") }.getOrDefault(stable)
                    val english = if (sourceTag == "en") stable else runCatching { OnDeviceTranslator.translate(stable, sourceTag, "en") }.getOrDefault(stable)
                    val line = SubtitleLine(stable, tamil, english)
                    if (CaptionSettingsStore.read(this@CaptionService).saveCaptions) SubtitleRepository.save(this@CaptionService, line)
                    val selected = render(tamil, english)
                    withContext(Dispatchers.Main) { publishText(selected) }
                }
            }
        }
    }

    private var priorTokens = emptyList<String>()
    private var priorNormalized = ""
    private fun removeOverlap(raw: String): String {
        val tokens = raw.trim().split(Regex("\\s+")).filter(String::isNotBlank)
        if (tokens.isEmpty()) return ""
        val normalized = tokens.joinToString(" ").lowercase(Locale.ROOT)
        if (normalized == priorNormalized) return ""
        var overlap = 0
        for (size in minOf(12, priorTokens.size, tokens.size) downTo 1) {
            val left = priorTokens.takeLast(size).map(::normalizeToken)
            val right = tokens.take(size).map(::normalizeToken)
            if (left == right) { overlap = size; break }
        }
        val fresh = tokens.drop(overlap)
        priorTokens = (priorTokens + fresh).takeLast(24)
        priorNormalized = normalized
        return fresh.joinToString(" ")
    }

    private fun normalizeToken(value: String) = value.trim { !it.isLetterOrDigit() }.lowercase(Locale.ROOT)
    private fun recognitionLanguage(): String = when (CaptionSettingsStore.read(this).sourceLanguage) {
        "ta-IN" -> "ta"; "hi-IN" -> "hi"; "en-US" -> "en"; else -> "auto"
    }
    private fun detectLanguage(text: String): String = when {
        text.any { it in '\u0B80'..'\u0BFF' } -> "ta"
        text.any { it in '\u0900'..'\u097F' } -> "hi"
        else -> "en"
    }
    private fun render(tamil: String, english: String): String {
        val settings = CaptionSettingsStore.read(this)
        val main = when (settings.outputMode) {
            com.nova.livecaptions.data.SubtitleMode.THANGLISH -> TamilTransliterator.toThanglish(tamil)
            com.nova.livecaptions.data.SubtitleMode.ENGLISH -> english
            else -> tamil
        }
        if (!settings.bilingual) return main
        val other = if (settings.outputMode == com.nova.livecaptions.data.SubtitleMode.ENGLISH) tamil else english
        return "$main\n$other"
    }

    @Suppress("DEPRECATION")
    private fun projectionData(intent: Intent): Intent? = if (Build.VERSION.SDK_INT >= 33)
        intent.getParcelableExtra(EXTRA_PROJECTION_DATA, Intent::class.java)
    else intent.getParcelableExtra(EXTRA_PROJECTION_DATA)

    private fun publishStatus(text: String) {
        captionOverlay?.showText(text)
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle("NOVA LIVE CAPTIONS")
            .setContentText(text.take(100)).build())
        sendBroadcast(Intent(ACTION_STATUS).putExtra(EXTRA_TEXT, text).setPackage(packageName))
    }
    private fun publishText(text: String) {
        captionOverlay?.updateSettings()
        captionOverlay?.showText(text)
        sendBroadcast(Intent(ACTION_UPDATE).putExtra(EXTRA_TEXT, text).setPackage(packageName))
    }

    private fun pauseCapture() {
        if (stopped || paused) return
        paused = true
        captureJob?.cancel(); recognitionJob?.cancel(); captureJob = null; recognitionJob = null
        runCatching { recorder?.stop() }; recorder?.release(); recorder = null
        publishStatus("Paused. Video audio is not being captured.")
        updateNotification()
    }

    private fun resumeCapture() {
        if (stopped || !paused) return
        paused = false
        if (speechEngine == null) beginSession() else runCatching { startPlaybackCapture() }.onFailure { stopSession("Could not resume audio capture: ${it.message}") }
        updateNotification()
    }

    private fun updateNotification() {
        val pause = PendingIntent.getService(this, 2, Intent(this, CaptionService::class.java).setAction(if (paused) ACTION_RESUME else ACTION_PAUSE), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, CaptionService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(this, CHANNEL_ID).setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle("NOVA LIVE CAPTIONS")
            .setContentText(if (paused) "Paused" else "Capturing app playback")
            .addAction(Notification.Action.Builder(null, if (paused) "Resume" else "Pause", pause).build())
            .addAction(Notification.Action.Builder(null, "Stop", stop).build()).build()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    private fun stopSession(reason: String) {
        if (stopped) return
        stopped = true
        captureJob?.cancel(); recognitionJob?.cancel()
        runCatching { recorder?.stop() }; recorder?.release(); recorder = null
        runCatching { projection?.stop() }; projection = null
        speechEngine?.close(); speechEngine = null
        publishStatus(reason)
        captionOverlay?.remove(); captionOverlay = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        sendBroadcast(Intent(ACTION_STOPPED).setPackage(packageName))
    }

    override fun onDestroy() {
        stopped = true
        scope.cancel()
        runCatching { recorder?.stop() }; recorder?.release(); recorder = null
        runCatching { projection?.stop() }; projection = null
        speechEngine?.close(); speechEngine = null
        captionOverlay?.remove(); captionOverlay = null
        sendBroadcast(Intent(ACTION_STOPPED).setPackage(packageName))
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.nova.livecaptions.START_CAPTURE"
        const val ACTION_STOP = "com.nova.livecaptions.STOP_CAPTURE"
        const val ACTION_PAUSE = "com.nova.livecaptions.PAUSE_CAPTURE"
        const val ACTION_RESUME = "com.nova.livecaptions.RESUME_CAPTURE"
        const val ACTION_REFRESH_SETTINGS = "com.nova.livecaptions.REFRESH_SETTINGS"
        const val ACTION_UPDATE = "com.nova.livecaptions.UPDATE"
        const val ACTION_STATUS = "com.nova.livecaptions.STATUS"
        const val ACTION_STOPPED = "com.nova.livecaptions.STOPPED"
        const val EXTRA_RESULT_CODE = "projection_result_code"
        const val EXTRA_PROJECTION_DATA = "projection_data"
        const val EXTRA_TEXT = "text"
        private const val CHANNEL_ID = "nova_live_captions"
        private const val NOTIFICATION_ID = 7
        private const val WINDOW_SECONDS = 4
        private const val OVERLAP_SECONDS = 1
    }
}
