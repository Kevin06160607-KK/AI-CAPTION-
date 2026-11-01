# AI Tamil Live Subtitles

A Kotlin and Jetpack Compose Android app for live speech recognition, Tamil translation, Tamil-to-Thanglish transliteration, an always-on-top subtitle panel, and on-device subtitle history.

## Capabilities and Android limits

- Speech recognition runs in a microphone foreground service with an ongoing notification and a Stop action.
- Supports Tamil, Thanglish, and English output. ML Kit Translation downloads language models on demand; Tamil/English/Hindi are inferred from the recognized script when possible.
- Tamil to Thanglish uses Android ICU's `Tamil-Latin` transliterator, which follows transliteration rules instead of replacing characters individually.
- The movable overlay requires the Android “Display over other apps” permission. Font size, foreground color, and panel opacity are configurable.
- History is stored locally in app preferences (up to 300 entries) and can be cleared in the app.
- No API key or audio upload endpoint is used by this app.

Android playback capture is not a general-purpose way to feed audio into speech recognition. It requires user-approved MediaProjection, Android 10+, and cooperation from the source app; apps can opt out. Android's public `SpeechRecognizer` listens through the microphone and has no API to accept an `AudioRecord` stream captured from another app. This build therefore uses the microphone for real recognition. When allowed, device audio may be audible to the microphone while speakers are in use, but headphone playback will not be captured. Read Android's audio capture and foreground service documentation before extending this into a playback-capture pipeline.

Recognition language is currently set to English (United States), which is a practical default for speech services. Change `source` in `SubtitleService.listenAgain()` to `ta-IN` or `hi-IN` for speech services that need a fixed language. Language auto-detection quality depends on the installed Android recognizer. The translated text is best-effort; model availability and language support are supplied by ML Kit.

## Requirements

- Android Studio with JDK 17
- Android SDK Platform 35 and Build Tools installed
- Android 8.0 (API 26) or newer device; Android 10+ is recommended

## Open and run

1. Open this directory in Android Studio and allow Gradle sync.
2. Run the `app` configuration on a device.
3. Grant microphone permission and “Display over other apps” access when prompted.
4. Choose Tamil, Thanglish, or English output and tap **Start live subtitles**.
5. The foreground notification remains available to stop recognition while another app is visible.

Connect to Wi-Fi once to download each ML Kit translation model used. Models run on device after download.

## Build a debug APK

From Android Studio, choose **Build > Build Bundle(s) / APK(s) > Build APK(s)**. Or, with Gradle 8.9 and JDK 17 installed:

```powershell
gradle --no-daemon :app:assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. GitHub Actions runs this same build on pushes, pull requests, and manual dispatch, then uploads the APK as a workflow artifact.

## Privacy and permissions

The app requests `RECORD_AUDIO`, overlay access, and foreground-service permissions. Audio is processed by Android's recognizer; device vendors may route recognition through their speech service. Translation runs through ML Kit. No credentials are embedded. Revoke microphone or overlay access in Android Settings at any time.
