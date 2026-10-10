# Proposal: optional Sarvam online voice mode (NOT enabled, no requests made)

Status: team decided (10 Oct) to add an online TTS-only demo voice: `CloudSpeechOutput`, enabled only by `USE_API=true` plus `SARVAM_API_KEY` in the adb-pushed `api.env`. Voices: `ishita` (en-IN), `priya` (hi-IN). STT stays on-device. The rest of this proposal (consent screen, STT, comparison) is still open.

## Principles
- Offline Y1 is the product. Answers come from `GroundedAnswerer` (audit evidence + at most 3 corpus passages) and never depend on a GPU model or Sarvam.
- Voice is replaceable: `SpeechOutput` (output) exists; an input equivalent should be added the same way before any online STT. Languages come from `CopilotLanguage`, so more can be added.
- Online mode is a separate, user-chosen mode: off by default, explicit consent before the first upload and after any change of provider/model, no silent fallback from offline to cloud (a failure shows a message and stays offline), no key in the APK or Git.

## Proposed data flow
| Step | Offline (default) | Online (opt-in) |
|---|---|---|
| Hear question | `SpeechRecognizer.createOnDeviceSpeechRecognizer` | Push-to-talk audio (max ~10 s, kept in memory only) -> Sarvam STT -> transcript |
| Answer | local `GroundedAnswerer` | same, local. Never sent to Sarvam |
| Speak answer | local Android TTS voice | answer text (<=2,500 chars) -> Sarvam TTS -> WAV played, then discarded |

Leaves the phone only in online mode: question audio (STT) and answer text (TTS). Audit evidence, package names, endpoints and reports are never sent to Sarvam. Answer text may name an app/host from the audit, so users see a preview-capable consent screen and a "sample/test phrases only" option.

## Key handling
User pastes their own key at runtime into app-private storage (Keystore-backed); not in BuildConfig, resources, `.env` or Git. The existing `ApiEnv`/`SarvamTts` prototype (adb-pushed `api.env`) is now unwired from the app; it can be reused after review or removed.

## Versions to confirm BEFORE any real request
Verified from https://docs.sarvam.ai/llms.txt (fetched today) and the Sarvam skill:
- TTS: `POST https://api.sarvam.ai/text-to-speech`, header `api-subscription-key`, `model: "bulbul:v3"`, body key `language_code` (renamed from `target_language_code` in SDK 0.1.29 / JS 1.1.8, 2026-08-03), speaker `shubh`, REST limit 2,500 chars. `pitch`/`loudness` are rejected on v3.
- STT: docs list "Saaras v3" but a changelog entry mentions "saaras:v4"; REST path, model string and max audio length were NOT stated. **Open: read the STT model page and pin the exact model id and endpoint.** Also open: Sarvam's data-retention/training terms.

## Required before enabling
1. Nitya: manifest/INTERNET already present; navigation, Results button and consent screen wiring (her files).
2. Privacy wording updated (Settings currently says "TraceVeil uploads nothing"; true again now that the cloud note was removed, must change if online mode ships), plus Sarvam attribution.
3. Network usage reported separately in Network Proof (Y2), as an "optional online voice" line, never folded into the audit residual.
4. Team sign-off on this flow and the pinned versions.

## Comparison plan (after approval)
Use only non-sensitive scripted phrases: the 8 question chips in English and Hindi plus ~10 noisy/accented variants, no audit data. Compare offline vs Sarvam on: STT word error rate and chip-intent match rate, TTS intelligibility (rated by two listeners), latency, bytes sent, failure behaviour offline. Decision rule: adopt only if it materially improves Hindi/accented intent match or voice quality without breaking the principles above.
