package com.nova.livecaptions.ui

import android.Manifest
import android.app.Activity
import android.content.*
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.nova.livecaptions.data.SubtitleLine
import com.nova.livecaptions.data.SubtitleMode
import com.nova.livecaptions.data.SubtitleRepository
import com.nova.livecaptions.service.CaptionService
import com.nova.livecaptions.settings.CaptionSettingsStore
import com.nova.livecaptions.settings.CaptionTextSize
import java.text.SimpleDateFormat
import java.util.*

private val Ink = Color(0xFF05080D)
private val Panel = Color(0xFF101722)
private val Blue = Color(0xFF168CFF)
private val Soft = Color(0xFF9AABC0)

class MainActivity : ComponentActivity() {
    private var running by mutableStateOf(false)
    private var paused by mutableStateOf(false)
    private var latest by mutableStateOf("Your live caption preview will appear here")
    private var waitingForOverlay by mutableStateOf(false)
    private var settingsRevision by mutableIntStateOf(0)

    private val requestAudioPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startServiceFlow() else latest = "Android audio-capture permission is required. No microphone input is captured."
    }
    private val requestProjection = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) launchService(result.resultCode, data)
        else latest = "Device audio capture was not approved. Nothing is being captured."
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val text = intent?.getStringExtra(CaptionService.EXTRA_TEXT)
            when (intent?.action) {
                CaptionService.ACTION_STOPPED -> { running = false; paused = false }
                CaptionService.ACTION_STATUS -> {
                    latest = text ?: latest
                    paused = latest.startsWith("Paused.")
                    if (latest.startsWith("Could not") || latest.startsWith("Device audio capture ended") || latest.startsWith("Playback capture stopped")) { running = false; paused = false }
                }
                else -> latest = text ?: latest
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val filter = IntentFilter(CaptionService.ACTION_UPDATE).apply {
            addAction(CaptionService.ACTION_STATUS)
            addAction(CaptionService.ACTION_STOPPED)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED) else registerReceiver(receiver, filter)
        setContent { NovaApp() }
    }

    override fun onResume() {
        super.onResume()
        if (waitingForOverlay) {
            waitingForOverlay = false
            if (Settings.canDrawOverlays(this)) startServiceFlow() else latest = "Allow Display over other apps to show the floating captions."
        }
    }

    override fun onDestroy() { runCatching { unregisterReceiver(receiver) }; super.onDestroy() }

    private fun begin() {
        if (Build.VERSION.SDK_INT < 29) { latest = "Internal app-audio captions need Android 10 or newer."; return }
        requestAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun startServiceFlow() {
        if (!Settings.canDrawOverlays(this)) {
            waitingForOverlay = true
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        requestProjection.launch(manager.createScreenCaptureIntent())
    }

    private fun launchService(resultCode: Int, data: Intent) {
        val serviceIntent = Intent(this, CaptionService::class.java).setAction(CaptionService.ACTION_START)
            .putExtra(CaptionService.EXTRA_RESULT_CODE, resultCode)
            .putExtra(CaptionService.EXTRA_PROJECTION_DATA, data)
        startForegroundService(serviceIntent)
        running = true
        paused = false
        latest = "Preparing private on-device captions..."
    }

    private fun sendServiceAction(action: String) { startService(Intent(this, CaptionService::class.java).setAction(action)) }
    private fun stopCapture() { stopService(Intent(this, CaptionService::class.java)); running = false; paused = false }

    @Composable private fun NovaApp() {
        var page by remember { mutableStateOf("Home") }
        MaterialTheme(colorScheme = darkColorScheme(primary = Blue, background = Ink, surface = Panel)) {
            Column(Modifier.fillMaxSize().background(Ink).padding(horizontal = 20.dp, vertical = 18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 18.dp)) {
                    Box(Modifier.size(46.dp).background(Blue, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.GraphicEq, null, tint = Color.White, modifier = Modifier.size(26.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("NOVA LIVE CAPTIONS", fontWeight = FontWeight.Bold, fontSize = 18.sp, letterSpacing = .7.sp)
                        Text("LIVE TRANSLATION · ON DEVICE", color = Soft, fontSize = 10.sp, letterSpacing = 1.4.sp)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    listOf("Home", "History", "Settings").forEach { name ->
                        FilterChip(selected = page == name, onClick = { page = name }, label = { Text(name) })
                    }
                }
                Spacer(Modifier.height(12.dp))
                when (page) { "Home" -> Home(); "History" -> History(); else -> SettingsPage() }
            }
        }
    }

    @Composable private fun ColumnScope.Home() {
        val revision = settingsRevision
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
            Text("Captions that keep up.", fontSize = 27.sp, fontWeight = FontWeight.Bold)
            Text("Translate supported app playback without turning on microphone input.", color = Soft, modifier = Modifier.padding(top = 5.dp))
            Spacer(Modifier.height(18.dp))
            Surface(color = Panel, shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(9.dp).background(if (running && !paused) Color(0xFF48D597) else Blue, CircleShape))
                        Spacer(Modifier.width(9.dp))
                        Text(when { !running -> "READY"; paused -> "PAUSED"; else -> "CAPTURING APP AUDIO" }, color = if (running && !paused) Color(0xFF48D597) else Soft, fontSize = 11.sp, letterSpacing = 1.2.sp)
                    }
                    Spacer(Modifier.height(15.dp))
                    Text(latest, color = Color.White, fontSize = 17.sp, lineHeight = 25.sp)
                }
            }
            Spacer(Modifier.height(18.dp))
            Text("CAPTION LANGUAGE", color = Soft, fontSize = 11.sp, letterSpacing = 1.4.sp)
            val settings = remember(revision) { CaptionSettingsStore.read(this@MainActivity) }
            Row(Modifier.padding(top = 8.dp, bottom = 5.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                SubtitleMode.entries.forEach { mode ->
                    FilterChip(selected = settings.outputMode == mode, onClick = { saveSetting("mode", mode.name) }, label = { Text(mode.title) })
                }
            }
            Spacer(Modifier.height(13.dp))
            if (!running) {
                Button(onClick = ::begin, modifier = Modifier.fillMaxWidth().height(58.dp), shape = RoundedCornerShape(17.dp)) {
                    Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(8.dp)); Text("Start captions", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = { sendServiceAction(if (paused) CaptionService.ACTION_RESUME else CaptionService.ACTION_PAUSE); paused = !paused }, modifier = Modifier.weight(1f).height(54.dp), shape = RoundedCornerShape(16.dp)) {
                        Icon(if (paused) Icons.Default.PlayArrow else Icons.Default.Pause, null); Spacer(Modifier.width(6.dp)); Text(if (paused) "Resume" else "Pause")
                    }
                    Button(onClick = ::stopCapture, modifier = Modifier.weight(1f).height(54.dp), shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF27364A))) {
                        Icon(Icons.Default.Stop, null); Spacer(Modifier.width(6.dp)); Text("Stop")
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Surface(color = Panel, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Floating subtitle preview", color = Soft, fontSize = 12.sp)
                    Text("வணக்கம் · Vanakkam · Hello", fontSize = 17.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 8.dp))
                }
            }
            Spacer(Modifier.height(14.dp))
            Surface(color = Panel, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Playback stays undisturbed", fontWeight = FontWeight.SemiBold)
                    Text("Android asks you to approve app-audio capture. NOVA reads only the permitted playback stream; it does not open a microphone. Some protected apps block capture. The local speech model downloads once (about 32 MB).", color = Soft, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 7.dp))
                }
            }
        }
    }

    private fun saveSetting(key: String, value: Any) {
        CaptionSettingsStore.update(this, key, value)
        settingsRevision++
        if (running) sendServiceAction(CaptionService.ACTION_REFRESH_SETTINGS)
    }

    @Composable private fun ColumnScope.SettingsPage() {
        val value = remember(settingsRevision) { CaptionSettingsStore.read(this@MainActivity) }
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Caption settings", fontSize = 25.sp, fontWeight = FontWeight.Bold)
            Surface(color = Panel, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("SOURCE LANGUAGE", color = Soft, fontSize = 11.sp, letterSpacing = 1.2.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
                        listOf("auto" to "Auto Detect", "ta-IN" to "\u0BA4\u0BAE\u0BBF\u0BB4\u0BCD", "en-US" to "English").forEach { (tag, title) ->
                            FilterChip(selected = value.sourceLanguage == tag, onClick = { saveSetting("source", tag) }, label = { Text(title) })
                        }
                    }
                    Spacer(Modifier.height(13.dp))
                    Text("SUBTITLE OUTPUT", color = Soft, fontSize = 11.sp, letterSpacing = 1.2.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
                        SubtitleMode.entries.forEach { mode -> FilterChip(selected = value.outputMode == mode, onClick = { saveSetting("mode", mode.name) }, label = { Text(mode.title) }) }
                    }
                    Spacer(Modifier.height(11.dp))
                    SettingSwitch("Bilingual captions", "Show the selected caption with its English/Tamil translation.", value.bilingual) { saveSetting("bilingual", it) }
                    HorizontalDivider(color = Color(0xFF253142), modifier = Modifier.padding(vertical = 8.dp))
                    Text("CAPTION TEXT SIZE", color = Soft, fontSize = 11.sp, letterSpacing = 1.2.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.padding(top = 8.dp)) {
                        CaptionTextSize.entries.forEach { size -> FilterChip(selected = value.textSize == size, onClick = { saveSetting("size", size.name) }, label = { Text(size.label) }) }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("Background transparency: ${((1f - value.backgroundOpacity) * 100).toInt()}%", color = Color.White)
                    Slider(value = 1f - value.backgroundOpacity, onValueChange = { saveSetting("opacity", 1f - it) }, valueRange = .05f..1f)
                    SettingSwitch("Save recognized captions", "Captions stay on this device. Audio is never saved.", value.saveCaptions) { saveSetting("save", it) }
                }
            }
            Surface(color = Panel, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Privacy", fontWeight = FontWeight.SemiBold)
                    Text("Audio is transcribed on-device with Whisper. NOVA does not record or upload audio. Translation models download when first needed; translation then runs on-device. Android's MediaProjection permission is temporary and can be revoked from Quick Settings.", color = Soft, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
    }

    @Composable private fun SettingSwitch(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.Medium); Text(detail, color = Soft, fontSize = 12.sp, lineHeight = 16.sp) }
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }

    @Composable private fun ColumnScope.History() {
        val lines = remember { mutableStateListOf<SubtitleLine>().also { it.addAll(SubtitleRepository.history(this@MainActivity)) } }
        Column(Modifier.fillMaxWidth().weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text("Caption history", fontSize = 25.sp, fontWeight = FontWeight.Bold)
                TextButton(onClick = { SubtitleRepository.clear(this@MainActivity); lines.clear() }) { Text("Clear") }
            }
            if (lines.isEmpty()) Text("No saved captions. Turn on Save recognized captions in Settings.", color = Soft, modifier = Modifier.padding(top = 8.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(lines) { line ->
                    Surface(color = Panel, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(15.dp)) {
                            Text(line.tamil, fontSize = 17.sp)
                            Text(line.english, color = Soft, modifier = Modifier.padding(top = 4.dp))
                            Text(SimpleDateFormat("MMM d · HH:mm", Locale.getDefault()).format(Date(line.time)), color = Soft, fontSize = 11.sp, modifier = Modifier.padding(top = 7.dp))
                        }
                    }
                }
            }
        }
    }
}
