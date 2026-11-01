# NOVA LIVE CAPTIONS

Native Android live captions for supported app playback. NOVA captures an allowed playback stream, transcribes it locally with Whisper, translates captions on device, and presents them in a floating overlay.

## Included

- Kotlin + Jetpack Compose + Material 3 dashboard, settings and caption history.
- Android 10+ `MediaProjection` consent and `AudioPlaybackCapture` in a foreground service. Android requires `RECORD_AUDIO` permission for this API, but NOVA configures `AudioRecord` for playback capture and does not read microphone input.
- Modular playback capture, speech-engine interface, Whisper implementation, on-device translation, Tamil transliteration, overlay, settings and history packages.
- Auto language detection, Tamil/English source selection and Tamil, Thanglish or English output. Bilingual captions are optional.
- Start, Pause, Resume and Stop controls. Pause stops the capture recorder while retaining the temporary session; Stop ends projection and closes the speech model.
- Caption persistence is off by default. When enabled, recognized text is stored locally; audio is never saved.
- Small, Standard or Large captions, 5–100% background transparency, draggable overlay and local history.

Android enforces the source app's playback-capture policy. Some apps, protected content and certain audio usages cannot be captured. NOVA does not bypass those restrictions. Captions are generated in rolling four-second windows; exact latency depends on device CPU, translation model availability and source playback. First run downloads the ~32 MB Whisper tiny quantized model. Translation models download when first needed. Audio transcription and translation run on device; no API keys or audio upload service are used.

## Open and run

1. Open this project in Android Studio with JDK 17, Android SDK Platform 35, NDK 27.2.12479018 and CMake 3.22.1.
2. Install the app on Android 10 (API 29) or newer. Connect to the internet for first-run model downloads.
3. Tap **Start captions**. Grant the Android audio-capture permission, **Display over other apps**, and the system MediaProjection prompt.
4. Start playback in a supported app. Use Pause/Resume while keeping the session, or Stop to end capture.
5. Choose Auto Detect, Tamil or English source; Tamil, Thanglish or English output; optional bilingual display; and other caption settings.

When Android stops projection from Quick Settings, start a fresh capture session. If the source app opts out, NOVA reports no supported stream and leaves that app's playback untouched.

## Build

With JDK 17 and Gradle 8.9:

```powershell
gradle --no-daemon :app:assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`. GitHub Actions builds the debug APK on pushes, pull requests and manual dispatch, then uploads `nova-live-captions-debug-apk`.

## Package structure

`ui`, `audio`, `speech`, `translation`, `transliteration`, `overlay`, `service`, `settings` and `data` separate the Android screens, capture/session flow and local content handling.
