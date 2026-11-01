package com.aitamil.livesubtitles

import android.app.*
import android.content.*
import android.graphics.PixelFormat
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.view.*
import android.widget.TextView
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.Locale

class SubtitleService : Service() {
    private var window: WindowManager? = null
    private var overlay: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private var projection: MediaProjection? = null
    private var recorder: AudioRecord? = null
    private var whisper: WhisperEngine? = null
    private var captureJob: Job? = null
    private var recognitionJob: Job? = null
    private var stopped = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(CHANNEL_ID, "Live subtitles", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSession("Stopped"); return START_NOT_STICKY }
        if (intent?.action != ACTION_START) return START_NOT_STICKY
        startForegroundCompat()
        if (overlay == null && Settings.canDrawOverlays(this)) showOverlay()
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
        val stopIntent = PendingIntent.getService(this, 1, Intent(this, SubtitleService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("AI Tamil Live Subtitles")
            .setContentText("Preparing private, on-device subtitles")
            .addAction(Notification.Action.Builder(null, "Stop", stopIntent).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(NOTIFICATION_ID, notification)
    }

    private fun beginSession() {
        if (whisper != null || captureJob != null) return
        scope.launch {
            publishStatus("Downloading the speech model (about 32 MB)...")
            try {
                var lastPercent = -1
                whisper = WhisperEngine.create(filesDir) { current, total ->
                    val percent = if (total > 0) (current * 100 / total).toInt() else -1
                    if (percent != lastPercent) {
                        lastPercent = percent
                        val progress = if (percent >= 0) "$percent%" else "${current / 1_000_000} MB"
                        main.post { publishStatus("Preparing offline speech model: $progress") }
                    }
                }
                if (stopped) return@launch
                startPlaybackCapture()
            } catch (e: Exception) {
                stopSession("Could not prepare speech recognition: ${e.message ?: "download or model error"}")
            }
        }
    }

    private fun startPlaybackCapture() {
        val mediaProjection = projection ?: error("Device audio permission expired. Start again and approve capture.")
        val sampleRate = CAPTURE_RATE
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val minimum = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "This device does not support 16 kHz playback capture." }
        val record = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(minimum, sampleRate * 2))
            .setAudioPlaybackCaptureConfig(config)
            .build()
        check(record.state == AudioRecord.STATE_INITIALIZED) { "Android could not open the permitted playback stream." }
        recorder = record
        record.startRecording()
        publishStatus("Capturing permitted app audio. Phone playback stays unchanged.")
        val chunks = Channel<FloatArray>(capacity = Channel.CONFLATED)
        captureJob = scope.launch(Dispatchers.IO) {
            val shorts = ShortArray(sampleRate / 4)
            val pending = ArrayList<Float>(MODEL_RATE * WINDOW_SECONDS)
            var resampleSum = 0f
            var resampleCount = 0
            while (isActive && !stopped) {
                val count = record.read(shorts, 0, shorts.size, AudioRecord.READ_BLOCKING)
                if (count < 0) { main.post { stopSession("Playback capture stopped by Android (code $count).") }; break }
                if (count == 0) continue
                for (i in 0 until count) {
                    resampleSum += shorts[i] / 32768.0f
                    resampleCount++
                    if (resampleCount == CAPTURE_RATE / MODEL_RATE) {
                        pending.add(resampleSum / resampleCount)
                        resampleSum = 0f
                        resampleCount = 0
                    }
                }
                if (pending.size >= MODEL_RATE * WINDOW_SECONDS) {
                    chunks.trySend(pending.toFloatArray())
                    val retained = pending.takeLast(MODEL_RATE * OVERLAP_SECONDS)
                    pending.clear()
                    pending.addAll(retained)
                }
            }
            chunks.close()
        }
        recognitionJob = scope.launch(Dispatchers.IO) {
            for (samples in chunks) {
                val lang = recognitionLanguage()
                val result = runCatching { whisper?.transcribe(samples, lang).orEmpty() }.getOrElse { "" }
                val stable = removeOverlap(result)
                if (stable.isNotBlank()) {
                    val sourceTag = detectLanguage(stable)
                    val tamil = if (sourceTag == "ta") stable else runCatching { SubtitleRepository.translate(stable, sourceTag, "ta") }.getOrDefault(stable)
                    val english = if (sourceTag == "en") stable else runCatching { SubtitleRepository.translate(stable, sourceTag, "en") }.getOrDefault(stable)
                    val line = SubtitleLine(stable, tamil, english)
                    SubtitleRepository.save(this@SubtitleService, line)
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
    private fun recognitionLanguage(): String = when (getSharedPreferences(PREFS, MODE_PRIVATE).getString("source", "auto")) {
        "ta-IN" -> "ta"; "hi-IN" -> "hi"; "en-US" -> "en"; else -> "auto"
    }
    private fun detectLanguage(text: String): String = when {
        text.any { it in '\u0B80'..'\u0BFF' } -> "ta"
        text.any { it in '\u0900'..'\u097F' } -> "hi"
        else -> "en"
    }
    private fun render(tamil: String, english: String): String = when (getSharedPreferences(PREFS, MODE_PRIVATE).getString("mode", "TAMIL")) {
        "THANGLISH" -> SubtitleRepository.thanglish(tamil)
        "ENGLISH" -> english
        else -> tamil
    }

    @Suppress("DEPRECATION")
    private fun projectionData(intent: Intent): Intent? = if (Build.VERSION.SDK_INT >= 33)
        intent.getParcelableExtra(EXTRA_PROJECTION_DATA, Intent::class.java)
    else intent.getParcelableExtra(EXTRA_PROJECTION_DATA)

    private fun publishStatus(text: String) {
        overlay?.post { overlay?.text = text }
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle("AI Tamil Live Subtitles")
            .setContentText(text.take(100)).addAction(Notification.Action.Builder(null, "Stop", PendingIntent.getService(this, 1, Intent(this, SubtitleService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build()).build())
        sendBroadcast(Intent(ACTION_STATUS).putExtra(EXTRA_TEXT, text).setPackage(packageName))
    }
    private fun publishText(text: String) {
        overlay?.post { overlay?.text = text }
        sendBroadcast(Intent(ACTION_UPDATE).putExtra(EXTRA_TEXT, text).setPackage(packageName))
    }

    private fun showOverlay() {
        window = getSystemService(WINDOW_SERVICE) as WindowManager
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val text = TextView(this).apply {
            setTextColor(prefs.getInt("color", 0xFFFFFFFF.toInt()))
            textSize = prefs.getFloat("font", 20f)
            setPadding(22, 14, 22, 14)
            setBackgroundColor(((prefs.getFloat("opacity", .8f) * 255).toInt() shl 24) or 0x00111827)
            this.text = "Preparing speech model..."
        }
        params = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; x = prefs.getInt("overlay_x", 24); y = prefs.getInt("overlay_y", 140) }
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        text.setOnTouchListener { _, event -> when (event.action) {
            MotionEvent.ACTION_DOWN -> { downX = event.rawX; downY = event.rawY; startX = params!!.x; startY = params!!.y; true }
            MotionEvent.ACTION_MOVE -> { params!!.x = startX + (event.rawX - downX).toInt(); params!!.y = startY + (event.rawY - downY).toInt(); prefs.edit().putInt("overlay_x", params!!.x).putInt("overlay_y", params!!.y).apply(); window?.updateViewLayout(text, params); true }
            else -> false
        } }
        overlay = text
        runCatching { window?.addView(text, params) }
    }

    private fun stopSession(reason: String) {
        if (stopped) return
        stopped = true
        captureJob?.cancel(); recognitionJob?.cancel()
        runCatching { recorder?.stop() }; recorder?.release(); recorder = null
        runCatching { projection?.stop() }; projection = null
        whisper?.close(); whisper = null
        publishStatus(reason)
        runCatching { overlay?.let { window?.removeView(it) } }; overlay = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        sendBroadcast(Intent(ACTION_STOPPED).setPackage(packageName))
    }

    override fun onDestroy() {
        stopped = true
        scope.cancel()
        runCatching { recorder?.stop() }; recorder?.release(); recorder = null
        runCatching { projection?.stop() }; projection = null
        whisper?.close(); whisper = null
        runCatching { overlay?.let { window?.removeView(it) } }; overlay = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.aitamil.livesubtitles.START_CAPTURE"
        const val ACTION_STOP = "com.aitamil.livesubtitles.STOP_CAPTURE"
        const val ACTION_UPDATE = "com.aitamil.livesubtitles.UPDATE"
        const val ACTION_STATUS = "com.aitamil.livesubtitles.STATUS"
        const val ACTION_STOPPED = "com.aitamil.livesubtitles.STOPPED"
        const val EXTRA_RESULT_CODE = "projection_result_code"
        const val EXTRA_PROJECTION_DATA = "projection_data"
        const val EXTRA_TEXT = "text"
        private const val CHANNEL_ID = "live_subtitles"
        private const val NOTIFICATION_ID = 7
        private const val CAPTURE_RATE = 48_000
        private const val MODEL_RATE = 16_000
        private const val WINDOW_SECONDS = 4
        private const val OVERLAP_SECONDS = 1
        private const val PREFS = "settings"
    }
}
