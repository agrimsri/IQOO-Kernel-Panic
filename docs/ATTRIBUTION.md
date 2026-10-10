# Attribution for the endpoint map (Y3)

Entries for the material the Source country / DNS and Owner / legal authority modes use. The format follows `ATTRIBUTIONS.md` on `nitya/repo-readiness`; these rows should be merged into that register. **Status: used at Yashvi's direction, not yet recorded in the team register. Nitya's reviewed decision is still pending** under the register's gate for new external material.

## Data and assets used

| Component | Location | Source and licence | Required handling |
| --- | --- | --- | --- |
| DB-IP IP to Country Lite (bundled) | `app/src/main/assets/geo/dbip-country-lite.mmdb.gz` in the repository; `assets/geo/dbip-country-lite.mmdb` inside the APK. Used whenever the person has not placed a GeoLite2 file of their own | © DB-IP.com, [IP to Country Lite](https://db-ip.com/db/download/ip-to-country-lite), licensed under [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/) (page read 10 October 2026) | Attribution "IP Geolocation by DB-IP (https://db-ip.com)" with the licence, printed on the map card whenever this table placed the countries. Unmodified. See "DB-IP Lite: version, licence and APK impact" below. |
| MaxMind GeoLite2 Country database | **Not in this repository or the APK.** Optional: read from the person's own `GeoLite2-Country.mmdb` in the app's `files/geo/` folder on the phone, and preferred over the bundled table when present | © MaxMind, Inc. [GeoLite End User License Agreement](https://www.maxmind.com/en/geolite/eula) (read 10 October 2026). Copyrightable elements are governed by CC BY-SA 4.0; separately, a limited internal-use licence (§3) | See "GeoLite2 terms, version and distribution" below. |
| `world.svg` world outline | `core/src/main/resources/geo/world.svg`, copied byte-for-byte (SHA-256 `fc268e8f…79d7ef`) | Used by TrackerControl via [takuseno/GeoMap](https://github.com/takuseno/GeoMap), whose README states it uses `world.svg` under **CC BY-NC 4.0**. The file's own metadata names amCharts' *ammap* as its origin; the original amCharts licence text was **not** independently read | Credit: map outline from amCharts via GeoMap by Takuma Seno, CC BY-NC 4.0. **Non-commercial only**: acceptable for the hackathon, not for a commercial release. Keep the file unmodified. |
| DPDP Act 2023, section 3 | Quoted in `LegalContext.DPDP_INDIA` (`core/.../geo/MapPresenter.kt`) | *The Digital Personal Data Protection Act, 2023*, Gazette of India Extraordinary, 11 August 2023 (`team_downloads/legal/DPDP_Act_2023.pdf`) | Quoted verbatim from section 3(a) and 3(b). Shown as legal context, not as a finding that the Act applies. Not legal advice. |
| Company facts | `core/src/main/resources/trackers/trackers.json` (`location`, `incorporation`, `note`), read through `OwnerRegistry.fromCatalog` | Cited by Agrim's A5: short quotes from SEC 10-K filings and company terms pages, each with its URL, source kind, date and basis. Nitya records them in `ATTRIBUTIONS.md` | The map adds no company data of its own. Keep the URL and quote with every claim; re-read before relying on an entry. |

## GeoLite2 terms, version and distribution

What the EULA says (§3, §5, §6, read from the raw page text on 10 October 2026), stated precisely because an earlier draft of this file overstated it:

- **Attribution is required** (§3). MaxMind's own example wording is *"This product includes GeoLite Data created by MaxMind, available from https://www.maxmind.com"*. TraceVeil's card prints exactly that whenever the database is used.
- **Use inside an application and display of results are permitted with that attribution**, subject to the agreement's other terms (§3): the copyrightable elements are under CC BY-SA 4.0, and a non-exclusive, non-transferable licence covers use for your own internal business purposes. Do not remove or obscure notices in the data.
- **Giving the database to a third party is conditional, not banned** (§6). Except as the Creative Commons licence explicitly permits, it needs MaxMind's prior written consent; where it is allowed, each recipient must be bound by the same or substantially similar duties (§3, §6, the DPA) and you remain responsible for them. Whether the `.mmdb` file as a whole falls under the CC BY-SA 4.0 grant is a legal question this project has not settled.
- **Update and destruction duty** (§6). When MaxMind releases an updated database you agree to promptly use it, and to stop using and destroy older versions within 30 days of that release, and everything on termination. The card therefore shows the database build date and notes when it is more than 30 days old.
- **No identifying households, individuals or street addresses** (§5), and MaxMind does not guarantee accuracy. TraceVeil shows countries only, labelled as inferred.

Version in use: `GeoLite2-Country`, build epoch `1790694124` (2026-09-29), 8,423,789 bytes, SHA-256 `ff539785596f72ac2a07048f08506b38134bcf3b568182fd65f4534817bf91d7`. It came from the TrackerControl checkout the team supplied, not from a MaxMind download under this project's own account; each person should download their own copy under their own MaxMind account.

**Distribution decision (10 October 2026, Yashvi): keep the database out of Git for now**, although the repository is private. Reasons: the 30-day destruction duty cannot be met for a file preserved in Git history and clones, and sharing with teammates or evaluators is a disclosure that needs the conditions above. Each user puts their copy on their own phone (`adb push GeoLite2-Country.mmdb /sdcard/Android/data/com.dpdpxray.app/files/geo/`). Revisit with Nitya if the team wants to ship it; that needs the §6 conditions met or MaxMind's written consent.

## DB-IP Lite: version, licence and APK impact

Added 10 October 2026 so the map places countries on any phone without a user-provided file. DB-IP's download page states that IP to Country Lite "is licensed under a Creative Commons Attribution 4.0 International License", asks users to "give attribution to DB-IP.com for the data" and, for applications, to link back to DB-IP.com wherever results are displayed; it is published monthly in CSV and MMDB formats.

| Item | Value |
| --- | --- |
| Release | October 2026 MMDB edition. Metadata: `database_type` `DBIP-Country-Lite`, `build_epoch` `1790818572` (2026-10-01 01:36:12 UTC), IPv6 tree, 24-bit records, languages en, de, es, fr, ja, pt-BR, ru, zh-CN, fa, ko |
| Original file | `dbip-country-lite.mmdb`, 8,348,509 bytes, SHA-256 `dbd70ccfa2a13627eaf4913d19920a1a229f1b3c5439ac01c509995b6364202a` |
| In the repository | `app/src/main/assets/geo/dbip-country-lite.mmdb.gz`, made with `gzip -9 -n` (no name or timestamp), 4,239,048 bytes, SHA-256 `02271f2830d129e6ba82c65e3ef71ddbeb6a985249421c08d96d0414ce6caba3`. Content unmodified |
| In the APK | The Android Gradle Plugin gunzips `*.gz` assets when merging, so the APK holds the original file as `assets/geo/dbip-country-lite.mmdb`, 8,348,509 bytes, SHA-256 `dbd70ccf…364202a` (checked with `unzip -p app-debug.apk assets/geo/dbip-country-lite.mmdb \| sha256sum`), stored deflated at 4,238,797 bytes. `GeoTableLoader` opens that name and falls back to the `.gz` name through gzip if a build ever keeps it |
| APK impact | Debug APK 108,789,452 → 113,192,290 bytes (+4,402,838 bytes, +4.2 MiB; both freshly packaged from base `68ef5e0` and from this branch). The table entry is 4,238,797 bytes of that; the trust screen and map code add about 0.17 MB of dex. At run time the table is read once into one 8.0 MiB array, only when a map is shown and no GeoLite2 file is present |
| Records | `continent` and `country` (ISO code, geoname id, EU flag, names). **No `registered_country`**, so the map never shows a "registered in" line for this table |
| Attribution on screen | Footer of the endpoint map card: "Countries are inferred from IP addresses using DB-IP IP to Country Lite (bundled with TraceVeil, database built 2026-10-01)…" and "IP Geolocation by DB-IP (https://db-ip.com). Used under CC BY 4.0 (https://creativecommons.org/licenses/by/4.0/), unmodified." The card's first line also says "Countries from DB-IP Lite · bundled · built 2026-10-01" |
| Accuracy | An IP-to-country table is an estimate. Anycast addresses are announced from many countries yet listed once: this table places 1.1.1.1 in Australia and Google's IPv6 DNS 2001:4860:4860::8888 in Canada. The map's "What this map shows, and what it doesn't" fold says so |

**Which table is used.** `GeoTableChoice` prefers the person's own `GeoLite2-Country.mmdb` (MaxMind terms below) when it loads; otherwise the bundled DB-IP table is used. If the person's file is present but unreadable, the map says so and uses the bundled table. The table is identified from the file's own `database_type`, never its name, so the right credit is printed.

**Updating the table.** Download the newest IP to Country Lite MMDB from DB-IP, check it, then `gzip -9 -n -c dbip-country-lite.mmdb > app/src/main/assets/geo/dbip-country-lite.mmdb.gz` and update `BundledGeoTable.BYTES`, `SHA256` and `BUILD_EPOCH` in `core/.../geo/GeoTable.kt`. `BundledGeoTableTest` fails until the constants match the shipped file. The map notes a table older than 30 days.

## Company facts: one mapping

The owner / legal authority mode reads company facts from the tracker catalogue only. An earlier version of this branch kept a second file (`geo/owners.json`) from my own read of four companies' terms pages; after A5 landed the two disagreed (Google and Meta were a stated address in mine and a headquarters, from SEC 10-K filings, in A5), so the second file was **deleted** and the registry is now a view over the catalogue. The catalogue already refuses a country without an https source, a date, the source's own words and a basis.

Labels follow the catalogue's basis exactly: a headquarters is shown as a headquarters, a stated address as a stated address, an incorporation as its own fact. A company is never described as "registered in" a country (that phrase appears only for an IP address block). A parent company is named only where the cited source names it. Entities nobody researched show "Not established".

## Looked at and not used

| Source | Why not |
| --- | --- |
| TrackerControl code, UI and databases | GPL-3.0. Only the layout idea (heading, one-sentence explanation, then the content with loading and failed states) was followed. No code, string, colour value or database was copied. Its GeoLite2 file and `world.svg` were inspected in the local checkout; the SVG is the only item copied, as above. |
| Exodus Privacy data inside TrackerControl (`trackers.json`) | Only 4 of 36 TraceVeil vendors have a "Primary Location" line, and two name a former company (Crashlytics: Boston; DoubleClick: New York). Not reliable for ownership, so not used. |
| Wikidata | Reachable and CC0, but not needed: A5 cites company documents and SEC filings. |

## Written for this feature

The MaxMind DB reader (`MmdbReader.kt`) is written from the public [MaxMind DB format specification](https://maxmind.github.io/MaxMind-DB/) and adds no library. It was checked against the real GeoLite2-Country file on 10 October 2026, read in place; nothing from that file was kept. It reads the DB-IP Lite MMDB unchanged (same format); `BundledGeoTableTest` checks the shipped file.
