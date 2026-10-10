# Hey TraceVeil and "Hey Google" shortcuts

Status, 10 October 2026: **built and unit-tested, not yet tried on a phone.** The wake word is **experimental, opt-in and off by default.** Google stays the default assistant: TraceVeil does not request `ROLE_ASSISTANT`, declares no assist or voice-interaction component and never loops `SpeechRecognizer`.

## 1. Assistant shortcuts (no TraceVeil microphone)

`app/src/main/res/xml/shortcuts.xml` declares three static shortcuts (long-press the TraceVeil icon) that are also App Actions for `actions.intent.OPEN_APP_FEATURE`:

| Shortcut id | Say, for example | Opens |
| --- | --- | --- |
| `check_app` | "Hey Google, open TraceVeil check an app" | App picker (`pick`) |
| `check_apk` | "… check an APK file" | `apkguard` if that screen exists in the build; otherwise a message says it is not available |
| `ask_last_check` | "… ask about my last check" | Voice copilot for the newest saved audit; a message if there is none |

Google Assistant passes either the shortcut id or the spoken feature text in the `feature` extra. `AssistantFeatures.parse` maps both; `AssistantEntry` navigates only to existing screens. It never starts an audit, grants a permission or opens a microphone. `MainActivity` is singleTask, so requests reach it through `onCreate` (only on a fresh start) or `addOnNewIntentListener`.

Limits: App Actions fulfilment for a sideloaded APK needs Google's App Actions Test Tool (Android Studio plugin, same Google account) or Play publication; on phones where Gemini replaces Google Assistant, App Actions support is not verified. "Hey Google, open TraceVeil" and the launcher shortcuts work without either.

## 2. "Hey TraceVeil" wake word

### Design

| Piece | File | Role |
| --- | --- | --- |
| Phrase and grammar | `voice/wake/WakePhrase.kt` | Grammar `["hey trace veil","hey traceveil","[unk]"]`; only **final** results count; every word of the phrase needs confidence ≥ 0.70 when Vosk reports it |
| Session rules | `voice/wake/WakeSession.kt` | Pure state machine (Off, Starting, Listening, Responding, Stopped), 15-minute cap, low-battery stop, arming checks, launch route and spoken reply |
| Engine | `voice/wake/VoskWakeEngine.kt` | Vosk `Model` + grammar `Recognizer` fed from our own `AudioRecord` loop (16 kHz mono, 100 ms buffers). Vosk's `SpeechService` is not used because its thread throws an uncaught exception on a failed read |
| Service | `voice/wake/HeyTraceVeilService.kt` | Not exported; `foregroundServiceType="microphone"`; `START_NOT_STICKY` |
| Model | `voice/wake/WakeModelFiles.kt`, `WakeModelManager.kt` | Download, checksum verification, safe unpacking |
| UI and hooks | `voice/wake/HeyTraceVeilSection.kt`, `AssistantEntry.kt` | Settings section; copilot guard and visibility hook mounted in `MainActivity` |

Flow: Settings → switch on "Hands-free wake word" (only reveals the controls) → download the model → **Start listening (15 minutes)** → microphone and notification permission requests if needed → service starts.

- **Start:** only from that button while TraceVeil is on screen. The UI starts a plain service, which promotes itself to a microphone foreground service after re-checking the model, the permissions, notifications and battery. A refused start therefore never leaves a pending `startForegroundService` obligation that could crash the app.
- **While listening:** persistent notification "Listening for "Hey TraceVeil" — tap to stop" with a countdown. Tapping it, or its Stop action, stops it. Android also shows its own microphone indicator.
- **Stops by itself:** after 15 minutes from the engine becoming ready (detections do not extend it); at ≤15% battery when not charging, or on Android's battery-low signal; when the voice copilot screen opens (only one recogniser may hold the microphone); when TraceVeil is swiped from recents; on any engine error, or if the recogniser has not started within 60 s. It is never restarted by the system or on boot.
- **On detection:** audio is ignored for 6 s so the reply cannot re-trigger it, and the phone's offline text-to-speech (`AnswerVoice`, offline voices only) speaks a reply that matches what will actually happen:
  - TraceVeil visible → it opens the copilot for the latest audit directly: "Yes? Opening your last check."
  - Otherwise → a high-priority notification "Hey TraceVeil heard you — Tap to open your last check": "Yes? Tap the TraceVeil notification to see your last check."
  - No saved audit → the reply says so.

Background activity starts: since Android 10 an app cannot start an activity from the background just because it has a foreground service. A full-screen intent would need `USE_FULL_SCREEN_INTENT`, which Android 14+ grants by default only to calling and alarm apps and Play restricts to those uses, so it is not requested. The existing accessibility service is not used to bypass this. The user taps the notification instead.

### Privacy

