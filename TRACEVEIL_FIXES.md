# Traceveil fix plan: LeakyShop (`com.leakyshop.demo`)

Audit `cli-20261010T133639Z-6e283c35`, score **12** (AT_RISK). 8 issue(s), 10 fix(es).

## Instructions for the agent

- Work through `fixes` in order; each one lists the issues it resolves.
- Use `locate` to find the code: search the app's sources for the listed package prefixes and files.
- Apply `manifest` and `code` as shown; when `verified` is false, check the exact API for the SDK version in `docUrl`.
- Persist the user's consent choice and re-apply it on every launch; withdrawing must be as easy as giving consent.
- An issue is fixed when the `doneWhen` condition holds after re-running `verify.commands` on a debug build.
- Evidence is DNS lookups timed against consent choices: a lookup is an attempt to reach a host, not proof data was sent. This plan is a reading of the evidence, not a legal verdict.

## Tasks

### 1. [ ] Meta (Facebook) SDK: delay initialisation and app events (`facebook`)

Resolves: C2 (CRITICAL), C1 (HIGH), C10 (MEDIUM). Trackers: Meta (Facebook SDK)

Where: AndroidManifest.xml; the Application subclass (onCreate); build.gradle / build.gradle.kts dependencies. Search for imports of: `com.facebook.appevents`

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

### 2. [ ] Firebase Analytics: start collection only after consent (`firebase-analytics`)

Resolves: C2 (CRITICAL), C1 (HIGH), C10 (MEDIUM). Trackers: Google Analytics for Firebase

Where: AndroidManifest.xml; the Application subclass (onCreate); build.gradle / build.gradle.kts dependencies. Search for imports of: `com.google.firebase.analytics`, `com.google.android.gms.measurement`

AndroidManifest.xml:
```xml
<application>
    <meta-data
        android:name="firebase_analytics_collection_enabled"
        android:value="false" />
</application>
```

```kotlin
// After the user taps Accept for analytics:
Firebase.analytics.setAnalyticsCollectionEnabled(true)

// If the user refuses or withdraws:
Firebase.analytics.setAnalyticsCollectionEnabled(false)
```

- Use the manifest flag, not a runtime call in Application.onCreate — a runtime call races the automatic first_open event.
- Persist the user's choice and re-apply it on every launch.

Docs: https://firebase.google.com/docs/analytics/android/configure-data-collection

### 3. [ ] AppsFlyer: init, but start only after consent (`appsflyer`)

Resolves: C2 (CRITICAL), C1 (HIGH), C10 (MEDIUM). Trackers: AppsFlyer

Where: AndroidManifest.xml; the Application subclass (onCreate); build.gradle / build.gradle.kts dependencies. Search for imports of: `com.appsflyer`

*Not checked against the vendor docs: confirm the exact API for your SDK version.*

```kotlin
// Application.onCreate():
AppsFlyerLib.getInstance().init(DEV_KEY, null, this)

// After the user taps Accept:
AppsFlyerLib.getInstance().start(this)
```

- Do not call start() before consent; use stop(true, context) if consent is withdrawn.

Docs: https://dev.appsflyer.com/hc/docs/android-sdk-reference-appsflyerlib

### 4. [ ] Mixpanel: opt out of tracking by default (`mixpanel`)

Resolves: C2 (CRITICAL), C1 (HIGH). Trackers: Mixpanel

Where: AndroidManifest.xml; the Application subclass (onCreate); build.gradle / build.gradle.kts dependencies. Search for imports of: `com.mixpanel.android`

*Not checked against the vendor docs: confirm the exact API for your SDK version.*

```kotlin
val mixpanel = MixpanelAPI.getInstance(context, TOKEN, /* optOutTrackingDefault = */ true, /* trackAutomaticEvents = */ false)
// After the user taps Accept:
mixpanel.optInTracking()
```

Docs: https://docs.mixpanel.com/docs/tracking-methods/sdks/android

### 5. [ ] Amplitude: start opted out (`amplitude`)

Resolves: C1 (HIGH). Trackers: Amplitude

Where: AndroidManifest.xml; the Application subclass (onCreate); build.gradle / build.gradle.kts dependencies. Search for imports of: `com.amplitude`

*Not checked against the vendor docs: confirm the exact API for your SDK version.*

```kotlin
// Create Amplitude with optOut = true in its configuration, then after consent:
amplitude.configuration.optOut = false
```

- Confirm the configuration field for your Amplitude SDK version.

Docs: https://amplitude.com/docs/sdks/analytics/android/android-kotlin-sdk

