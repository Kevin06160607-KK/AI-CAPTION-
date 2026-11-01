# AI Tamil Live Subtitles

Android captions for app playback using Android AudioPlaybackCapture and a local Whisper speech model. App playback is captured directly and is not played through the microphone, so the app does not duck or interrupt the video's audio. The foreground service shows a persistent Stop action.

## Features

- Playback audio capture after the Android MediaProjection consent prompt. Requires Android 10 (API 29) or newer, `RECORD_AUDIO` permission (required by Android for playback capture), and an app that permits capture. The recorder is configured only with `AudioPlaybackCaptureConfiguration`; it never opens a microphone input.
- On-device multilingual Whisper transcription. The approximately 32 MB tiny quantized model downloads from the official `ggerganov/whisper.cpp` model repository the first time; transcription then runs locally.
- Tamil, Thanglish and English subtitles. Tamil-to-Thanglish uses Android ICU `Tamil-Latin` transliteration. Translation between Tamil, English and Hindi uses ML Kit on-device models downloaded as needed.
- Draggable floating overlay, configurable size, text color and opacity, and on-device history (last 300 entries).
- No API keys, audio uploads or cloud speech-recognition service.

Some apps deliberately block playback capture; Android cannot override their capture policy. Audio captions update in short rolling windows, so captions can appear a few seconds after the spoken audio. Translation models need an internet connection on their first use. The app supports arm64 phones and x86_64 emulators.

## Run on a device

1. Open the project in Android Studio with JDK 17, Android SDK Platform 35, NDK 27.2.12479018 and CMake 3.22.1.
2. Install and open the app. Connect to Wi-Fi for the first run so it can download the local Whisper model; translation models download when first needed.
3. Tap **Start device audio captions**. Allow the Android audio permission, overlay permission, and the system playback-capture prompt. The Android audio permission is a platform prerequisite; this app captures the playback stream and does not use microphone input.
4. Start or resume the video in the other app. Choose Tamil, Thanglish or English. Use the persistent notification to stop capture.

The Android system can stop MediaProjection at any time from Quick Settings. Start a new session to resume. Protected apps may provide silence by design.

## Build a debug APK

With JDK 17 and Gradle 8.9:

```powershell
gradle --no-daemon :app:assembleDebug
```

The APK is `app/build/outputs/apk/debug/app-debug.apk`. GitHub Actions builds it on push, pull request or manual dispatch and uploads the APK artifact.

## Privacy

Audio stays on device for transcription. The app asks Android to grant a temporary MediaProjection token, reads only playback usages allowed by Android, and stops when the user or system ends the session. The only network downloads are the Whisper model and on-device translation models. No recognized audio or text is uploaded.
