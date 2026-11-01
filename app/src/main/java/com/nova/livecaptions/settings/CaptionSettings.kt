package com.nova.livecaptions.settings

import android.content.Context
import com.nova.livecaptions.data.SubtitleMode

enum class CaptionTextSize(val sp: Float, val label: String) {
    SMALL(16f, "Small"), STANDARD(20f, "Standard"), LARGE(28f, "Large")
}

data class CaptionSettings(
    val sourceLanguage: String = "auto",
    val outputMode: SubtitleMode = SubtitleMode.TAMIL,
    val bilingual: Boolean = false,
    val textSize: CaptionTextSize = CaptionTextSize.STANDARD,
    val backgroundOpacity: Float = .8f,
    val saveCaptions: Boolean = false,
)

object CaptionSettingsStore {
    private const val FILE = "nova_caption_settings"

    fun read(context: Context): CaptionSettings {
        val p = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return CaptionSettings(
            sourceLanguage = p.getString("source", "auto") ?: "auto",
            outputMode = runCatching { SubtitleMode.valueOf(p.getString("mode", "TAMIL") ?: "TAMIL") }.getOrDefault(SubtitleMode.TAMIL),
            bilingual = p.getBoolean("bilingual", false),
            textSize = runCatching { CaptionTextSize.valueOf(p.getString("size", "STANDARD") ?: "STANDARD") }.getOrDefault(CaptionTextSize.STANDARD),
            backgroundOpacity = p.getFloat("opacity", .8f).coerceIn(0f, 1f),
            saveCaptions = p.getBoolean("save", false),
        )
    }

    fun update(context: Context, key: String, value: Any) {
        val edit = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
        when (value) {
            is String -> edit.putString(key, value)
            is Boolean -> edit.putBoolean(key, value)
            is Float -> edit.putFloat(key, value)
        }
        edit.apply()
    }
}