- Recognition is fully offline. Audio buffers are passed to the recogniser and overwritten; nothing is written to storage, logged or uploaded.
- The only network use is the one-time model download, and only after the user taps Download. It goes through the phone's download service to `alphacephei.com`: an HTTP request, no audio or audit data.
- The Settings section says all of this on screen. Follow-up for the Settings owner: the general Privacy note still says the DNS relay is TraceVeil's "only other network use"; once a user downloads the model, that sentence should also mention this optional download.

### Model: not bundled, verified before use

| | |
| --- | --- |
| Archive | `vosk-model-small-en-us-0.15.zip`, 41,205,931 bytes, from `https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip` (Apache 2.0) |
| MD5 (official) | `09ab50ccd62b674cbaa231b825f9c1cb`, from the official `model-list.json` |
| SHA-256 (pinned) | `30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498` |
| Location | `getExternalFilesDir("wake")` = `/sdcard/Android/data/com.dpdpxray.app/files/wake/` |

alphacephei publishes only MD5 checksums. The SHA-256 was computed on 10 October 2026 from a copy downloaded from the official URL whose size and MD5 matched the official list. The app requires size, SHA-256 **and** MD5 to match before unpacking. Unpacking accepts only entries under `vosk-model-small-en-us-0.15/`, refuses absolute paths, `..`, more than 64 entries or more than 80 MiB, goes through a staging folder, checks the 13 required model files and deletes the archive. A rejected archive is deleted.

Developer alternative, without the in-app download (open Settings → Hey TraceVeil once so the folder exists):

```sh
# Verified path: push the official zip, then tap "Check again" in Settings.
adb push vosk-model-small-en-us-0.15.zip /sdcard/Android/data/com.dpdpxray.app/files/wake/
# Unverified path: push an unpacked folder. It works, but Settings shows it as "not verified".
adb push vosk-model-small-en-us-0.15 /sdcard/Android/data/com.dpdpxray.app/files/wake/
```

### Known limits

- Accuracy is **not measured**: no false-accept or false-reject rates, and the 0.70 confidence floor is not calibrated. A grammar-restricted recogniser can map similar speech onto the phrase.
- "traceveil" is outside the small model's vocabulary. Vosk drops that word, so in practice only "hey trace veil" is produced.
- English (US) model only, with Indian-English accents untested. A small Indian-English model (`vosk-model-small-en-in-0.4`) exists but is not wired in.
- CPU, battery and memory cost on our phones is not measured. OEM battery managers may stop the service early.
- When another app records (for example, Google's own hotword or a call), Android may give that app the microphone and silence ours.
- APK size: Vosk adds `libvosk.so` (10,042,800 bytes arm64-v8a; 10,335,120 bytes x86_64) and JNA's `libjnidispatch.so` (176,520 / 126,912 bytes), stored uncompressed. The debug APK (two ABIs) grew from 85,446,999 to 106,570,883 bytes; an arm64-only release grows by about 10.3 MB.

## 3. Phone test checklist (human, not yet done)

1. Install; long-press the icon: three shortcuts; each opens its screen ("Check APK" shows the not-available message until the APK screen is merged).
2. Simulate the assistant: `adb shell am start -n com.dpdpxray.app/.MainActivity -a android.intent.action.VIEW --es feature "check an app"` (and "ask about my last check", "check an APK file"). Optionally use the App Actions Test Tool for a real "Hey Google" query.
3. Settings → Hey TraceVeil: switch on, nothing listens yet. Download: system download notification, then "checksum verified". Try a corrupted zip via adb: rejected and deleted.
4. Start listening: permission prompts, persistent notification with countdown, microphone indicator. Tap the notification: it stops.
5. Start again, stay in TraceVeil, say "Hey TraceVeil": audible offline reply and the copilot opens; listening stops (copilot guard).
6. Start again, go to the home screen, say it: reply plus heads-up notification; tap it: copilot opens.
7. Leave it 15 minutes: it stops with "Stopped automatically after 15 minutes."
8. Low battery: `adb shell dumpsys battery unplug` and `adb shell dumpsys battery set level 10`: it stops within about 15 s; then `adb shell dumpsys battery reset`.
9. Swipe TraceVeil from recents while listening: it stops. Reboot: it does not start.
10. Count false triggers during ordinary conversation and TV audio before calling it usable.

## Tests

JVM tests in `app/src/test/java/com/dpdpxray/app/voice/wake/`: `WakePhraseTest`, `WakeSessionPolicyTest`, `WakeMachineTest`, `WakeResponseTest`, `AssistantFeaturesTest`, `WakeModelFilesTest` (checksums, zip-slip, size limit, manual folder) and `HeyTraceVeilManifestTest` (private microphone service, no assist/voice-interaction/full-screen permissions, shortcuts wiring).
