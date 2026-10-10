# Hey TraceVeil and "Hey Google" shortcuts

Status, 10 October 2026: **built and unit-tested; the command mode and session choices are not yet tried on a phone.** The wake word is **experimental, opt-in and off by default.** Google stays the default assistant: TraceVeil does not request `ROLE_ASSISTANT`, declares no assist or voice-interaction component and never loops `SpeechRecognizer`.

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
| Phrase, sensitivity, event log | `voice/wake/WakePhrase.kt` | Grammar `["hey trace veil","hey traceveil","[unk]"]`; only **final** results count. **Strict** (default): every phrase word ≥ 0.90, word scores required, no speech straight before the phrase, phrase 0.35–2.5 s with no gap over 0.6 s. **Normal**: every phrase word ≥ 0.75. `WakeEventLog` counts accepted/rejected decisions (time, outcome, confidence, mode; no words, no audio) |
| Session rules | `voice/wake/WakeSession.kt` | Pure state machine (Off, Starting, Listening, Responding, Paused, Stopped); session length 15 min / 1 hour / until stopped; low-battery stop; holds (COMMAND, COPILOT, ASSISTANT) that release the microphone and resume; arming checks |
| Engine | `voice/wake/VoskWakeEngine.kt` | Vosk `Model` + grammar `Recognizer` fed from our own `AudioRecord` loop (16 kHz mono, 100 ms buffers). It can close the microphone while keeping the model loaded and reopen it (with short retries). Vosk's `SpeechService` is not used because its thread throws an uncaught exception on a failed read |
| Service | `voice/wake/HeyTraceVeilService.kt` | Not exported; `foregroundServiceType="microphone"`; `START_NOT_STICKY`; command mode after a detection |
| Choices | `voice/wake/WakePreferences.kt` | Session length and sensitivity, in their own preferences file (`hey_traceveil`), not the shared settings |
| Model | `voice/wake/WakeModelFiles.kt`, `WakeModelManager.kt` | Download, checksum verification, safe unpacking |
| UI and hooks | `voice/wake/HeyTraceVeilSection.kt`, `AssistantEntry.kt` | Settings card (length, sensitivity, Recent wake events, battery-settings hint); copilot/visibility hooks mounted in `MainActivity` |
| Commands | `core/assistant/*` (pure), `voice/assistant/*` | Parser, app-name matcher and spoken summary in `:core`; planner, voice-safe audit options, executor, offline reply voice and the screen host in the app |

Flow: Settings → switch on "Hands-free wake word" (only reveals the controls) → download the model → choose **Listen for** (15 min / 1 hour / Until I turn it off) and **Wake sensitivity** (Strict / Normal) → **Start listening** → microphone and notification permission requests if needed → service starts.

- **Start:** only from that button while TraceVeil is on screen. The UI starts a plain service, which promotes itself to a microphone foreground service after re-checking the model, the permissions, notifications and battery. A refused start therefore never leaves a pending `startForegroundService` obligation that could crash the app.
- **Session length:** without a saved choice it listens **until stopped while charging** (no battery cost; a plugged-in demo phone should not go deaf mid-presentation) and for **1 hour on battery** (covers a demo or meeting, bounds a forgotten session). The user's choice is kept.
- **While listening:** persistent notification "Listening for "Hey TraceVeil" — tap to stop", with a countdown only when a limit was chosen. Tapping it, or its Stop action, stops it. Android also shows its own microphone indicator. It keeps running with the screen off (the foreground service was started from the foreground, so it keeps microphone access); phones that stop background apps to save power can be told to allow it from the card's **TraceVeil battery settings** button.
- **Pauses and resumes by itself:** while the voice copilot is on screen (its Tap to speak needs the microphone), while a voice-started check runs and while TraceVeil is speaking a reply, the service closes its microphone ("Hey TraceVeil is paused") and reopens it afterwards, after a 1.5 s pause.
- **Stops by itself:** at the end of the chosen length (detections do not extend it); at ≤15% battery when not charging, or on Android's battery-low signal; when TraceVeil is swiped from recents; on any engine error, or if the recogniser has not started within 60 s. It is never restarted by the system or on boot.
- **On detection (command mode):** it says "Yes?" (Hindi "हाँ, बोलिए?" when the app language is Hindi; a beep when no offline voice exists), closes its microphone, hears **one** command with the phone's **on-device** `SpeechRecognizer` (`OnDeviceRecognizer`; Hindi pack when the app language is Hindi, English otherwise or when the Hindi pack is missing), acts, replies aloud with an offline voice and resumes. An unclear app name gets one follow-up "Which one?". A command that takes longer than 60 s is abandoned by a watchdog.

