# Attribution for the endpoint map (Y3)

Entries for the material the Source country / DNS and Owner / legal authority modes use. The format follows `ATTRIBUTIONS.md` on `nitya/repo-readiness`; these rows should be merged into that register. **Status: used at Yashvi's direction, not yet recorded in the team register. Nitya's reviewed decision is still pending** under the register's gate for new external material.

## Data and assets used

| Component | Location | Source and licence | Required handling |
| --- | --- | --- | --- |
| MaxMind GeoLite2 Country database | **Not in this repository or the APK.** Read from `GeoLite2-Country.mmdb` in the app's `files/geo/` folder on the phone | © MaxMind, Inc. [GeoLite End User License Agreement](https://www.maxmind.com/en/geolite/eula) (read 10 October 2026). Copyrightable elements are governed by CC BY-SA 4.0; separately, a limited internal-use licence (§3) | See "GeoLite2 terms, version and distribution" below. |
| `world.svg` world outline | `core/src/main/resources/geo/world.svg`, copied byte-for-byte (SHA-256 `fc268e8f…79d7ef`) | Used by TrackerControl via [takuseno/GeoMap](https://github.com/takuseno/GeoMap), whose README states it uses `world.svg` under **CC BY-NC 4.0**. The file's own metadata names amCharts' *ammap* as its origin; the original amCharts licence text was **not** independently read | Credit: map outline from amCharts via GeoMap by Takuma Seno, CC BY-NC 4.0. **Non-commercial only**: acceptable for the hackathon, not for a commercial release. Keep the file unmodified. |
| DPDP Act 2023, section 3 | Quoted in `LegalContext.DPDP_INDIA` (`core/.../geo/MapPresenter.kt`) | *The Digital Personal Data Protection Act, 2023*, Gazette of India Extraordinary, 11 August 2023 (`team_downloads/legal/DPDP_Act_2023.pdf`) | Quoted verbatim from section 3(a) and 3(b). Shown as legal context, not as a finding that the Act applies. Not legal advice. |
| Company statements | `core/src/main/resources/geo/owners.json` | Short factual statements quoted from each company's own published documents; each entry carries its URL, the exact quote and the date it was read (see "Ownership research") | Keep the URL and quote with every claim. Re-read before relying on an entry after 10 October 2026. |

## GeoLite2 terms, version and distribution

What the EULA says (§3, §5, §6, read from the raw page text on 10 October 2026), stated precisely because an earlier draft of this file overstated it:

- **Attribution is required** (§3). MaxMind's own example wording is *"This product includes GeoLite Data created by MaxMind, available from https://www.maxmind.com"*. TraceVeil's card prints exactly that whenever the database is used.
- **Use inside an application and display of results are permitted with that attribution**, subject to the agreement's other terms (§3): the copyrightable elements are under CC BY-SA 4.0, and a non-exclusive, non-transferable licence covers use for your own internal business purposes. Do not remove or obscure notices in the data.
- **Giving the database to a third party is conditional, not banned** (§6). Except as the Creative Commons licence explicitly permits, it needs MaxMind's prior written consent; where it is allowed, each recipient must be bound by the same or substantially similar duties (§3, §6, the DPA) and you remain responsible for them. Whether the `.mmdb` file as a whole falls under the CC BY-SA 4.0 grant is a legal question this project has not settled.
- **Update and destruction duty** (§6). When MaxMind releases an updated database you agree to promptly use it, and to stop using and destroy older versions within 30 days of that release, and everything on termination. The card therefore shows the database build date and notes when it is more than 30 days old.
- **No identifying households, individuals or street addresses** (§5), and MaxMind does not guarantee accuracy. TraceVeil shows countries only, labelled as inferred.

Version in use: `GeoLite2-Country`, build epoch `1790694124` (2026-09-29), 8,423,789 bytes, SHA-256 `ff539785596f72ac2a07048f08506b38134bcf3b568182fd65f4534817bf91d7`. It came from the TrackerControl checkout the team supplied, not from a MaxMind download under this project's own account; each person should download their own copy under their own MaxMind account.

**Distribution decision (10 October 2026, Yashvi): keep the database out of Git for now**, although the repository is private. Reasons: the 30-day destruction duty cannot be met for a file preserved in Git history and clones, and sharing with teammates or evaluators is a disclosure that needs the conditions above. Each user puts their copy on their own phone (`adb push GeoLite2-Country.mmdb /sdcard/Android/data/com.dpdpxray.app/files/geo/`). Revisit with Nitya if the team wants to ship it; that needs the §6 conditions met or MaxMind's written consent.

## Ownership research (read-only, 10 October 2026)

Scope: the four companies behind the six catalogue entities that appear in the demo and sample audits. Preference order: company document, then public registry, then a labelled community database. No registry or community source was needed.

| Company | Entities | Source | Result |
| --- | --- | --- | --- |
| Google LLC | Google Analytics for Firebase, Google Ads / AdMob, Firebase Crashlytics | [Google Terms of Service](https://policies.google.com/terms) | States Delaware organisation and a Mountain View address. Product-specific terms were not read and may name other Google entities. |
| Meta Platforms, Inc. | Meta (Facebook SDK) | [Meta Platform Terms](https://developers.facebook.com/terms/) | States a Menlo Park address as a party in the data-transfer clause. Incorporation not stated. |
| AppsFlyer Ltd. | AppsFlyer | [AppsFlyer Terms of Use](https://www.appsflyer.com/legal/terms-of-use/) | **Three** contracting entities by customer country (US/Canada: San Francisco; UK/France and others: London; all other countries: Herzliya, Israel). The recorded location is Israel with this caveat. |
| CleverTap | CleverTap | [CleverTap privacy policy](https://clevertap.com/privacy-policy/) | Three entities (US, India, Singapore) and no headquarters named. **Left unresolved**, with the reason recorded. |

No source describes its address as a headquarters, so each location is recorded as the company's *stated address* and the screen says so. The other catalogue entities were not researched and have no entry.

**Coordination with A5 (Agrim):** there must be one ownership mapping. `owners.json` is the interim one and is keyed by catalogue entity name, like `trackers.json`. When A5 adds `hqCountry` and `hqSource` to `trackers.json`, those fields should adopt this file's rules: a cited https source, a source kind, a date, the source's quote, and a basis that distinguishes "described as headquarters" from "stated address". The registry loader can then read either, and this file can be retired. Until then nothing here changes `trackers.json`.

## Looked at and not used

| Source | Why not |
| --- | --- |
| TrackerControl code, UI and databases | GPL-3.0. Only the layout idea (heading, one-sentence explanation, then the content with loading and failed states) was followed. No code, string, colour value or database was copied. Its GeoLite2 file and `world.svg` were inspected in the local checkout; the SVG is the only item copied, as above. |
| Exodus Privacy data inside TrackerControl (`trackers.json`) | Only 4 of 36 TraceVeil vendors have a "Primary Location" line, and two name a former company (Crashlytics: Boston; DoubleClick: New York). Not reliable for ownership, so not used. |
| DB-IP "IP to Country Lite" | CC BY 4.0, CSV, reduced accuracy. Evaluated as an alternative and not used. |
| Wikidata | Reachable and CC0, but not needed: company documents were found for every researched company. |

## Written for this feature

The MaxMind DB reader (`MmdbReader.kt`) is written from the public [MaxMind DB format specification](https://maxmind.github.io/MaxMind-DB/) and adds no library. It was checked against the real GeoLite2-Country file on 10 October 2026, read in place; nothing from that file was kept.
