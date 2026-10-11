# Play label vs what this check saw

TraceVeil can set an app's Google Play **Data safety** label against the tracker hosts one audit actually looked up on the phone. The result is a short list of **points to review**, never a verdict, and it is **separate from the score**: the consent-readiness score, the findings and the CI policy result do not change. LeakyShop still scores 21 (leaky), 8 (sample) and 100 (fixed).

Why it is useful: Google's own help page says Play's review "is not designed to verify the accuracy and completeness of your data safety declarations". TraceVeil shows, on the tester's phone, what the app looked up next to what its label says.

## Where it appears

| Place | What it shows |
| --- | --- |
| Result screen, developer mode | Card **"Play Data safety label vs what we saw"**, under "Which SDK caused this?". Buttons: **Paste label text**, **Open a text file**, and **Use demo label (LeakyShop)** on LeakyShop and the sample audit only. With a label attached: what the label says, the numbered points to review (each with its caveat), what could not be compared, the vendor sources behind every SDK expectation, "About this check", and **Replace label** / **Remove label**. |
| Result screen, everyday mode | One line, only when a label is attached: "Compared with this app's Play Data safety label: 4 points to review. Developer mode shows the details." Nothing otherwise. |
| Report (Markdown, PDF and interactive HTML) | A section "Play Data safety label vs what this check saw": before "Method and limitations" in Markdown and PDF, before the footer in HTML (escaped, links as plain anchors, no request). |
| Audit data (JSON) | Optional top-level `playLabel` part of `traceveil.audit.v1` (label text, how it was read, and the comparison). Omitted when no label is attached, so older readers and bundles are unaffected. Backups keep the label and restores put it back. |
| TraceVeil CI | `traceveil-ci check bundle.json --play-label label.txt` prints the same points after the check report (text), or adds `playLabel` to `--format json`. It never changes the exit code. |