### Commands (wake word, copilot Tap to speak, typed text and command chips)

| Say (EN / Hinglish / Hindi) | What happens |
| --- | --- |
| "Check Instagram", "audit Instagram", "Instagram ko check karo", "इंस्टाग्राम की जाँच करो" | Finds the launcher app by label (case, spacing, small misspellings, Devanagari transliteration). Checks the VPN permission, the consent watcher and Private DNS first; if one is missing it says what to fix and opens the VPN request, Accessibility settings or network settings. Otherwise it starts a **normal Quick audit** (one Accept plan, data kept, no reset, not Fast Preview) through the same `XrayViewModel.startAudit` as the app picker, shows the live screen and says "Choose on the app's popup, I'm watching." When the check ends it speaks the saved result and opens it. |
| "Check leaky shop" | Same, but the agent taps Accept on the LeakyShop demo apps only (or packages on a developer automation allowlist, once that feature is wired in). |
| "Check insta" | "I found Instagram and Instagram Lite. Which one?" — answer "the first one", "Instagram Lite", "दूसरा" or "never mind". |
| "Check Netflix" (not installed) | "I couldn't find an app called netflix on this phone." Nothing starts. |
| "Run a full audit of Instagram", "reset and check …" | Voice never starts a Full audit or a reset: it runs the Quick check and says so. |
| "Check a file", "check this APK" | Opens APK Guard's file picker. |
| "Check my phone", "phone checkup", "mera phone check karo" | Opens the `checkup` route (My phone checkup) when it exists in the build; otherwise says it is not in this version. |
| "What did you find?", "read my last result", "kya mila", "पिछला नतीजा बताओ" | Speaks the newest saved audit (or, in the copilot, that audit): score, band, headline, most serious finding, missing choices, "in this check"; never "safe". Opens its result screen only if TraceVeil is already on screen. |
| "Which trackers did it contact?" and any other question | The existing grounded copilot answer (or its refusal plus a hint). |
| "Open reports / settings / home / the trust map" | Opens that screen (`trust` only when the build has it). |
| "What can you do?", "help", "tum kya kar sakte ho" | Lists the commands. |
| "Stop listening", "band karo", "बंद करो" | Stops the wake word. |

**Reaching the screen from the background.** Android 10+ blocks activity starts from the background. When TraceVeil is not on screen, the assistant asks it to come forward (`DeviceChecks.bringXrayToFront`, the same call the audit orchestrator already uses; Android allows it in some cases, such as while the consent watcher is bound). If TraceVeil is not on screen within 4 s, or the phone is locked, the request waits up to 2 minutes and a high-priority notification "Tap to continue: check Instagram" is posted; the spoken reply says to tap it (or to unlock first). `USE_FULL_SCREEN_INTENT` and overlays are still not requested. Commands that only speak (what did you find, questions, help, stop) work without the screen.

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

