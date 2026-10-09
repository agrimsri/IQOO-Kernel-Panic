# Attribution for the endpoint map (Y3)

Entries for the material the Source country / DNS and Owner / legal authority modes use. The format follows `ATTRIBUTIONS.md` on `nitya/repo-readiness`; these rows should be merged into that register. **Status: used at Yashvi's direction, not yet recorded in the team register. Nitya's reviewed decision is still pending** under the register's gate for new external material.

## Data and assets used

| Component | Location | Source and licence | Required handling |
| --- | --- | --- | --- |
| MaxMind GeoLite2 Country database | **Not in this repository or the APK.** Read from `GeoLite2-Country.mmdb` in the app's `files/geo/` folder on the phone | © MaxMind, Inc. [GeoLite End User License Agreement](https://www.maxmind.com/en/geolite2/eula); copyrightable elements are under CC BY-SA 4.0 (EULA section 3) | Show this notice wherever the data is used: *"This product includes GeoLite Data created by MaxMind, available from https://www.maxmind.com"* (the card shows it whenever the database is loaded). Do not commit or publicly redistribute the file: the EULA grants only internal use and restricts disclosure to third parties (section 6.1), and requires destroying versions older than 30 days after a new release (section 6.3). Each user should download their own copy under their own MaxMind account. Do not use the data to identify a household, individual or street address (section 5). |
| `world.svg` world outline | `core/src/main/resources/geo/world.svg`, copied byte-for-byte (SHA-256 `fc268e8f…79d7ef`) | Used by TrackerControl via [takuseno/GeoMap](https://github.com/takuseno/GeoMap), whose README states it uses `world.svg` under **CC BY-NC 4.0**. The file's own metadata names amCharts' *ammap* as its origin; the original amCharts licence text was **not** independently read | Credit: map outline from amCharts via GeoMap by Takuma Seno, CC BY-NC 4.0. **Non-commercial only**: acceptable for the hackathon, not for a commercial release. Keep the file unmodified. |
| DPDP Act 2023, section 3 | Quoted in `LegalReference` (`core/.../geo/MapPresenter.kt`) | *The Digital Personal Data Protection Act, 2023*, Gazette of India Extraordinary, 11 August 2023 (`team_downloads/legal/DPDP_Act_2023.pdf`) | Quoted verbatim from section 3(a) and 3(b). Not legal advice. |

## Looked at and not used

| Source | Why not |
| --- | --- |
| TrackerControl code, UI and databases | GPL-3.0. Only the layout idea (heading, one-sentence explanation, then the content with loading and failed states) was followed. No code, string, colour value or database was copied. Its GeoLite2 file and `world.svg` were inspected in the local checkout; the SVG is the only item copied, as above. |
| Exodus Privacy data inside TrackerControl (`trackers.json`) | Only 4 of 36 TraceVeil vendors have a "Primary Location" line, and two name a former company (Crashlytics: Boston; DoubleClick: New York). Not reliable for ownership, so not used. |
| DB-IP "IP to Country Lite" | CC BY 4.0, CSV, reduced accuracy. Evaluated as an alternative and not used. |

## Written for this feature

The MaxMind DB reader (`MmdbReader.kt`) is written from the public [MaxMind DB format specification](https://maxmind.github.io/MaxMind-DB/) and adds no library. It was checked against the real GeoLite2-Country file on 10 October 2026, read in place; nothing from that file was kept.
