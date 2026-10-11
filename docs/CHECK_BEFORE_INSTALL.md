# Check before install

Request: "if TraceVeil runs in the background and checks an app automatically when someone tries to install it, and tells the person before the install, that would be useful."

This page says what TraceVeil now does for that, what Android does not allow, the permissions involved, and how to test it on a phone. Code: `app/.../apkguard/` (opener, report, Install anyway), `app/.../autoguard/` (downloads watcher, after-install check), `core/.../apk/` (signer check, pre-install advice, notification highlights).

## What Android allows (Android 10 to 16)

| Wanted | Possible? | What TraceVeil does |
| --- | --- | --- |
| Stop or pause another app's install (Chrome, Files, the Play Store or Android's package installer) and check it first | **No.** No public API lets a third-party app see or block an install that another installer is doing. Device-owner (MDM) policies and misuse of an accessibility service are the only ways, and TraceVeil uses neither | Nothing. It does not pretend to |
| Be the app that opens a tapped `.apk` file | **Yes**: an activity for `ACTION_VIEW`/`ACTION_SEND` (and the old `ACTION_INSTALL_PACKAGE`) with the APK MIME type | Flow 1. If the person picks TraceVeil with **Always**, every APK they tap opens in TraceVeil first: the closest Android allows to "check before every install" |
| Install the checked file afterwards | **Yes, with the person's confirmation**: `PackageInstaller` session + `REQUEST_INSTALL_PACKAGES` + the person's "Install unknown apps" switch for TraceVeil | "Install anyway" (Flow 1). Android shows its own confirmation every time |
| Notice a new APK in Downloads | **Partly.** MediaStore shows an app without storage permission only the downloads it made itself (Android 11+, and Android 10 under scoped storage). `READ_MEDIA_*` (Android 13+) covers photos, video and audio only, never APKs. Only All-files access (`MANAGE_EXTERNAL_STORAGE`) would show other apps' APKs, and TraceVeil does not ask for it | Flow 2: a content-URI job sees *that* something changed in Downloads; TraceVeil then asks Android only for the item's type. Whether a phone answers that for another app's download must be checked on the phone |
| Notice an app right after it was installed | **Yes**: `PACKAGE_ADDED` while TraceVeil runs, `PackageManager.getChangedPackages` in a background job otherwise | Flow 3: the existing new-app check now also runs the static APK Guard check and its code scan, and says what it found in the notification |

Nothing here sends data off the phone, and nothing starts a consent check, a VPN or an install by itself.

## Flow 1: open an APK with TraceVeil, then decide

