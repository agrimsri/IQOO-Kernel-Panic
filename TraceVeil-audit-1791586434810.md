# TraceVeil report — Instagram (`com.instagram.android`)

**Score: 70/100 · Needs work**

- Audited: 10 Oct 2026, 04:23 · Plan C · No interaction, Plan B · Reject, Plan A · Accept
- Device: vivo I2501 · Android 16
- 0 bytes of audit data left this phone: capture, AI analysis and this report were produced on-device.

## Summary

| Check | Finding | Severity | Evidence | Penalty ceiling |
|---|---|---|---|---|
| C1 | Trackers contacted before consent | High | observed | Up to ₹50 crore (DPDP Schedule, residual) |
| NC | No consent mechanism detected | High | observed | Up to ₹50 crore (DPDP Schedule, residual) |

## Findings

### C1 · Trackers contacted before consent
*High · observed · confidence 100% · Plan C · No interaction, Plan B · Reject, Plan A · Accept*

The app contacted Meta before the user made any consent choice. Under the DPDP Act, personal data may be processed only with consent (or a legitimate use), and consent must be given by a clear affirmative action before processing starts.

**Evidence**
- z-p42.graph.instagram.com (Meta) — alias observed: z-p42.graph.instagram.com → z-p42-instagram.c10r.facebook.com — 1.2 s after launch, no consent was ever requested [Plan C · No interaction]
- z-p42.graph.instagram.com (Meta) — alias observed: z-p42.graph.instagram.com → z-p42-instagram.c10r.facebook.com — 1.7 s after launch, no consent was ever requested [Plan B · Reject]
- z-p42.graph.instagram.com (Meta) — alias observed: z-p42.graph.instagram.com → z-p42-instagram.c10r.facebook.com — 1.6 s after launch, no consent was ever requested [Plan A · Accept]

**DPDP basis**

> A person may process the personal data of a Data Principal only in accordance with the provisions of this Act and for a lawful purpose,— (a) for which the Data Principal has given her consent; or (b) for certain legitimate uses.
>
> — DPDP Act 2023, Section 4(1)

> The consent given by the Data Principal shall be free, specific, informed, unconditional and unambiguous with a clear affirmative action, and shall signify an agreement to the processing of her personal data for the specified purpose and be limited to such personal data as is necessary for such specified purpose.
>
> — DPDP Act 2023, Section 6(1)

### NC · No consent mechanism detected
*High · observed · confidence 100% · Plan C · No interaction, Plan B · Reject, Plan A · Accept*

No consent request or privacy notice was detected, yet the app contacted Meta. DPDP requires a notice (s.5) and valid consent (s.6), and the Data Fiduciary must be able to prove both (s.6(10)).

**Evidence**
- No consent screen detected in Plan C · No interaction, Plan B · Reject, Plan A · Accept.

**DPDP basis**

> Every request made to a Data Principal under section 6 for consent shall be accompanied or preceded by a notice given by the Data Fiduciary to the Data Principal, informing her,— (i) the personal data and the purpose for which the same is proposed to be processed; (ii) the manner in which she may exercise her rights under sub-section (4) of section 6 and section 13; and (iii) the manner in which the Data Principal may make a complaint to the Board, in such manner and as may be prescribed.
>
> — DPDP Act 2023, Section 5(1)

> The consent given by the Data Principal shall be free, specific, informed, unconditional and unambiguous with a clear affirmative action, and shall signify an agreement to the processing of her personal data for the specified purpose and be limited to such personal data as is necessary for such specified purpose.
>
> — DPDP Act 2023, Section 6(1)

> Where a consent given by the Data Principal is the basis of processing of personal data and a question arises in this regard in a proceeding, the Data Fiduciary shall be obliged to prove that a notice was given by her to the Data Principal and consent was given by such Data Principal to the Data Fiduciary in accordance with the provisions of this Act and the rules made thereunder.
>
> — DPDP Act 2023, Section 6(10)

## How to fix

### Meta (Facebook) SDK: delay initialisation and app events

AndroidManifest.xml:
```xml
<meta-data android:name="com.facebook.sdk.AutoInitEnabled" android:value="false" />
<meta-data android:name="com.facebook.sdk.AutoLogAppEventsEnabled" android:value="false" />
<meta-data android:name="com.facebook.sdk.AdvertiserIDCollectionEnabled" android:value="false" />
```

```kotlin
// After the user taps Accept:
FacebookSdk.setAutoInitEnabled(true)
FacebookSdk.fullyInitialize()
FacebookSdk.setAutoLogAppEventsEnabled(true)
FacebookSdk.setAdvertiserIDCollectionEnabled(true)
```

- If you call AppEventsLogger.activateApp(), make sure the SDK is initialised first — with auto-init off it is not.

Docs: https://developers.facebook.com/docs/app-events/gdpr-compliance

### Add a DPDP notice and consent screen on first launch

- Show the notice before initialising any non-essential SDK.
- List the personal data, the specific purposes, how to withdraw, and how to complain to the Data Protection Board.
- Record the user's choice with a timestamp (you must be able to prove it — s.6(10)).

## Network timeline

**Plan C · No interaction** — 11 lookups

| t (s) | Host |
|---|---|
| 1.1 | i.instagram.com |
| 1.1 | i.instagram.com |
| 1.2 | b.i.instagram.com |
| 1.2 | z-p42.graph.instagram.com |
| 1.3 | b.i.instagram.com |
| 2.2 | z-p42-static.cdninstagram.com |
| 2.5 | android.googleapis.com |
| 2.7 | lamssettings-pa.googleapis.com |
| 8.4 | chromesyncpasswords-pa.googleapis.com |
| 10.9 | mtalk.google.com |
| 30.4 | chromesyncentities-pa.googleapis.com |

**Plan B · Reject** — 7 lookups

| t (s) | Host |
|---|---|
| 1.1 | i.instagram.com |
| 1.2 | i.instagram.com |
| 1.3 | b.i.instagram.com |
| 1.3 | b.i.instagram.com |
| 1.7 | z-p42.graph.instagram.com |
| 2.3 | z-p42-static.cdninstagram.com |
| 7.5 | people-pa.googleapis.com |

**Plan A · Accept** — 11 lookups

| t (s) | Host |
|---|---|
| 1.1 | i.instagram.com |
| 1.2 | i.instagram.com |
| 1.2 | b.i.instagram.com |
| 1.3 | b.i.instagram.com |
| 1.6 | z-p42.graph.instagram.com |
| 2.5 | z-p42-static.cdninstagram.com |
| 2.6 | lamssettings-pa.googleapis.com |
| 2.6 | android.googleapis.com |
| 5.8 | chromesyncpasswords-pa.googleapis.com |
| 17.6 | www.googleapis.com |
| 18.7 | spot-pa.googleapis.com |

## Method and limitations
- Network evidence is DNS lookups made by the audited app (captured by a local, per-app VPN). A lookup shows the app tried to contact a server; payloads are not decrypted.
- *Observed* findings come from captured traffic or the screen's accessibility tree. *Inferred* findings come from SDK documentation or the on-device AI's reading of the screen, and count half in the score.
- Some processing may rely on a legitimate use (DPDP s.7) rather than consent; review findings in that light.
- Penalty ceilings are statutory maximums from the DPDP Act Schedule, not predicted fines.

---
*Automated technical assessment based on network and on-screen evidence captured on this device. This is not legal advice.*
