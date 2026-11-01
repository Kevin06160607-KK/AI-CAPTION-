package com.nova.livecaptions.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.view.*
import android.widget.TextView
import com.nova.livecaptions.settings.CaptionSettingsStore

class CaptionOverlay(private val context: Context) {
    private val window = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: TextView? = null
    private var params: WindowManager.LayoutParams? = null

    fun show() {
        if (view != null) return
        val prefs = context.getSharedPreferences("nova_caption_settings", Context.MODE_PRIVATE)
        val settings = CaptionSettingsStore.read(context)
        val caption = TextView(context).apply {
            setTextColor(prefs.getInt("color", 0xFFFFFFFF.toInt()))
            textSize = settings.textSize.sp
            setPadding(22, 14, 22, 14)
            setBackgroundColor(((settings.backgroundOpacity * 255).toInt() shl 24) or 0x00070D18)
            text = "Preparing local speech model..."
        }
        val layout = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt("overlay_x", 24)
            y = prefs.getInt("overlay_y", 140)
        }
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        caption.setOnTouchListener { _, e -> when (e.action) {
            MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = layout.x; startY = layout.y; true }
            MotionEvent.ACTION_MOVE -> { layout.x = startX + (e.rawX - downX).toInt(); layout.y = startY + (e.rawY - downY).toInt(); prefs.edit().putInt("overlay_x", layout.x).putInt("overlay_y", layout.y).apply(); window.updateViewLayout(caption, layout); true }
            else -> false
        } }
        window.addView(caption, layout)
        view = caption
        params = layout
    }

    fun showText(text: String) { view?.post { view?.text = text } }
    fun updateSettings() {
        val settings = CaptionSettingsStore.read(context)
        val color = context.getSharedPreferences("nova_caption_settings", Context.MODE_PRIVATE).getInt("color", 0xFFFFFFFF.toInt())
        view?.post {
            view?.setTextColor(color)
            view?.textSize = settings.textSize.sp
            view?.setBackgroundColor(((settings.backgroundOpacity * 255).toInt() shl 24) or 0x00070D18)
        }
    }
    fun remove() { runCatching { view?.let(window::removeView) }; view = null; params = null }
}