1. The person downloads an APK (Chrome, WhatsApp, Files) and taps it. Android shows "Open with": **TraceVeil** or the package installer. Choosing TraceVeil with **Always** makes this automatic for every APK they tap later. Sharing the APK to TraceVeil works too.
2. TraceVeil copies the file into its private cache at once (while the sending app's read permission is valid), records its SHA-256, and reads it with Android's `getPackageArchiveInfo` plus a byte-level scan of the DEX string tables. It never loads or runs code from the file.
3. The APK Guard result shows, top to bottom: the verdict card (app, headline, counts, "Not an antivirus"); the **Before you install** group; app details (version, target API, file name and size, signing-certificate and file SHA-256 with Copy); **SDKs named in its code** (the bundled signatures in `core/src/main/resources/sdk/code_signatures.json`, with the tracker catalogue's category); and every APK Guard signal (permissions, risky combinations, special services, install source, identity).
4. **Signer check** (`core/.../apk/SignerCheck.kt`): if an app with the same package name is installed, the file's signer set is compared with the installed one, the way Android decides updates. Same signer: "This file would update your installed "X" (old → new). It is signed with the same key." A v3 rotated key whose history holds the installed key is also an update. A **different signer is the big red flag** (red notice, below), and Android would refuse the file anyway, so TraceVeil does not offer to install it. An older version code (downgrade) and a target API that this Android refuses (below 23 on Android 14, below 24 on Android 15 and later) are blocked the same way. An unreadable certificate is "not established", never a match.
5. Buttons: **Install anyway** and **Don't install**. "Don't install" is the filled (first) button whenever APK Guard found something to review carefully; otherwise "Install anyway" is. When Android would refuse the file, only "Don't install" is shown.
6. **Install anyway** (`InstallHandoff`, `PlatformInstaller`):
   - It refuses a blocked file, a missing checked copy, and a copy whose SHA-256 no longer matches the check. Only the exact bytes that were checked are installed.
   - If Android's **Install unknown apps** switch is off for TraceVeil, nothing is created. TraceVeil explains and offers **Open the setting** (`Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES` for its own package). It never bypasses the switch. After the person comes back, it says "Allowed. Tap Install anyway again to continue."
   - Otherwise it creates a `PackageInstaller` session (`MODE_FULL_INSTALL`, `USER_ACTION_REQUIRED` on Android 12+, `PACKAGE_SOURCE_DOWNLOADED_FILE` on Android 13+ so Android's protections for sideloaded apps, such as restricted settings, still apply; no update ownership requested), writes the copy, and commits with a PendingIntent to its private `InstallResultReceiver`.
   - Android answers "pending user action". The result screen (an activity in front) then shows **Android's own confirmation**. Nothing is installed until the person taps Install there.
   - Outcome on the same screen: Installed (with **Run a consent check**, which opens the check sheet, and **See it in APK Guard**), cancelled, or Android's reason (for example "Not installed: it conflicts with an app already on the phone, for example the same package signed with a different key."). After a successful install the checked copy is deleted, and the new-install alert is not repeated for this app.
7. **Don't install** deletes TraceVeil's checked copy and says the original file is still where the person found it. Copies are also deleted when the check drops out of memory (six checks kept) or after one hour at the latest.

A `file://` URI, a missing file or a web address is not read; the result screen says why and what to do instead (open it from Files or Chrome's downloads, or share it).

**Split bundles (.xapk, .apks, .apkm)** are checked with the same rules as the developer CLI (`traceveil audit --apk`, `src/traceveil/apkfile.py` on `origin/cli`). They usually reach APK Guard through **Choose a file**: Android gives them no standard MIME type, so the "Open with" filter does not match them.
- A zip with `AndroidManifest.xml` at its root is an APK; a zip with APK files inside is a bundle.
- The base APK is the one whose binary manifest has no `split` attribute (`core/.../apk/ApkBundles.kt`, a Kotlin port of the CLI's `manifest_attrs`).
- `manifest.json` (XAPK) or `info.json` (APKM) only fills gaps such as the app name.
- The report uses the base APK's facts; the code scan covers every APK inside. Entry names are flattened into a private folder, at most 64 APKs and 400 MB, deleted after the read.
- The CLI also installs bundles over adb. TraceVeil on the phone only checks them: it shows "This is an app bundle with N APK files…" and offers only **Don't install** (Android's own installer cannot open bundles either). OBB expansion files are ignored.

## Flow 2: a new APK appears in Downloads

- Setting: **Settings › Auto-guard › Check new APK downloads**, on by default, English and Hindi text (`DownloadAlertCopy`). The same switch is a row in **What TraceVeil uses** (Home's Setup sheet and Settings › Permissions & background: `ControlId.APK_DOWNLOADS`, under "TraceVeil's own switches"). It has its own job, so it stays switchable while the new-app check is off.
- A one-shot JobScheduler job (id **4104**) with `JobInfo.TriggerContentUri` on `MediaStore.Downloads.EXTERNAL_CONTENT_URI` (descendants), 3 s quiet period, at most 30 s delay. Content-URI jobs cannot be persisted, so it is scheduled again after each run, after a restart or an update of TraceVeil, and at every app start. No battery constraint: each run only asks MediaStore about the changed items.
- For each changed item (`DownloadsWatch.newApks`):
  - if MediaStore lets TraceVeil see the row, it uses the name, MIME type, `IS_PENDING` and `DATE_ADDED`;
  - otherwise it asks only `ContentResolver.getType` for the item.
  - It alerts an item once (ledger of MediaStore ids, last 100), only if it is an APK, not still being written (when known) and added in the last hour (when known).
- Notification, channel "New APK downloads": **"New APK downloaded: <name>" / "Check it before installing. Tap to check it in TraceVeil."** When the name is not visible: **"New APK downloaded" / "Check it before installing. Tap, then pick it from Downloads."** Hindi on the second line.
- Tap: a readable file opens its APK Guard check directly. Otherwise TraceVeil opens a short screen and Android's file picker once, in Downloads, filtered to APK files. The picked file goes to Flow 1.
- **Limits (be honest in demos):**
  - Other apps' downloads are hidden from TraceVeil's MediaStore queries on Android 11 and later.
  - Whether Android 16 on the test phone answers the type query for them is **not verified**: no phone was used for this work.
  - If no notification appears, the phone does not tell, and Flow 1 is the way.
  - Because a hidden item's pending state is unknown, a very large download may be announced a few seconds before it finishes. The picker shows it once it is complete.

## Flow 3: after an install (Auto-guard)

- The live receiver (TraceVeil running) and the background new-app check (about every 15 minutes, battery-not-low, deduplicated per install) are unchanged in when they alert.
- New: for each separately alerted app (up to three per run), TraceVeil reads the app's installed APK files (base and splits; Android lets apps read these for normal apps) with the same DEX scan, unless Android reports the battery as low. Combined alerts for more than three apps stay as they were.
- Notification (`InstallAlertPolicy.alert`):
  - Title: **"New app: <name> · N things to review carefully"**, or "· N things worth knowing", or "· check it before you open it".
  - Text: up to two capabilities and the tracking SDKs found, for example **"Can read SMS; can draw over other apps; 5 tracking SDKs in its code. Tap for details."**
  - With nothing to name: "Nothing unusual in what Android shows us — this is not a safety guarantee. Tap for details."
  - Hindi on the second line.
- "Tracking SDK" means a code-signature entity whose tracker-catalogue category is analytics, advertising, attribution, social or engagement; crash reporting is not counted. Code not read, or no match, is simply not mentioned (absence is not proof).
- Tap: the app's APK Guard result. Button **"Run a consent check"**: the app picker with that app selected and its check sheet open. Nothing starts until the person taps Start.

## Permissions and manifest

| Item | Change | Why |
| --- | --- | --- |
| `android.permission.REQUEST_INSTALL_PACKAGES` | **added** | Needed for a `PackageInstaller` session. On its own it installs nothing: Android also needs the person's "Install unknown apps" switch for TraceVeil, and asks for confirmation on every session |
| Storage permissions (`READ_EXTERNAL_STORAGE`, `READ_MEDIA_*`, `MANAGE_EXTERNAL_STORAGE`) | **not requested** (pinned by `AutoGuardManifestTest`) | The downloads watcher works without them, within the limits above |
| `INSTALL_PACKAGES`, `DELETE_PACKAGES`, `REQUEST_DELETE_PACKAGES`, `UPDATE_PACKAGES_WITHOUT_USER_ACTION`, `ENFORCE_UPDATE_OWNERSHIP` | **not requested** (pinned by `ApkGuardManifestTest`) | No silent or ownership installs |
| `MainActivity` intent filters | `VIEW` (content, APK MIME), `SEND` (APK MIME) as before; **new** `INSTALL_PACKAGE` (content, APK MIME) | Some file managers still send the deprecated action |
| `.apkguard.InstallResultReceiver` | **new**, `exported=false`, no intent filter | Reached only through TraceVeil's own PendingIntent |
| `.autoguard.DownloadsJobService` | **new**, `exported=false`, `BIND_JOB_SERVICE` | Downloads watcher job |

**Store review item.** Google Play treats `REQUEST_INSTALL_PACKAGES` as a restricted permission: the app's core purpose must include installing packages, and the Play Console asks for a declaration. If that is not accepted, build with `-Ptraceveil.installHandoff=false`. That build removes the permission from the merged manifest (`app/src/noInstallHandoff/AndroidManifest.xml`) and sets `BuildConfig.INSTALL_HANDOFF = false`. APK Guard then shows only "Don't install" and explains how to continue with Android's own installer; Flows 2 and 3 are unchanged. See `docs/LICENSES_FOR_SALE.md` §4 and `docs/RELEASE.md`.

## Tests (JVM)

- `core`: `SignerCheckTest` (same, rotated, different, unknown signer; downgrade; APK Guard's same-package signal), `PreInstallCheckTest` (blocks, banners, ordering, minimum target API, wording), `InstallHighlightsTest`, `ApkBundlesTest` (binary manifest, base APK, bundle metadata).
- `app`:
  - `IncomingApkTest`: VIEW/SEND/INSTALL_PACKAGE, content vs file URIs, bad input.
  - `InstallHandoffTest`: the installer-session wrapper against a fake `InstallerGateway`, status mapping, hand-off dedup.
  - `InstallAlertCopyTest`, `DownloadsWatchTest` (MediaStore logic against a fake `DownloadsSource`, notification copy, taps), `ApkOpenerTest`, `PreInstallCopyTest`, `BundleReaderTest` (unpacking, zip-slip names, caps).
  - Manifest, settings, trust and controls-inventory tests updated.

## Phone test steps (Android 16 test phone; not yet run)

Install the debug build from this branch (`:app:assembleDebug`) as usual. Allow notifications for TraceVeil.

1. **Chrome → tap → TraceVeil → report → Install anyway.**
   1. Put `riskdemo-debug.apk` (`:riskdemo:assembleDebug`; harmless TEST Challan fixture) or `leakyshop-leaky-debug.apk` somewhere Chrome can download it, for example your own GitHub release. Uninstall that app from the phone first.
   2. In Chrome, download it. Tap **Open** in Chrome's download message (or tap it in Downloads).
   3. Expect "Open with": choose **TraceVeil** (try **Always** once to see that every APK then opens in TraceVeil). If Chrome or the phone's file manager opens the package installer directly without a choice, note it. Share the APK to TraceVeil instead.
   4. Expect the APK Guard result, then **Before you install**. For RiskDemo: "Don't install" filled, "Install anyway" secondary, review-carefully signals listed, and an SDK list (probably "None of the SDKs TraceVeil knows by name was found").
   5. Tap **Install anyway**.
      - First time: expect the notice about **Install unknown apps**. Tap **Open the setting**, allow TraceVeil, come back, and expect "Allowed. Tap Install anyway again to continue."
      - Tap it again. Expect "Handing the checked file to Android's installer…", then **Android's own install dialog**.
   6. Tap Install there. Expect "Installed…" with **Run a consent check** (opens the picker with that app; nothing starts). Expect **no** "New app" notification for this app.
   7. Repeat and tap **Don't install**: expect "Not installed. TraceVeil deleted its checked copy…".
   8. Repeat and press Back in Android's dialog: expect "Not installed: you cancelled in Android's installer."
2. **Signer mismatch (red flag).** With the debug TraceVeil installed, open the published `TraceVeil-1.1.0-release.apk` (release key, `docs/RELEASE.md`) with TraceVeil. Expect the red notice "Red flag: this file is signed with a different key than your installed "TraceVeil"…" and only **Don't install**. Do **not** uninstall the debug build. Also: with LeakyShop 1.1 (`leakyV11`) installed, opening LeakyShop 1.0 should show the "older version" block; with 1.0 installed, 1.1 should show "This file would update your installed "LeakyShop" (1.0 (1) → 1.1 (2))".
3. **Downloads notification.**
   1. Leave **Check new APK downloads** on. Open TraceVeil once after installing the build (this schedules the job).
   2. Download an APK in Chrome and wait up to about 30 s. Expect "New APK downloaded: <name>" or "New APK downloaded".
   3. Tap it. Expect either the APK Guard check, or the "Check the new APK" screen with Android's picker in Downloads.
   4. **If nothing appears**, record that Android 16 on this phone does not reveal other apps' downloads to TraceVeil. Diagnostics, by the person with the phone lease: `adb shell dumpsys jobscheduler | findstr 4104`, and logcat tag `TraceVeilAutoGuard` ("downloads job changed=… alerted=…").
4. **After-install notification.**
   1. With "Check new apps automatically" on, install any small app from Google Play (or `adb install` RiskDemo).
   2. Expect "New app: <name> · …", text naming capabilities and "N tracking SDKs in its code" when the code has catalogued SDKs, and a **Run a consent check** button.
   3. Tap the body (APK Guard result) and the button (picker with that app selected). No check starts by itself.
