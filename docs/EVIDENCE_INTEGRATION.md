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

N4 rights-notice/alias UI, Y3/Y4 map mounting, N7 comparison and Camera Check removal remain separate tasks. The pre-existing report-body zero-byte sentence must be corrected by its owner before it is used as a measured privacy claim. Jovi's presence in Office Kit is not evidence of an embeddable Android model/API.

Y3's local handoff is in the user-provided `Y3_ENDPOINT_MAP.md`. Its source was not on the published `yashvi/phone-experience` branch at `39c915a` when checked. Publish before mounting; no guessed `EndpointMapCard` import belongs in this checkpoint. Preserve observed/inferred/sourced/unknown facts separately and use Agrim's actual nullable `AnswerStatus` contract. Headquarters research should coordinate with A5 to avoid conflicting company mappings. The existing passage corpus has no Section 3 entry; any quoted scope passage needs a verified official source.

GeoLite licensing is conditional, not a blanket redistribution ban. MaxMind's [external display guidance](https://support.maxmind.com/knowledge-base/articles/sell-or-display-data-from-geolite-databases-and-web-services) permits external applications/display with attribution, subject to the [GeoLite EULA](https://www.maxmind.com/en/geolite/eula), including applicable sharing and database-update obligations. Keep the database outside Git while the team documents its specific distribution decision; do not assume a phone-copy instruction grants a licence. Country inference is not proof of physical server location, transfers or legal applicability.

## Phone acceptance checklist

- Install with `adb install -r` to preserve saved audits. Do not force-stop TraceVeil on this OEM: it previously disabled the accessibility watcher.
- Open an older real audit: history still loads, confidence explains missing coverage and Network Proof is unavailable.
- Open a synthetic sample: Sample labeling stays visible, capture confidence is unmeasured and Network Proof is unavailable.
- Open **Ask this audit**, test question chips and Back; test **Ask latest audit** on Home. Verify the answer belongs to the selected saved audit.
- Choose microphone input consciously. Verify denial does not prevent question chips, then approve through the phone's permission UI if desired. Never grant permission through ADB.
- Verify English/Hindi recognizer availability and offline voice availability. Record unavailable states honestly; do not activate a cloud recognizer or voice silently. Audible output and recognized speech require a human listening/speaking check.
- Run only a controlled developer-owned demo: existing Shizuku fresh-state resets may clear the target's data. Inspect every plan's saved `captureHealth` and corresponding sidecar entry, and compare displayed measurements against the saved values.
- Record screenshots, APK hash, device identity, any accounting discrepancy and whether Google/accessibility/Private DNS settings stayed unchanged. Separate this live evidence from JVM fixtures.
