# TraceVeil: Round 1 mentor brief

Use only the build and features merged before the evaluation freeze. Check [verification](VERIFICATION.md) for what was actually tested; unit-test success is not phone acceptance.

## Thirty-second pitch

“A Reject button is a promise, but a screenshot cannot show whether tracking SDKs respect it. TraceVeil connects an Android app's observed DNS lookups with its recorded consent choices, explains the evidence, and gives developers practical remediation templates. It runs analysis on the phone and makes missing coverage visible. Our technical workflow is useful globally; the legal reference layer currently covers India's DPDP framework.”

## Who benefits

- Developers and QA teams can test their own builds, find premature SDK initialization, inspect refusal timing and compare repeat audits of the same package.
- Privacy reviewers can inspect timestamped evidence, limitations and cited reference passages, then export a report or review a notice draft.
- Users can inspect saved results through plain-language questions and spoken answers. This is not a universal fraud detector, malware verdict or permission firewall.

## Explain the pipeline

1. Select a permitted test app. Quick defaults preserve real-app data and use manual consent choices; explicit reset approval and Shizuku enable repeatable developer tests.
2. A per-app Android VPN observes and relays supported DNS traffic. No TLS certificate is installed, and encrypted application payloads are not decrypted. A DNS query shows a lookup, not proof of uploaded personal data or a completed server connection.
3. The accessibility watcher reads the armed target app's consent UI. It timestamps detected screens and choices; an inferred choice is labeled. It does not automatically approve Android permission dialogs.
4. Each session saves a consistent capture snapshot, health counters and separately bracketed UID readings. Unknown data remains unknown, not zero.
5. Deterministic checks classify tracker hosts and recorded aliases, relate events to consent phases and produce findings. The optional local model can assist screen review; model availability is not required for the deterministic DNS checks.
6. Results show evidence coverage, findings, alias trails and a qualified byte estimate. The saved-audit copilot gives deterministic answers with relevant retrieved passages, not unrestricted model-generated advice. Exports retain coverage warnings.

## Four-minute demonstration

Before starting: confirm the exact APK, VPN permission, consent watcher, Private DNS state and any other active VPN. Obtain permission to test; avoid banking, payments, OTP, passwords, personal notifications and private chats. Keep Google as the existing assistant.

**Repeatable case:** run the controlled leaky demo with Full audit. Show a recorded refusal, then the later tracker lookup and C2 evidence. Inspect a screen finding and its fix template. Contrast with a separate controlled fixed-demo audit. The core regression expects 21/100 versus 100/100; do not present those numbers as fresh live results unless this exact build reproduced them. The two flavors have different package IDs, so this contrast is not the automatic same-package comparison card.

**Real-world case:** select an agreed non-sensitive installed app. Leave “Allow clearing this test app's local data” and automatic tapping off. Run Quick and make any consent choice yourself. Explain observed DNS hosts and limits, without calling the app fraudulent. An already-opened app may show no dialog or repeat no lookups; encrypted/cached DNS may be invisible. If the run is quiet or incomplete, demonstrate the warning rather than claim the app passed.

**Accessibility:** open “Ask this audit.” Ask about trackers, score and refusal; each question addresses its own evidence. An off-topic question is refused. Try the mic only after consenting to microphone access. If an offline language pack is absent, use the explicit Download/Check again controls or chips. Audibility and voice quality require human confirmation. Android documents [on-device recognition and model download](https://developer.android.com/reference/android/speech/SpeechRecognizer) separately from general speech recognition.

**Action:** open a C2 notice draft, inspect its exact evidence, then Copy or Share only by user choice. Finish with the exported report's scope warning. Never send a notice automatically.

Fallback: clearly labeled synthetic samples demonstrate the UI if the live device is unavailable. Sample capture confidence and Network Proof are unmeasured; reserved example IPs do not establish real locations. A sample is not a live test.

## Questions to be ready for

**Why both demo and real apps?** Controlled apps give a known, repeatable regression. Real apps demonstrate applicability but cannot guarantee a visible consent screen, fresh state or complete DNS coverage. Both matter; neither alone proves complete detection.

**Does a score of 100 mean safe or legally compliant?** No. It describes the checks supported by recorded evidence. Missing intended choices, low visibility and capture problems are explicit limitations. A missing finding is not proof of compliance.

**What does Network Proof prove?** It compares saved UID transmission readings with estimated DNS wire bytes, including an IPv4/UDP allowance. Tunnel accounting, other UID traffic and timing can affect the residual. We have not established OEM accounting accuracy or zero uploads.

**What is an alias trail?** A recorded CNAME can link a first-party-looking hostname to a catalogued tracker. We label it “alias observed”; not every CNAME is disguise, and it is not payload evidence.

**Is it global?** The Android evidence pipeline is not India-only. Current legal references and notice wording are DPDP-specific, and current voice languages are English/Hindi. More jurisdictions require independently sourced legal modules; an IP country never determines applicable law by itself.

**Where are the map and Jovi?** The published map is now mounted locally with sourced A5 owner facts. Without a licensed GeoLite database, countries remain unknown; filled-map phone rendering is still unverified. The outline is non-commercial licensed, not blanket commercial-release clearance. Jovi appearing in Office Kit does not establish a supported Android SDK/API; its linked terms were not successfully read, and no integration or installation is verified.

**Is the cited framework already enforceable in full?** Do not claim that. The official [commencement notification](https://upload.indiacode.nic.in/showfile?actid=AC_CEN_45_0_00003_2023-22_1763464807080&filename=c56ceae6c383460ca69577428d36828b.pdf&type=hindinotification) stages provisions, scheduling Section 3 eighteen months after 13 November 2025. As of this demonstration, use readiness language and check commencement, exceptions and applicability with qualified reviewers.

**What has actually been built by the team?** Explain the baseline and new contributions honestly: Agrim owns evidence parsing/classification/health, Yashvi owns voice/network presentation and forthcoming map, Nitya integrates persistence/navigation/evidence UI and validation. Preserve provenance, genuine commit authors and attributions. Baseline reuse eligibility needs organizer confirmation; commit timestamps do not prove originality.

## Before main is accepted

Run fresh tests and a review, publish the integration branch, and let Agrim review it. Phone acceptance still needs microphone/pack checks, audible distinct replies, old/sample/live results, report export, fresh capture and repeated consent detection. Agrim's report requests ten consecutive full leaky runs for the intermittent consent bug. Do not say it is phone-fixed before those checks. The teammate JDK-kit fix additionally needs its missing setup scripts.