### 6. [ ] Default every optional consent toggle to OFF (`ui-preticked`)

Resolves: C3 (HIGH)

Where: the consent / onboarding screen (layout or Compose code); string resources for the consent text

```kotlin
Checkbox(checked = analyticsConsent, onCheckedChange = { analyticsConsent = it }) // initial value: false
```

- Only the user's own tap may switch a consent option on (clear affirmative action, s.6(1)).

### 7. [ ] Make 'Reject' as easy as 'Accept' (`ui-reject`)

Resolves: C4 (HIGH)

Where: the consent / onboarding screen (layout or Compose code); string resources for the consent text

- Put a 'Reject' button on the first screen, with the same size and visual weight as 'Accept'.
- Do not hide refusal behind 'Manage' or extra screens.
- Withdrawal later must be equally easy (s.6(4)).

### 8. [ ] Itemise the data and state the purpose (`ui-notice`)

Resolves: C6 (MEDIUM)

Where: the consent / onboarding screen (layout or Compose code); string resources for the consent text

- List each personal data item (e.g. phone number, device ID, purchase history).
- State the specific purpose and the service it enables (Rule 3(b)).

### 9. [ ] Show how to withdraw consent, use rights and complain (`ui-withdraw`)

Resolves: C7 (MEDIUM)

Where: the consent / onboarding screen (layout or Compose code); string resources for the consent text

- Add 'You can withdraw consent anytime in Settings › Privacy' with a direct link.
- Explain how to exercise rights and how to complain to the Data Protection Board (Rule 3(c)).

### 10. [ ] Offer the notice in Indian languages (`ui-language`)

Resolves: C8 (LOW)

Where: the consent / onboarding screen (layout or Compose code); string resources for the consent text

- Add a language picker for the notice: English plus Hindi and your users' regional languages (Eighth Schedule, s.5(3)).

### 11. [ ] Trackers without a template

- **Microsoft Clarity** (imports: `com.microsoft.clarity`): No Traceveil template for this SDK yet. Initialise it only after the user accepts, keep it off after Reject or withdrawal, and look up the vendor's consent / opt-out API for your SDK version.

## Issues and where they apply

### TV-C2 CRITICAL: Tracker hosts looked up after the user refused

Trackers: Meta (Facebook SDK) (Meta, US), Google Analytics for Firebase (Google, US), AppsFlyer (IL), Mixpanel
Seen in runs: REJECT; evidence: OBSERVED. Fixed by: `facebook`, `firebase-analytics`, `appsflyer`, `mixpanel`