**No network.** TraceVeil never fetches the Play Store page or anything else for this check. The label comes only from the person (pasted, or a text file picked with Android's file picker), or from the built-in LeakyShop demo label. The trust screen's list of network paths is unchanged.

The label is stored as `play_label.json` in the audit's own folder on the phone. It leaves the phone only if the person exports the check.

## Method

1. **Read the label** (`core/.../datasafety/DataSafetyLabel.kt`, `DataSafetyParser`). Tolerant, English, offline. It reads the listing summary or the "See details" page, one statement per line or all on one line: "No data shared with third parties", "No data collected", "This app may share these data types with third parties", "This app may collect these data types", "Data shared", "Data collected", Play's category and data-type names, "and N others", and the security lines. A statement the text does not contain stays unknown. Text with no statement reads as **"could not read a label"**, never as "no data".
2. **Group the lookups** exactly as "Which SDK caused this?" does (`SdkAttribution`, `SessionClassifier`). Only catalogued third-party SDK kinds count (analytics, advertising, attribution, social, engagement, crash reporting). Services of the app's own company (cited `app_owners.json` record) are left out and named.
3. **What each SDK's documentation says it collects** (`core/src/main/resources/datasafety/sdk_expectations.json`). Each curated entry quotes the vendor page it was read from. Other catalogue SDKs get a softer expectation by kind, always marked **"catalogue expectation, unverified"**.
4. **Points to review** (`PlayLabelComparator`), collection first:

| Kind | When | Wording starts |
| --- | --- | --- |
| No collection vs SDKs | Label says "No data collected" and catalogued SDK hosts were looked up | "Label says no data collected, but this check saw lookups to …" |
| Type not declared | A category the contacted SDKs document is not declared as collected or shared | "Label does not list Device or other IDs among collected or shared data, but this check saw lookups to …" |
| No sharing vs third-party SDKs | Label says "No data shared with third parties" and advertising, attribution or social SDK hosts (or an SDK whose own page says it shares) were looked up | "Label says no data shared with third parties, while this check saw lookups to advertising or attribution SDK hosts: …" |
| Collected but not shared | Declared as collected, not as shared, and such third-party SDK hosts were looked up | "Label declares Device or other IDs as collected but not shared, while third-party SDK hosts were contacted: …" |
| Declared, not explained (for information) | Declared categories no contacted SDK documents | "Declared types the observed SDKs don't explain: …" |

Collection comes first because Play counts data an app's SDKs transmit as **collected**. Sharing comes second and is worded for review because Play does **not** count a transfer to a service provider as sharing.

Each point names when the SDK was first seen ("before consent", "after Reject", "after Accept"). "Not compared" notes say what was skipped and why (no sharing statement in the text, a summary cut short by "and N others", nothing catalogued seen).

## Demo: LeakyShop

The demo label (`core/src/main/resources/datasafety/leakyshop_demo_label.txt`) was written by the TraceVeil team. It is not a Play listing. It says "No data shared with third parties" and collects "App activity" only.

On the leaky LeakyShop audit (the device-shaped sessions in the core tests) it gives **4 points to review**:

1. Device or other IDs not listed: Google Analytics for Firebase, Meta (Facebook SDK), Firebase Crashlytics and AppsFlyer before consent, and Google Ads / AdMob and CleverTap after Accept (CleverTap: catalogue expectation, unverified).
2. Location not listed: Google Analytics for Firebase before consent, and Google Ads / AdMob after Accept.
3. App info and performance not listed: Firebase Crashlytics before consent, and Google Ads / AdMob after Accept.
4. "No data shared with third parties", while Meta (Facebook SDK) and AppsFlyer were looked up before consent and Google Ads / AdMob after Accept. Google's Mobile Ads SDK page says it "collects and shares" data types automatically. This point carries the service-provider caveat.

On the fixed build there are no points to review: no catalogued third-party SDK host was looked up. That does not show the label is complete.

## Sources (read 11 Oct 2026)

**Google Play Console Help, "Provide information for Google Play's Data safety section"**, https://support.google.com/googleplay/android-developer/answer/10787469
- "Google's review process is not designed to verify the accuracy and completeness of your data safety declarations."
- "You alone are responsible for making complete and accurate declarations in your app's store listing on Google Play."
- "Collect" means transmitting data from your app off a user's device. "This includes user data transmitted off device from your app by libraries and/or SDKs used in your app".
- "Sharing" refers to transferring user data collected from your app to a third party. It lists "Transferring data collected from your app off a user's device directly to a third party". Its exceptions include "Transferring user data to a "service provider" that processes it on behalf of the developer."
- "You must reflect data collection or sharing carried out by such third-party code in the Data safety form for your app."
- The page also lists the categories and data types the parser uses (Location: Approximate location, Precise location; … Device or other IDs).

**Play listing wording.** These generic strings were read on play.google.com/store/apps/datasafety pages: "No data shared with third parties", "The developer says this app doesn't share user data with other companies or organizations", "Data shared", "Data collected", "Data this app may collect", "Security practices", "Data is encrypted in transit", "You can request that data be deleted", and a "· Optional" suffix. "This app may share these data types with third parties", "This app may collect these data types" and "X, Y and 4 others" appear in listing text in web search results. The negative forms ("Data isn't encrypted", "Data can't be deleted") were not read on a live page, so the parser accepts several spellings.

**SDK documentation** (the mapping from vendor words to Play categories is TraceVeil's reading):

| SDK | Page | Quotes used |
| --- | --- | --- |
| Google Analytics for Firebase | Google Analytics Help, "Prepare for Google Play's data disclosure requirements", https://support.google.com/analytics/answer/11582702 | "Collects the Android Advertising ID"; "Collects screen views and sessions for use in Reporting and Analysis."; "Derives coarse location data from the masked IP addresses of the device."; "you are solely responsible for deciding how to respond to Google Play's Data safety section form" |
| Firebase Crashlytics | Firebase, https://firebase.google.com/docs/android/play-data-disclosure (page updated 2026-10-08) | "Collects stack traces when an application crashes."; "Generates and stores the Crashlytics installation UUID to measure the number of users impacted by a crash."; "Firebase does not transfer this data to third-parties except:" (subprocessors, the developer's instructions) |
| Google Ads / AdMob (Google Mobile Ads SDK) | https://developers.google.com/admob/android/privacy/play-data-disclosure (page updated 2026-10-07) | "collects and shares the following data types automatically for advertising, analytics, and fraud prevention purposes."; "Collects Android advertising (ad) ID"; "Collects user product interactions and interaction information, including app launch, taps, and video views."; "including app launch time, hang rate, and energy usage"; "Collects device's IP address, which may be used to estimate the general location of a device." |
| Meta (Facebook SDK), Meta Audience Network | Meta for Developers blog, 18 Jul 2022, https://developers.facebook.com/blog/post/2022/07/18/resources-for-completing-app-store-data-practice-questionnaires-apps-facebook-or-audience-network-sdk | "We may also receive and process the following information if your app is integrated with the Facebook SDK:" … "Device identifiers;"; "there are app events that are automatically logged (app installs, app launches, and in-app purchases)"; Audience Network: "Device identifiers;" and "Ad performance information, such as impressions, clicks, placement, and viewability." |
| AppsFlyer | AppsFlyer Android SDK reference, https://dev.appsflyer.com/hc/docs/android-sdk-reference-appsflyerlib | setDisableAdvertisingIdentifiers: "Disables collection of various Advertising IDs by the SDK." "This includes Google Advertising ID (GAID), OAID and Amazon Advertising ID (AAID)."; logEvent: "Log an in-app event." AppsFlyer's Data safety help article (support.appsflyer.com/hc/en-us/articles/4417197430289) returned HTTP 403 and is not used. |

The brief expected "App info and performance" for Google Analytics for Firebase. Google's page does not say that, so it is not used. Google's page does say coarse location, so Location is used.

**Consent mode.** Google's consent-mode page (https://developers.google.com/tag-platform/security/concepts/consent-mode, updated 2026-07-30) says that with `analytics_storage` denied the SDK "Won't read or write first-party analytics cookies or app identifiers", and that "Measurements without 3rd-party cookies (web) or signals (apps) will be sent to Google Analytics". An `app-measurement.com` lookup after Reject therefore does not by itself show identifiers. The wording stays "looked up a host".

**Context.** Arkalakis et al., "Abandon All Hope Ye Who Enter Here: A Dynamic, Longitudinal Investigation of Android's Data Safety Section", USENIX Security 2024 (https://www.usenix.org/conference/usenixsecurity24/presentation/arkalakis): "Our study on almost 5K applications uncovers a pervasive trend of incomplete disclosure", "81% misrepresent their data collection and sharing practices in the Data Safety section".

## Limits

- **A label is self-declared**, and it covers every use of the app; one audit covers a few minutes of launch, Accept, Reject and no interaction. An SDK not seen in this capture says nothing about the label.
- **SDK behaviour depends on configuration.** Vendors document defaults and switches (consent mode, disabled advertising ID, no automatic events). A documented type is what the SDK *can* collect, not what this app sent.
- **A DNS lookup is an attempt to contact a host**, not proof that data was sent. TraceVeil does not decrypt traffic.
- **Sharing has exceptions.** Transfers to service providers, for legal purposes, user-initiated transfers and fully anonymised data are not "sharing" under Play's definition. Sharing points are for review with the developer.
- **The catalogue is limited.** Hosts it does not list stay unknown and are never attributed, so "nothing to compare" never means "the label matches". Encrypted or hidden DNS can hide lookups (see V1).
- **Mapping is TraceVeil's reading.** Vendor pages use their own words; matching them to Play's categories is a judgement, shown with the quotes so a reviewer can check it.
- **English only.** The parser reads English Play text; other languages read as "could not read a label".
- **Points to review, not verdicts.** The wording never says false, lying, violation or illegal. Tests check this.

## Files

- `core/src/main/kotlin/com/dpdpxray/core/datasafety/`: `DataSafetyLabel.kt` (model, parser, demo label), `SdkExpectations.kt`, `PlayLabelCheck.kt` (comparator), `PlayLabelReport.kt` (record, export part, Markdown and CLI text)
- `core/src/main/resources/datasafety/`: `sdk_expectations.json`, `leakyshop_demo_label.txt`
- `app/src/main/java/com/dpdpxray/app/ui/screens/PlayLabelCard.kt`, `PlayLabelCopy.kt`; `app/.../data/PlayLabelFiles.kt`
- Tests: `core/src/test/kotlin/com/dpdpxray/core/datasafety/*`, `app/src/test/.../data/PlayLabelFilesTest.kt`, `app/src/test/.../ui/screens/PlayLabelCopyTest.kt`, `cli/src/test/.../PlayLabelCommandTest.kt`
