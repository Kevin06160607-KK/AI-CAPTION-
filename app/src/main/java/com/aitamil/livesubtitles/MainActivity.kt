package com.aitamil.livesubtitles

import android.Manifest
import android.app.Activity
import android.content.*
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.SpeechRecognizer
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import java.text.SimpleDateFormat
import java.util.*

private val Bg = Color(0xFF090D16); private val Card = Color(0xFF151C2B); private val Accent = Color(0xFF8B7CFF)

class MainActivity : ComponentActivity() {
    private var running by mutableStateOf(false)
    private var latest by mutableStateOf("Your live subtitles will appear here")
    private var waitingForOverlay by mutableStateOf(false)
    private val requestMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) startServiceFlow() }
    private val receiver = object : BroadcastReceiver() { override fun onReceive(context: Context?, intent: Intent?) { if (intent?.action == "com.aitamil.livesubtitles.STOPPED") running = false else latest = intent?.getStringExtra("text") ?: latest } }
    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); val filter = IntentFilter("com.aitamil.livesubtitles.UPDATE").apply { addAction("com.aitamil.livesubtitles.STOPPED") }; if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED) else registerReceiver(receiver, filter); setContent { App() } }
    override fun onResume() { super.onResume(); if (waitingForOverlay && Settings.canDrawOverlays(this)) { waitingForOverlay = false; launchService() } }
    override fun onDestroy() { runCatching { unregisterReceiver(receiver) }; super.onDestroy() }
    private fun startServiceFlow() {
        if (!Settings.canDrawOverlays(this)) { waitingForOverlay = true; startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))); return }
        launchService()
    }
    private fun launchService() {
        val i = Intent(this, SubtitleService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        running = true
    }
    private fun start() { requestMic.launch(Manifest.permission.RECORD_AUDIO) }
    private fun stop() { stopService(Intent(this, SubtitleService::class.java)); running = false }
    @Composable private fun App() {
        var page by remember { mutableStateOf("Home") }
        MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Bg, surface = Card)) {
            Column(Modifier.fillMaxSize().background(Bg).padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 18.dp)) {
                    Box(Modifier.size(44.dp).background(Accent, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) { Icon(Icons.Default.GraphicEq, null, tint = Color.White) }
                    Spacer(Modifier.width(12.dp)); Column { Text("AI Tamil", fontWeight = FontWeight.Bold, fontSize = 21.sp); Text("LIVE SUBTITLES", color = Color.Gray, fontSize = 10.sp, letterSpacing = 2.sp) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("Home", "History", "Settings").forEach { name -> FilterChip(selected = page == name, onClick = { page = name }, label = { Text(name) }) } }
                Spacer(Modifier.height(12.dp))
                when (page) { "Home" -> Home(); "History" -> History(); else -> SettingsPage() }
            }
        }
    }
    @Composable private fun Home() {
        Text("Live subtitles", fontSize = 27.sp, fontWeight = FontWeight.Bold)
        Text("Recognize speech and translate it on your device.", color = Color.LightGray, modifier = Modifier.padding(top = 5.dp))
        Spacer(Modifier.height(18.dp))
        CardBox {
            Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(10.dp).background(if (running) Color(0xFF48D597) else Color.Gray, RoundedCornerShape(20.dp))); Spacer(Modifier.width(9.dp)); Text(if (running) "Microphone listening" else "Ready to start", fontWeight = FontWeight.SemiBold) }
            Text(latest, modifier = Modifier.padding(top = 14.dp), color = Color.White, fontSize = 18.sp)
        }
        Spacer(Modifier.height(14.dp))
        Text("SUBTITLE LANGUAGE", color = Color.Gray, fontSize = 11.sp, letterSpacing = 1.4.sp)
        var mode by remember { mutableStateOf(getSharedPreferences("settings", MODE_PRIVATE).getString("mode", "TAMIL") ?: "TAMIL") }
        Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) { SubtitleMode.entries.forEach { m -> FilterChip(selected = mode == m.name, onClick = { mode = m.name; getSharedPreferences("settings", MODE_PRIVATE).edit().putString("mode", mode).apply() }, label = { Text(m.title) }) } }
        Spacer(Modifier.height(18.dp))
        Button(onClick = { if (running) stop() else start() }, modifier = Modifier.fillMaxWidth().height(58.dp), shape = RoundedCornerShape(18.dp)) { Icon(if (running) Icons.Default.Stop else Icons.Default.PlayArrow, null); Spacer(Modifier.width(8.dp)); Text(if (running) "Stop subtitles" else "Start live subtitles", fontSize = 16.sp) }
        Spacer(Modifier.height(14.dp))
        CardBox { Text("Works across apps", fontWeight = FontWeight.SemiBold); Text("Keep the foreground notification active while you watch. Recognition uses your microphone. Some Android apps block playback capture; Android speech recognition does not accept captured app audio directly.", color = Color.LightGray, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
    }
    @Composable private fun CardBox(content: @Composable ColumnScope.() -> Unit) { Column(Modifier.fillMaxWidth().background(Card, RoundedCornerShape(20.dp)).padding(18.dp), content = content) }
    @Composable private fun History() {
        Text("Subtitle history", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        val lines = remember { mutableStateListOf<SubtitleLine>().also { it.addAll(SubtitleRepository.history(this)) } }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { TextButton(onClick = { SubtitleRepository.clear(this@MainActivity); lines.clear() }) { Text("Clear history") } }
        if (lines.isEmpty()) Text("Recognized subtitles are saved on this device.", color = Color.LightGray)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) { items(lines) { line -> CardBox { Text(line.tamil, fontSize = 18.sp); Text(line.english, color = Color.LightGray, modifier = Modifier.padding(top = 4.dp)); Text(SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(line.time)), color = Color.Gray, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp)) } } }
    }
    @Composable private fun SettingsPage() {
        Text("Appearance", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        var size by remember { mutableFloatStateOf(prefs.getFloat("font", 20f)) }
        var opacity by remember { mutableFloatStateOf(prefs.getFloat("opacity", .8f)) }
        var source by remember { mutableStateOf(prefs.getString("source", "en-US") ?: "en-US") }
        CardBox {
            Text("Recognition language", fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.padding(top = 6.dp)) {
                listOf("en-US" to "English", "ta-IN" to "\u0BA4\u0BAE\u0BBF\u0BB4\u0BCD", "hi-IN" to "Hindi").forEach { (tag, title) ->
                    FilterChip(selected = source == tag, onClick = { source = tag; prefs.edit().putString("source", tag).apply() }, label = { Text(title) })
                }
            }
            Text("Applies to the next listening session.", color = Color.Gray, fontSize = 12.sp)
            Spacer(Modifier.height(12.dp))
            Text("Subtitle font size: ${size.toInt()} sp"); Slider(value = size, onValueChange = { size = it; prefs.edit().putFloat("font", it).apply() }, valueRange = 14f..36f)
            Spacer(Modifier.height(8.dp)); Text("Background opacity: ${(opacity * 100).toInt()}%"); Slider(value = opacity, onValueChange = { opacity = it; prefs.edit().putFloat("opacity", it).apply() }, valueRange = .2f..1f)
            Spacer(Modifier.height(8.dp)); Text("Text colour"); Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) { listOf("White" to -1, "Yellow" to 0xFFFFFF00.toInt(), "Cyan" to 0xFF80DEEA.toInt()).forEach { (name, color) -> TextButton(onClick = { prefs.edit().putInt("color", color).apply() }) { Text("[$name]", color = Color(color)) } } }
        }
        Spacer(Modifier.height(14.dp)); CardBox { Text("Privacy", fontWeight = FontWeight.SemiBold); Text("Speech recognition and ML Kit translation run on device when supported. No API key is required. The Android recognizer may use the device speech service depending on manufacturer settings.", color = Color.LightGray, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
    }
}