| Lens | Countries | Relation | Why |
|---|---|---|---|
| India | IN | REQUIRED | A refused consent gives no consent basis for processing (s.4(1), s.6(1)); a legitimate use (s.7) would have to apply instead. |
| EU / EEA | 30 countries | REQUIRED | After a refusal there is no consent, so non-essential storage or access on the device is not allowed (ePrivacy Art. 5(3)). [ePrivacy Directive 2002/58/EC, Article 5(3), as replaced by Directive 2009/136/EC, Article 2(5)](https://eur-lex.europa.eu/legal-content/EN/TXT/HTML/?uri=CELEX:32009L0136) [GDPR (EU) 2016/679, Article 4(11)](https://eur-lex.europa.eu/legal-content/EN/TXT/HTML/?uri=CELEX:32016R0679) [GDPR (EU) 2016/679, Recital 42](https://eur-lex.europa.eu/legal-content/EN/TXT/HTML/?uri=CELEX:32016R0679) |
| United Kingdom | GB | REQUIRED | After a refusal, non-exempt storage or access on the device has no consent behind it. [ICO guidance on storage and access technologies: What are the PECR rules? (quoting PECR reg. 6 as amended by the Data (Use and Access) Act 2025)](https://ico.org.uk/for-organisations/direct-marketing-and-privacy-and-electronic-communications/guidance-on-the-use-of-storage-and-access-technologies/what-are-the-pecr-rules/) [ICO guidance on storage and access technologies: What are the PECR rules?](https://ico.org.uk/for-organisations/direct-marketing-and-privacy-and-electronic-communications/guidance-on-the-use-of-storage-and-access-technologies/what-are-the-pecr-rules/) |
| California (US) | - | REQUIRED | If the refusal was the way offered to opt out of sale or sharing, the business must stop selling or sharing after it (§1798.120). A cookie-style banner alone is not an acceptable opt-out method (regs §7026(a)(4)). A lookup after refusal does not prove a sale or share. [Cal. Civ. Code §1798.120(a)(1)](https://cppa.ca.gov/pdf/20260101_ccpa_statute.pdf) [Cal. Civ. Code §1798.120(d)](https://cppa.ca.gov/pdf/20260101_ccpa_statute.pdf) [CCPA Regulations, 11 CCR §7026(a)(4)](https://cppa.ca.gov/regulations/pdf/ccpa_statute_eff_20260101.pdf) |
| US children (COPPA) | - | CONTEXT | Under COPPA the choice belongs to a parent, who may refuse further collection at any time (§312.6(a)(2)); a tap on Reject in the app is not the COPPA mechanism. [16 CFR §312.6(a)(2)](https://www.ecfr.gov/current/title-16/chapter-I/subchapter-C/part-312/section-312.6) |
| Brazil | BR | REQUIRED | Where consent is the legal basis, a refusal leaves none (Art. 7, I); any other basis claimed must be one of Art. 7's and respect the person's rights. [Lei nº 13.709/2018 (LGPD), Art. 7º, I and IX](https://www.planalto.gov.br/ccivil_03/_ato2015-2018/2018/lei/l13709.htm) [Lei nº 13.709/2018 (LGPD), Art. 5º, XII](https://www.planalto.gov.br/ccivil_03/_ato2015-2018/2018/lei/l13709.htm) |
| Singapore | SG | REQUIRED | A refusal means no consent was given, and deemed consent by notification does not hold once the person says they do not consent (s.15A(2)(b)); collection would need an exception (s.13). [Personal Data Protection Act 2012, section 13](https://sso.agc.gov.sg/Act/PDPA2012?ProvIds=P14-) [Personal Data Protection Act 2012, section 15A(2)](https://sso.agc.gov.sg/Act/PDPA2012?ProvIds=P14-) |
| Global baseline | - | REQUIRED | Good practice: a refusal keeps tracking off. TraceVeil consent-first baseline (good practice, not law) |

Done when: A fresh `traceveil audit` of com.leakyshop.demo reports no C2 finding.

### TV-C1 HIGH: Tracker hosts looked up before consent

Trackers: Amplitude (US), Mixpanel, Meta (Facebook SDK) (Meta, US), Google Analytics for Firebase (Google, US), Microsoft Clarity (US), AppsFlyer (IL)
Seen in runs: SILENT, REJECT, ACCEPT; evidence: OBSERVED. Fixed by: `amplitude`, `mixpanel`, `facebook`, `firebase-analytics`, `appsflyer`

| Lens | Countries | Relation | Why |
|---|---|---|---|
| India | IN | REQUIRED | Consent-based processing needs a clear affirmative action before it starts (s.4(1), s.6(1)). A legitimate use (s.7) may apply instead. |
| EU / EEA | 30 countries | REQUIRED | Storing or reading information on the device needs prior consent unless it is strictly necessary for the service the person asked for (ePrivacy Art. 5(3)). A tracker SDK that runs usually does this; the lookup alone does not prove it. [ePrivacy Directive 2002/58/EC, Article 5(3), as replaced by Directive 2009/136/EC, Article 2(5)](https://eur-lex.europa.eu/legal-content/EN/TXT/HTML/?uri=CELEX:32009L0136) [GDPR (EU) 2016/679, Article 4(11)](https://eur-lex.europa.eu/legal-content/EN/TXT/HTML/?uri=CELEX:32016R0679) [GDPR (EU) 2016/679, Recital 32](https://eur-lex.europa.eu/legal-content/EN/TXT/HTML/?uri=CELEX:32016R0679) |
| United Kingdom | GB | REQUIRED | PECR reg. 6 bars storing or reading information on the device without consent unless a Schedule A1 exception applies, and it covers SDKs in apps. Analytics used only for aggregate statistics can rely on an exception; advertising and attribution cannot. [ICO guidance on storage and access technologies: What are the PECR rules? (quoting PECR reg. 6 as amended by the Data (Use and Access) Act 2025)](https://ico.org.uk/for-organisations/direct-marketing-and-privacy-and-electronic-communications/guidance-on-the-use-of-storage-and-access-technologies/what-are-the-pecr-rules/) [ICO guidance on storage and access technologies: What are the PECR rules?](https://ico.org.uk/for-organisations/direct-marketing-and-privacy-and-electronic-communications/guidance-on-the-use-of-storage-and-access-technologies/what-are-the-pecr-rules/) [ICO guidance on storage and access technologies: What are the PECR rules?](https://ico.org.uk/for-organisations/direct-marketing-and-privacy-and-electronic-communications/guidance-on-the-use-of-storage-and-access-technologies/what-are-the-pecr-rules/) [ICO guidance on storage and access technologies: What are the exceptions?](https://ico.org.uk/for-organisations/direct-marketing-and-privacy-and-electronic-communications/guidance-on-the-use-of-storage-and-access-technologies/what-are-the-exceptions/) |
| California (US) | - | CONTEXT | Opt-out model: no prior consent is generally needed to collect adults' data, but the business must give notice at or before collection, let people opt out of sale or sharing at any time, and honour opt-out preference signals such as Global Privacy Control. Under-16s differ (see C9). [Cal. Civ. Code §1798.100(a)(1) (CCPA, effective 1 Jan 2026)](https://cppa.ca.gov/pdf/20260101_ccpa_statute.pdf) [Cal. Civ. Code §1798.120(a)(1)](https://cppa.ca.gov/pdf/20260101_ccpa_statute.pdf) [CCPA Regulations, 11 CCR §7025(a)-(b)](https://cppa.ca.gov/regulations/pdf/ccpa_statute_eff_20260101.pdf) |
| US children (COPPA) | - | CONTEXT | COPPA applies only if the app is directed to children under 13 or the operator knows it collects from such a child. Then persistent identifiers are personal information and need verifiable parental consent first, unless used only for internal operations (see C9). [16 CFR §312.3](https://www.ecfr.gov/current/title-16/chapter-I/subchapter-C/part-312/section-312.3) [16 CFR §312.5(a)(1)-(2)](https://www.ecfr.gov/current/title-16/chapter-I/subchapter-C/part-312/section-312.5) [16 CFR §312.2, definition of personal information, paragraph (7)](https://www.ecfr.gov/current/title-16/chapter-I/subchapter-C/part-312/section-312.2) |
| Brazil | BR | CONTEXT | Consent is one of several legal bases, and legitimate interest can apply (Art. 7, IX). Where the app relies on consent, it must come first and be free, informed and unequivocal (Art. 5, XII). [Lei nº 13.709/2018 (LGPD), Art. 7º, I and IX](https://www.planalto.gov.br/ccivil_03/_ato2015-2018/2018/lei/l13709.htm) [Lei nº 13.709/2018 (LGPD), Art. 5º, XII](https://www.planalto.gov.br/ccivil_03/_ato2015-2018/2018/lei/l13709.htm) |
| Singapore | SG | CONTEXT | Collection needs consent, deemed consent or an exception (s.13). Deemed consent by notification (s.15A) and the Act's exceptions can apply without an opt-in, so a prior opt-in is not always required. [Personal Data Protection Act 2012, section 13](https://sso.agc.gov.sg/Act/PDPA2012?ProvIds=P14-) [Personal Data Protection Act 2012, section 15A(2)](https://sso.agc.gov.sg/Act/PDPA2012?ProvIds=P14-) |
| Global baseline | - | REQUIRED | Consent-first good practice: start tracking only after an opt-in. TraceVeil consent-first baseline (good practice, not law) |

Done when: A fresh `traceveil audit` of com.leakyshop.demo reports no C1 finding.

### TV-C3 HIGH: Pre-ticked consent option

Seen in runs: SILENT, REJECT, ACCEPT; evidence: OBSERVED. Fixed by: `ui-preticked`

| Lens | Countries | Relation | Why |
|---|---|---|---|
| India | IN | REQUIRED | Consent must be a clear affirmative action (s.6(1)); an option switched on in advance is not one. |
| EU / EEA | 30 countries | REQUIRED | Consent needs a clear affirmative action (Art. 4(11)); pre-ticked boxes do not count (Recital 32). [GDPR (EU) 2016/679, Article 4(11)](https://eur-lex.europa.eu/legal-content/EN/TXT/HTML/?uri=CELEX:32016R0679) [GDPR (EU) 2016/679, Recital 32](https://eur-lex.europa.eu/legal-content/EN/TXT/HTML/?uri=CELEX:32016R0679) |
| United Kingdom | GB | REQUIRED | Consent needs a clear and positive action; the ICO says a pre-ticked box is not valid consent. [ICO guidance on storage and access technologies: What are the PECR rules?](https://ico.org.uk/for-organisations/direct-marketing-and-privacy-and-electronic-communications/guidance-on-the-use-of-storage-and-access-technologies/what-are-the-pecr-rules/) [ICO guidance on storage and access technologies: What are the PECR rules? (quoting UK GDPR Article 4(11))](https://ico.org.uk/for-organisations/direct-marketing-and-privacy-and-electronic-communications/guidance-on-the-use-of-storage-and-access-technologies/what-are-the-pecr-rules/) |
| California (US) | - | CONTEXT | Consent is required only in some cases (for example opting a 13 to 15-year-old in, or opting back in). There, silence or failure to act is not consent, and agreement obtained through dark patterns is not consent. [CCPA Regulations, 11 CCR §7004(a)(3)](https://cppa.ca.gov/regulations/pdf/ccpa_statute_eff_20260101.pdf) [Cal. Civ. Code §1798.140(h)](https://cppa.ca.gov/pdf/20260101_ccpa_statute.pdf) |
| US children (COPPA) | - | NOT_APPLICABLE | COPPA consent comes from a parent through a verifiable method, not from a consent banner; banner design is not what COPPA tests. |
| Brazil | BR | REQUIRED | Where consent is the basis, it must be given by a means that shows the person's will (Art. 8) and be unequivocal (Art. 5, XII); an option switched on in advance does not show it. [Lei nº 13.709/2018 (LGPD), Art. 8º](https://www.planalto.gov.br/ccivil_03/_ato2015-2018/2018/lei/l13709.htm) [Lei nº 13.709/2018 (LGPD), Art. 5º, XII](https://www.planalto.gov.br/ccivil_03/_ato2015-2018/2018/lei/l13709.htm) |
| Singapore | SG | CONTEXT | The PDPA does not mention pre-ticked options; consent obtained through deceptive or misleading practices is not validly given (s.14(2)-(3)). [Personal Data Protection Act 2012, section 14(2)-(3)](https://sso.agc.gov.sg/Act/PDPA2012?ProvIds=P14-) |
| Global baseline | - | REQUIRED | Good practice: optional choices start switched off. TraceVeil consent-first baseline (good practice, not law) |

Done when: A fresh `traceveil audit` of com.leakyshop.demo reports no C3 finding.

### TV-C4 HIGH: No clear way to refuse

Seen in runs: SILENT, REJECT, ACCEPT; evidence: OBSERVED. Fixed by: `ui-reject`

| Lens | Countries | Relation | Why |
|---|---|---|---|
| India | IN | REQUIRED | Consent must be free (s.6(1)), and withdrawing it must be as easy as giving it (s.6(4)). |
| EU / EEA | 30 countries | REQUIRED | Consent must be freely given: people must be able to refuse without detriment (Recital 42), and withdrawing must be as easy as giving (Art. 7(3)). [GDPR (EU) 2016/679, Article 4(11)](https://eur-lex.europa.eu/legal-content/EN/TXT/HTML/?uri=CELEX:32016R0679) [GDPR (EU) 2016/679, Recital 42](https://eur-lex.europa.eu/legal-content/EN/TXT/HTML/?uri=CELEX:32016R0679) [GDPR (EU) 2016/679, Article 7(3)](https://eur-lex.europa.eu/legal-content/EN/TXT/HTML/?uri=CELEX:32016R0679) |
| United Kingdom | GB | REQUIRED | People must be able to refuse non-exempt technologies as easily as they accept them (ICO). [ICO guidance on storage and access technologies: What are the PECR rules?](https://ico.org.uk/for-organisations/direct-marketing-and-privacy-and-electronic-communications/guidance-on-the-use-of-storage-and-access-technologies/what-are-the-pecr-rules/) |
| California (US) | - | CONTEXT | Where a banner seeks consent, the more privacy-protective choice must be no harder than the other: only "Accept All" and "Preferences" is not symmetrical (regs §7004(a)(2)). [CCPA Regulations, 11 CCR §7004(a)(2) (effective 1 Jan 2026)](https://cppa.ca.gov/regulations/pdf/ccpa_statute_eff_20260101.pdf) |
| US children (COPPA) | - | NOT_APPLICABLE | COPPA consent comes from a parent through a verifiable method, not from a consent banner; banner design is not what COPPA tests. |
| Brazil | BR | CONTEXT | Consent must be free (Art. 5, XII); the LGPD does not spell out how a refusal option must look. [Lei nº 13.709/2018 (LGPD), Art. 5º, XII](https://www.planalto.gov.br/ccivil_03/_ato2015-2018/2018/lei/l13709.htm) |
| Singapore | SG | CONTEXT | The PDPA sets no rule on the refusal option; consent must not be obtained through deceptive or misleading practices (s.14(2)(b)). [Personal Data Protection Act 2012, section 14(2)-(3)](https://sso.agc.gov.sg/Act/PDPA2012?ProvIds=P14-) |
| Global baseline | - | REQUIRED | Good practice: refusing is as easy and as visible as accepting. TraceVeil consent-first baseline (good practice, not law) |

Done when: A fresh `traceveil audit` of com.leakyshop.demo reports no C4 finding.

### TV-C6 MEDIUM: Notice lacks itemised data or purpose

Seen in runs: SILENT, REJECT, ACCEPT; evidence: INFERRED. Fixed by: `ui-notice`

| Lens | Countries | Relation | Why |
|---|---|---|---|
| India | IN | REQUIRED | The notice must itemise the personal data and state the specified purpose (s.5(1), Rule 3(b)). |
| EU / EEA | 30 countries | REQUIRED | People must get clear and comprehensive information about the purposes (ePrivacy Art. 5(3), GDPR Art. 13(1)(c)); EDPB guidance also lists the type of data among the minimum information for informed consent. [GDPR (EU) 2016/679, Article 13(1)(c)](https://eur-lex.europa.eu/legal-content/EN/TXT/HTML/?uri=CELEX:32016R0679) [EDPB Guidelines 05/2020 on consent, version 1.1, paragraph 64](https://www.edpb.europa.eu/system/files/documents/files/file1/edpb_guidelines_202005_consent_en.pdf) [ePrivacy Directive 2002/58/EC, Article 5(3), as replaced by Directive 2009/136/EC, Article 2(5)](https://eur-lex.europa.eu/legal-content/EN/TXT/HTML/?uri=CELEX:32009L0136) |
| United Kingdom | GB | REQUIRED | People must be told what the technologies are, their purposes, any third parties and how long they last (ICO guidance on reg. 6). [ICO guidance on storage and access technologies: What are the PECR rules?](https://ico.org.uk/for-organisations/direct-marketing-and-privacy-and-electronic-communications/guidance-on-the-use-of-storage-and-access-technologies/what-are-the-pecr-rules/) [ICO guidance on storage and access technologies: What are the PECR rules? (quoting PECR reg. 6 as amended by the Data (Use and Access) Act 2025)](https://ico.org.uk/for-organisations/direct-marketing-and-privacy-and-electronic-communications/guidance-on-the-use-of-storage-and-access-technologies/what-are-the-pecr-rules/) |
| California (US) | - | CONTEXT | A notice at or before collection must list the categories of personal information, the purposes and whether it is sold or shared (§1798.100(a)(1)). An app may give it through a link on its download page and in its settings, so the consent screen need not carry it. [Cal. Civ. Code §1798.100(a)(1) (CCPA, effective 1 Jan 2026)](https://cppa.ca.gov/pdf/20260101_ccpa_statute.pdf) [CCPA Regulations, 11 CCR §7012(c)(3)](https://cppa.ca.gov/regulations/pdf/ccpa_statute_eff_20260101.pdf) |
| US children (COPPA) | - | CONTEXT | Operators must give notice of what they collect from children, how they use it and their disclosure practices (§312.3(a), §312.4); the in-app consent screen is not the only place for it. [16 CFR §312.3](https://www.ecfr.gov/current/title-16/chapter-I/subchapter-C/part-312/section-312.3) |
| Brazil | BR | REQUIRED | People are entitled to clear, adequate and prominent information on the specific purpose (Art. 9, I), and consent is void if this was not presented beforehand clearly (Art. 9, §1). The LGPD does not require a list of data items here. [Lei nº 13.709/2018 (LGPD), Art. 9º, I and VII](https://www.planalto.gov.br/ccivil_03/_ato2015-2018/2018/lei/l13709.htm) [Lei nº 13.709/2018 (LGPD), Art. 9º, § 1º](https://www.planalto.gov.br/ccivil_03/_ato2015-2018/2018/lei/l13709.htm) |
| Singapore | SG | REQUIRED | Consent counts only if the person was told the purposes on or before collection (s.14(1), s.20(1)(a)). The Act does not require a list of data items. [Personal Data Protection Act 2012, section 14(1)](https://sso.agc.gov.sg/Act/PDPA2012?ProvIds=P14-) [Personal Data Protection Act 2012, section 20(1)(a)](https://sso.agc.gov.sg/Act/PDPA2012?ProvIds=P14-) |
| Global baseline | - | REQUIRED | Good practice: say which data, for which purposes and who receives it, before collecting. TraceVeil consent-first baseline (good practice, not law) |

Done when: A fresh `traceveil audit` of com.leakyshop.demo reports no C6 finding.

### TV-C7 MEDIUM: No visible way to withdraw consent

Seen in runs: SILENT, REJECT, ACCEPT; evidence: INFERRED. Fixed by: `ui-withdraw`

| Lens | Countries | Relation | Why |
|---|---|---|---|
| India | IN | REQUIRED | The notice must say how to withdraw consent, exercise rights and complain to the Data Protection Board (s.5(1), s.6(4), Rule 3(c)). |
| EU / EEA | 30 countries | REQUIRED | People must be told before consenting that they can withdraw at any time, and withdrawing must be as easy as consenting (Art. 7(3), Art. 13(2)(c)). [GDPR (EU) 2016/679, Article 7(3)](https://eur-lex.europa.eu/legal-content/EN/TXT/HTML/?uri=CELEX:32016R0679) [GDPR (EU) 2016/679, Article 13(2)(c)](https://eur-lex.europa.eu/legal-content/EN/TXT/HTML/?uri=CELEX:32016R0679) |
| United Kingdom | GB | REQUIRED | People must be able to withdraw consent at any time as easily as they gave it, and be told how (ICO). [ICO guidance on storage and access technologies: How do we manage consent in practice?](https://ico.org.uk/for-organisations/direct-marketing-and-privacy-and-electronic-communications/guidance-on-the-use-of-storage-and-access-technologies/how-do-we-manage-consent-in-practice/) |
| California (US) | - | CONTEXT | California's counterpart is the right to opt out of sale or sharing at any time, through a clear "Do Not Sell or Share My Personal Information" link or an opt-out preference signal; it need not be on the consent screen. [Cal. Civ. Code §1798.120(a)(1)](https://cppa.ca.gov/pdf/20260101_ccpa_statute.pdf) [Cal. Civ. Code §1798.135(a)(1)](https://cppa.ca.gov/pdf/20260101_ccpa_statute.pdf) |
| US children (COPPA) | - | CONTEXT | Parents can refuse further use or collection at any time and ask for deletion (§312.6(a)(2)). [16 CFR §312.6(a)(2)](https://www.ecfr.gov/current/title-16/chapter-I/subchapter-C/part-312/section-312.6) |
| Brazil | BR | REQUIRED | Consent can be revoked at any time through a free and easy procedure (Art. 8, §5), and information must mention the person's rights (Art. 9, VII). [Lei nº 13.709/2018 (LGPD), Art. 8º, § 5º](https://www.planalto.gov.br/ccivil_03/_ato2015-2018/2018/lei/l13709.htm) [Lei nº 13.709/2018 (LGPD), Art. 9º, I and VII](https://www.planalto.gov.br/ccivil_03/_ato2015-2018/2018/lei/l13709.htm) |
| Singapore | SG | CONTEXT | People may withdraw consent at any time on reasonable notice, and the organisation must then stop (s.16); the Act does not require the consent screen itself to explain withdrawal. [Personal Data Protection Act 2012, section 16(1) and (4)](https://sso.agc.gov.sg/Act/PDPA2012?ProvIds=P14-) |
| Global baseline | - | REQUIRED | Good practice: tell people how to change or withdraw their choice. TraceVeil consent-first baseline (good practice, not law) |

Done when: A fresh `traceveil audit` of com.leakyshop.demo reports no C7 finding.

### TV-C10 MEDIUM: Device identifiers likely shared before consent

Trackers: Meta (Facebook SDK) (Meta, US), Google Analytics for Firebase (Google, US), AppsFlyer (IL)
Seen in runs: SILENT, REJECT, ACCEPT; evidence: INFERRED. Fixed by: `facebook`, `firebase-analytics`, `appsflyer`

| Lens | Countries | Relation | Why |
|---|---|---|---|
| India | IN | REQUIRED | Identifiers read before a consent choice would be processed without the consent s.6(1) describes. Inferred from SDK documentation. |
| EU / EEA | 30 countries | REQUIRED | Reading a device or advertising identifier is gaining access to information stored on the device, which needs prior consent unless strictly necessary (ePrivacy Art. 5(3)). Inferred from SDK documentation. [ePrivacy Directive 2002/58/EC, Article 5(3), as replaced by Directive 2009/136/EC, Article 2(5)](https://eur-lex.europa.eu/legal-content/EN/TXT/HTML/?uri=CELEX:32009L0136) |
| United Kingdom | GB | REQUIRED | Reading device or advertising identifiers is accessing information stored on the device; it needs consent unless an exception applies (reg. 6). Inferred from SDK documentation. [ICO guidance on storage and access technologies: What are the PECR rules? (quoting PECR reg. 6 as amended by the Data (Use and Access) Act 2025)](https://ico.org.uk/for-organisations/direct-marketing-and-privacy-and-electronic-communications/guidance-on-the-use-of-storage-and-access-technologies/what-are-the-pecr-rules/) [ICO guidance on storage and access technologies: What are the PECR rules?](https://ico.org.uk/for-organisations/direct-marketing-and-privacy-and-electronic-communications/guidance-on-the-use-of-storage-and-access-technologies/what-are-the-pecr-rules/) |
| California (US) | - | CONTEXT | Device and advertising identifiers are personal information; disclosing them for cross-context behavioural advertising is sharing, which adults can opt out of. Prior consent is not generally required for adults. [Cal. Civ. Code §1798.140(aj)](https://cppa.ca.gov/pdf/20260101_ccpa_statute.pdf) [Cal. Civ. Code §1798.140(ah)(1)](https://cppa.ca.gov/pdf/20260101_ccpa_statute.pdf) |
| US children (COPPA) | - | CONTEXT | Device identifiers are personal information under COPPA; this matters for child-directed apps or actual knowledge of a child under 13 (see C9). [16 CFR §312.2, definition of personal information, paragraph (7)](https://www.ecfr.gov/current/title-16/chapter-I/subchapter-C/part-312/section-312.2) [16 CFR §312.5(c)(7)](https://www.ecfr.gov/current/title-16/chapter-I/subchapter-C/part-312/section-312.5) |
| Brazil | BR | CONTEXT | Identifiers linked to a person are personal data; the LGPD needs a legal basis for them, not necessarily consent (Art. 7). [Lei nº 13.709/2018 (LGPD), Art. 7º, I and IX](https://www.planalto.gov.br/ccivil_03/_ato2015-2018/2018/lei/l13709.htm) |
| Singapore | SG | CONTEXT | Identifiers linked to a person are personal data; collecting them needs consent, deemed consent or an exception (s.13). [Personal Data Protection Act 2012, section 13](https://sso.agc.gov.sg/Act/PDPA2012?ProvIds=P14-) |
| Global baseline | - | REQUIRED | Good practice: identifiers wait for an opt-in too. TraceVeil consent-first baseline (good practice, not law) |

Done when: A fresh `traceveil audit` of com.leakyshop.demo reports no C10 finding.

### TV-C8 LOW: Notice offered only in English

Seen in runs: SILENT, REJECT, ACCEPT; evidence: INFERRED. Fixed by: `ui-language`

| Lens | Countries | Relation | Why |
|---|---|---|---|
| India | IN | REQUIRED | People must be able to read the notice and the consent request in English or any Eighth Schedule language (s.5(3), s.6(3)). |
| EU / EEA | 30 countries | CONTEXT | The GDPR names no language, but information must be intelligible and in clear and plain language (Art. 12(1)); an English-only notice may not be intelligible to people who do not read English. [GDPR (EU) 2016/679, Article 12(1)](https://eur-lex.europa.eu/legal-content/EN/TXT/HTML/?uri=CELEX:32016R0679) |
| United Kingdom | GB | NOT_APPLICABLE | The UK GDPR names no language for notices; an English-only notice does not test a UK requirement. |
| California (US) | - | CONTEXT | Notices must be available in the languages the business ordinarily uses with California consumers; English alone can be enough. [CCPA Regulations, 11 CCR §7003(b)(2)](https://cppa.ca.gov/regulations/pdf/ccpa_statute_eff_20260101.pdf) |
| US children (COPPA) | - | NOT_APPLICABLE | COPPA sets no language requirement that this check tests. |
| Brazil | BR | CONTEXT | The LGPD names no language; information must be clear and adequate for the person (Art. 9). [Lei nº 13.709/2018 (LGPD), Art. 9º, I and VII](https://www.planalto.gov.br/ccivil_03/_ato2015-2018/2018/lei/l13709.htm) |
| Singapore | SG | NOT_APPLICABLE | The PDPA names no language for notices; an English-only notice does not test a PDPA requirement. |
| Global baseline | - | CONTEXT | Good practice where your audience reads other languages; an English-only notice suits an English-reading audience. TraceVeil consent-first baseline (good practice, not law) |

Done when: A fresh `traceveil audit` of com.leakyshop.demo reports no C8 finding.

## Verify

```
traceveil audit --package com.leakyshop.demo --out traceveil-after.json
```
```
traceveil fix --report traceveil-after.json
```

None of the issue checks above is reported again; the policy lens score rises.

*Same findings and severities under every lens; a lens only marks which ones each law requires. A reading of the evidence, not a legal verdict.*