- Accuracy is **not measured**: no false-accept or false-reject rates; the Strict and Normal thresholds are reasoned starting values, not calibrated. A grammar-restricted recogniser can map similar speech onto the phrase. Use the Recent wake events counter and `adb logcat -s HeyTraceVeil` to measure.
- "traceveil" is outside the small model's vocabulary. Vosk drops that word, so in practice only "hey trace veil" is produced.
- English (US) wake model only, with Indian-English accents untested. A small Indian-English model (`vosk-model-small-en-in-0.4`) exists but is not wired in.
- Commands need Android's **on-device** recogniser (Android 12+) and its language pack (`en-US`/`en-IN`, and `hi-IN` for Hindi). Missing packs are downloaded only from the copilot's explicit Download button. Whether the phone's recognition service accepts a command while TraceVeil is in the background (it should, because the microphone foreground service is running) is **not verified on the iQOO**.
- Whether Android lets TraceVeil come forward from the background on this phone is **not verified**; the notification fallback covers it.
- Spoken replies need an installed offline voice; Hindi replies fall back to English when no offline Hindi voice is installed. Command replies never use the optional online voice.
- CPU, battery and memory cost on our phones is not measured. OEM battery managers may stop the service early.
- When another app records (for example, Google's own hotword or a call), Android may give that app the microphone and silence ours.
- APK size: Vosk adds `libvosk.so` (10,042,800 bytes arm64-v8a; 10,335,120 bytes x86_64) and JNA's `libjnidispatch.so` (176,520 / 126,912 bytes), stored uncompressed.

## 3. Phone test checklist (human, not yet done)

1. Install; long-press the icon: three shortcuts; each opens its screen.
2. Settings → Hey TraceVeil: switch on, nothing listens yet. Download the model (or push it), then "checksum verified".
3. Choose **Until I turn it off** and **Strict**; Start listening: permission prompts, persistent notification without a countdown, microphone indicator.
4. In TraceVeil, say "Hey TraceVeil", wait for "Yes?", say "What can you do?": the command list is spoken; listening resumes (notification title returns to "Listening…").
5. Say "Hey TraceVeil" … "Check Instagram" (or another agreed non-sensitive app) with VPN, watcher on and Private DNS off: spoken "Starting a Quick check…", the live screen, the app opens; make the consent choice yourself; at the end the result is spoken and opened.
6. Say "Hey TraceVeil" … "Check leaky shop": the agent taps Accept on LeakyShop; result spoken.
7. Say "Hey TraceVeil" … "Check insta": "Which one?"; answer "the first one".
8. Go to the home screen, lock nothing, say "Hey TraceVeil" … "Open reports": TraceVeil comes forward, or the notification "Tap to open TraceVeil" appears and the reply says to tap it.
9. Screen off for 20+ minutes on battery with **1 hour** chosen: it still answers; after 1 hour it stops with "Stopped automatically after 1 hour, as you chose."
10. Open the voice copilot: the notification says paused; Tap to speak works; leave the copilot: listening resumes.
11. Low battery: `adb shell dumpsys battery unplug` and `adb shell dumpsys battery set level 10`: it stops within about 15 s; then `adb shell dumpsys battery reset`.
12. Watch `adb logcat -s HeyTraceVeil` and the Recent wake events counter during ordinary conversation and hall noise in Strict, then Normal, before calling it usable.

## Tests

JVM tests in `app/src/test/java/com/dpdpxray/app/voice/wake/`: `WakePhraseTest` (Strict/Normal decisions on sample Vosk results), `WakeEventLogTest`, `WakeSessionPolicyTest` (15 min / 1 hour / until stopped, charging default, battery), `WakeMachineTest` (command, copilot and check holds, watchdog, resume), `WakeResponseTest` (arming), `WakeCopyTest`, `AssistantFeaturesTest`, `WakeModelFilesTest` (checksums, zip-slip, size limit, manual folder) and `HeyTraceVeilManifestTest` (private microphone service, no assist/voice-interaction/full-screen permissions, shortcuts wiring). In `app/src/test/java/com/dpdpxray/app/voice/assistant/`: `AssistantPlannerTest` (readiness, ambiguity, not found, no Full/reset, routes, no safety claims) and `VoiceAuditOptionsTest` (Quick, no reset, auto-tap only for demo/allowlisted apps). In `core/src/test/kotlin/com/dpdpxray/core/assistant/`: `VoiceCommandParserTest` (EN, Hindi, Hinglish, every copilot chip stays a question), `AppNameMatcherTest` (exact, typos, multi-word, Devanagari, ambiguity, not found), `ChoiceReplyTest` and `AuditSpokenSummaryTest`.
