# Saved evidence and voice integration

This is Nitya's integration of Agrim A4 and Yashvi Y1/Y2. It does not replace their implementations. Device acceptance and publication status are recorded in [verification](VERIFICATION.md).

## Integration contracts

| Boundary | What the integration does |
| --- | --- |
| Capture | Takes `CaptureBus.snapshot()` at the end of each plan, before awaiting remaining screen analysis. Uses that snapshot's events and health together. `PlanCaptureRecorder` brackets reset/snapshot with UID reads and preserves separate sampling timestamps. |
| Saved audit | Stores `AuditSession.captureHealth` in `sessions.json` and nullable per-plan UID readings in `network-readings.json`. |
| Confidence | Shows `ConfidenceGrader.gradeAudit(sessions).summary` for a real audit; labels sample capture confidence as unmeasured. |
| Network Proof | Joins saved readings to session IDs and capture windows, then uses `SessionCounters.from` and Yashvi's calculator/card. Unknown is not zero. |
| Voice | Opens `VoiceCopilotScreen(auditId, onBack)` at `voice/{id}`, from Results or Home's latest saved audit. |
| Permissions | Declares microphone permission and speech/TTS service queries; Y1 asks at runtime only when the user chooses microphone input. |

The UID readings cannot be reconstructed from an old audit or sampled when Results opens. Every measured session needs exactly one matching reading with known sampling times. Sampling intervals must bracket an active capture snapshot and must not overlap another plan's interval. Stopped, restarted/gapped or uncertain capture is unavailable even if it retained partial evidence. Missing/corrupt/future-version sidecars leave the audit usable but its Network Proof unavailable. Export/import implementations must include the sidecar to retain these readings; current report exports do not constitute an audit backup.

Readings are nullable because Android may not support them. Unsupported packet readings remain null rather than becoming zero packets. The calculator retains negative residuals and warns about inconsistent counters. Its 28-byte-per-datagram allowance is estimated IPv4/UDP overhead. UID sampling and snapshot timing are not atomic; the recorded sampling interval can include sends just outside the capture boundary. UID accounting may also include other traffic or tunnel writes; a small residual is not evidence that no data ever left the phone. Confidence is capture coverage, not a safety certificate.

## Teammate handoff

Agrim should review the snapshot lifecycle against PR #3, including revocation and packets in flight at a plan boundary. Yashvi should review the recorded UID intervals and test offline recognition/TTS on her own phone. Neither owner needs to duplicate the manifest, navigation, persistence or Results wiring.

The current approved milestone uses offline speech and deterministic, cited audit answers. The Sarvam prototype is not wired into this route. Do not add cloud fallback without a reviewed opt-in disclosure covering the provider, transmitted audio/text, retention and how the user turns it off. English/Hindi support is the current voice-screen scope, not a claim of worldwide language coverage. Google remains the default assistant; a custom wake word is separate work.

N4 rights-notice/alias UI and N7 same-package comparison are implemented locally. C2 notices are reviewed before Copy/Share, never sent automatically. A missed planned choice invalidates a score comparison. Home's Camera entry and route are removed; Yashvi's unreachable helper cleanup remains separate. New reports and app-rendered/exported older reports replace the legacy zero-byte assertion with qualified DNS/local-analysis scope. Jovi's presence in Office Kit is not evidence of an embeddable Android model/API.

Y3 was initially unpublished at `39c915a`, then arrived at `5679a5a` and merged locally as `55cac49`. Results mounts the actual `EndpointMapCard`; its AI-Lab stopgap is removed. Preserve observed/inferred/sourced/unknown facts separately and Agrim's actual nullable `AnswerStatus` contract. `EndpointMapIntegration` reads A5 company facts from the catalogue, not the interim owners file. Missing GeoLite data is unknown; no database was downloaded or copied. The legal context points to the official Act/notification, distinguishes condensed clauses from a full quote, warns about commencement and is not a finding of applicability. It does not invent a Section 3 corpus passage.

Voice recovery chooses an actually installed regional pack, provides a user-triggered download for supported missing packs, refreshes support after microphone approval and ignores stale support/answer completions. Offline TTS selection prefers quality before accent; actual audible quality still needs a person to check. Each chip routes to its explicit intent. Google is unchanged. Missing pack support does not silently switch to cloud recognition.

Consent waits now poll the armed window periodically as well as responding to accessibility events. Screenshot completions carry an arm generation so an earlier plan cannot inject evidence into a later one. One missed-screen retry is allowed only for controlled demos after an approved successful reset; both attempts and distinct readings remain saved. Missing Accept/Reject is qualified beside the score, in voice and reports. Home refreshes on entry, observes accessibility settings and rechecks the live preflight when tapped. See [bug-fix handoff](BUG_FIX_HANDOFF.md).

GeoLite licensing is conditional, not a blanket redistribution ban. MaxMind's [external display guidance](https://support.maxmind.com/knowledge-base/articles/sell-or-display-data-from-geolite-databases-and-web-services) permits external applications/display with attribution, subject to the [GeoLite EULA](https://www.maxmind.com/en/geolite/eula), including applicable sharing and database-update obligations. Keep the database outside Git while the team documents its specific distribution decision; do not assume a phone-copy instruction grants a licence. Country inference is not proof of physical server location, transfers or legal applicability.

## Phone acceptance checklist

- Install with `adb install -r` to preserve saved audits. Do not force-stop TraceVeil on this OEM: it previously disabled the accessibility watcher.
- Open an older real audit: history still loads, confidence explains missing coverage and Network Proof is unavailable.
- Open a synthetic sample: Sample labeling stays visible, capture confidence is unmeasured and Network Proof is unavailable.
- Open **Ask this audit**, test question chips and Back; test **Ask latest audit** on Home. Verify the answer belongs to the selected saved audit.
- Choose microphone input consciously. Verify denial does not prevent question chips, then approve through the phone's permission UI if desired. Never grant permission through ADB.
- Verify English/Hindi recognizer availability and offline voice availability. Record unavailable states honestly; do not activate a cloud recognizer or voice silently. Audible output and recognized speech require a human listening/speaking check.
- Run controlled demos with explicit fresh-state reset. For an agreed real app, keep reset and automatic tapping off, use Quick/manual choices, avoid private inputs and do not claim payload analysis or a clean result from missing evidence. Inspect each saved `captureHealth` and sidecar and compare measurements against saved values.
- Record screenshots, APK hash, device identity, any accounting discrepancy and whether Google/accessibility/Private DNS settings stayed unchanged. Separate this live evidence from JVM fixtures.
