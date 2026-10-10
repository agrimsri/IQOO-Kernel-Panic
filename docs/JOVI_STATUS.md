# Jovi feasibility — 10 October 2026

## Decision

A supervised optional Office Kit trial is reasonable after the user personally reviews and accepts the terms. Jovi has not been installed, launched, paired or integrated by the coordinator. Do not advertise an embedded Jovi voice engine, offline processing, global availability or bonus points.

Keep TraceVeil's core independent: local evidence collection, deterministic evaluation, offline voice where supported and truthful exports. An optional desktop companion is a separate proposed workflow, not a replacement for those components.

## Official documents checked

- [Office Kit product page](https://pc.vivoglobal.com/): identifies Jovi as a PC automation assistant; India is among its listed supported regions. Some countries/models/features are unsupported.
- [Jovi agreement](https://pc.vivoglobal.com/#/userProtocolVclawEx?version=3&dark=false&language=en_US): served English document dated 20 August 2026.
- [Jovi privacy terms](https://pc.vivoglobal.com/#/privacyPolicyVclawEx?version=3&dark=false&language=en_US): served English document dated 1 September 2026.
- [vivo AI tools platform](https://aitools.vivo.com.cn/platform): advertises Skill/Agent/Tool registration and review. This China-platform evidence does not establish access, approval or compatibility for India's Office Kit build.

The two terms pages require JavaScript and the browser connection failed during setup. Their exact route definitions and referenced public English content modules were read without executing service operations or accepting terms:
[agreement module](https://pc.vivoglobal.com/website/static/js/chunk-2d22927f.8472d010.js),
[privacy module](https://pc.vivoglobal.com/website/static/js/chunk-2d2218b2.68cddea2.js).
Read the current rendered agreements again when enabling the actual service; later changes can supersede this snapshot.

## Licence and privacy implications

Agreement 2.1/2.4 describes third-party integrations, models, APIs and Skills. It does not supply an Android speech SDK or grant distribution rights. Sections 3.4/6.1 restrict commercial use and provision to third parties. AI Appendix 3 restricts scripted extraction of outputs, professional advice to third parties and removing AI identifiers. Account/model-provider terms and costs also apply. Permission-based agent operations require supervision and can have irreversible consequences.

Privacy terms describe collection of input text/voice/files, generated outputs and history. Necessary files/location may be uploaded with consent; AI content can be shared with Google or other providers and processed across borders. A personal API key is stored locally, but its content is sent to that configured model provider. Optional experience telemetry can be disabled. This is not an offline-only processing promise.

## What integration might mean

Possible future direction, subject to documented support and approval: a user-triggered Jovi Skill/Tool that opens a deliberately exported, non-sensitive TraceVeil summary, or launches an explicitly permitted action. This is a design hypothesis, not a working API demonstration.

Not approved: scraping the Jovi UI/output, reverse engineering, reusing private credentials, embedding its binaries, covert recording, letting it decide Android permissions, or making it the legal/scoring authority.

Before code, obtain:

1. Official India-build integration documentation and supported interface.
2. Required account/registration/review and licence clarification for the hackathon and distribution.
3. Explicit scope and informed consent for every external data flow.
4. A separate test plan and a fallback when Jovi is unavailable.

## Hey TraceVeil

Not implemented. Current integration provides in-app push-to-talk and keeps Google as the default assistant. A custom companion wake word is a separate Android microphone/foreground-service subsystem needing approved design, visible arming, privacy/battery policy and OEM testing.

Android explicitly says [SpeechRecognizer is not intended for continuous recognition](https://developer.android.com/reference/android/speech/SpeechRecognizer). [Microphone foreground services](https://developer.android.com/develop/background-work/services/fgs/service-types#microphone) have permission and background-start restrictions. Do not implement a perpetual SpeechRecognizer loop or promise secret always-on/locked-screen activation.
