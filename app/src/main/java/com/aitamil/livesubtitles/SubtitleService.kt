package com.aitamil.livesubtitles

import android.app.*
import android.content.*
import android.graphics.PixelFormat
import android.os.*
import android.view.*
import android.widget.TextView
import android.speech.*
import kotlinx.coroutines.*

class SubtitleService : Service() {
    private var window: WindowManager? = null
    private var overlay: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private var recognizer: SpeechRecognizer? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    override fun onBind(intent: Intent?) = null
    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel("live_subtitles", "Live subtitles", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        val stop = PendingIntent.getService(this, 1, Intent(this, SubtitleService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(this, "live_subtitles").setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle("AI Tamil Live Subtitles").setContentText("Listening with microphone").addAction(Notification.Action.Builder(null, "Stop", stop).build()).build()
        startForeground(7, notification)
        if (Settings.canDrawOverlays(this)) showOverlay()
        beginRecognition()
    }
    private fun beginRecognition() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { publish("Speech recognition is unavailable on this device"); return }
        recognizer = if (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) SpeechRecognizer.createOnDeviceSpeechRecognizer(this) else SpeechRecognizer.createSpeechRecognizer(this)
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { publish("Listening…") }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) { if (error != SpeechRecognizer.ERROR_CLIENT) Handler(mainLooper).postDelayed({ listenAgain() }, 500) }
            override fun onResults(results: Bundle?) { results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { process(it) }; listenAgain() }
            override fun onPartialResults(results: Bundle?) { results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { publish(render(it)) } }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        listenAgain()
    }
    private fun listenAgain() {
        try {
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, getSharedPreferences("settings", MODE_PRIVATE).getString("source", "en-US"))
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            recognizer?.startListening(i)
        } catch (_: Exception) { }
    }
    private fun process(text: String) {
        val tag = when { text.any { it in '\u0B80'..'\u0BFF' } -> "ta"; text.any { it in '\u0900'..'\u097F' } -> "hi"; else -> "en" }
        scope.launch {
            val tamil = if (tag == "ta") text else runCatching { SubtitleRepository.translate(text, tag, "ta") }.getOrDefault(text)
            val english = if (tag == "en") text else runCatching { SubtitleRepository.translate(text, tag, "en") }.getOrDefault(text)
            val line = SubtitleLine(text, tamil, english)
            SubtitleRepository.save(this@SubtitleService, line)
            publish(render(text, tamil, english))
        }
    }
    private fun render(original: String, tamil: String? = null, english: String? = null): String {
        val mode = getSharedPreferences("settings", MODE_PRIVATE).getString("mode", "TAMIL")
        val ta = tamil ?: original
        return when (mode) { "THANGLISH" -> SubtitleRepository.thanglish(ta); "ENGLISH" -> english ?: original; else -> ta }
    }
    private fun publish(text: String) { overlay?.post { overlay?.text = text }; sendBroadcast(Intent("com.aitamil.livesubtitles.UPDATE").putExtra("text", text).setPackage(packageName)) }
    private fun showOverlay() {
        window = getSystemService(WINDOW_SERVICE) as WindowManager
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val text = TextView(this).apply { setTextColor(prefs.getInt("color", 0xFFFFFFFF.toInt())); textSize = prefs.getFloat("font", 20f); setPadding(22, 14, 22, 14); setBackgroundColor((prefs.getFloat("opacity", .8f) * 255).toInt() shl 24 or 0x00111827); text = "Listening…" }
        val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        params = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, type, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; x = prefs.getInt("overlay_x", 24); y = prefs.getInt("overlay_y", 140) }
        var sx = 0f; var sy = 0f; var wx = 0; var wy = 0
        text.setOnTouchListener { _, e -> when (e.action) { MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; wx = params!!.x; wy = params!!.y; true }; MotionEvent.ACTION_MOVE -> { params!!.x = wx + (e.rawX - sx).toInt(); params!!.y = wy + (e.rawY - sy).toInt(); prefs.edit().putInt("overlay_x", params!!.x).putInt("overlay_y", params!!.y).apply(); window?.updateViewLayout(text, params); true }; else -> false } }
        overlay = text
        try { window?.addView(text, params) } catch (_: Exception) { }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") { stopSelf(); return START_NOT_STICKY }
        return START_STICKY
    }
    override fun onDestroy() { recognizer?.destroy(); recognizer = null; scope.cancel(); try { overlay?.let { window?.removeView(it) } } catch (_: Exception) {}; overlay = null; sendBroadcast(Intent("com.aitamil.livesubtitles.STOPPED").setPackage(packageName)); super.onDestroy() }
}
