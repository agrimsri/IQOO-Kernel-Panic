# Release build

TraceVeil release APKs are signed with the TraceVeil release key, not Android's shared debug key, so anyone can check that a copy came from us. Release builds are arm64-v8a only and are not minified (R8 is off, as before; no ProGuard rules were needed).

## Published build

| Item | Value |
| --- | --- |
| App | TraceVeil 1.1.0 (`versionCode` 2), package `com.dpdpxray.app` |
| File | `TraceVeil-1.1.0-release.apk`, 114,203,373 bytes |
| APK SHA-256 | `6429d830496c0d6a2a1a13430fe2c96def1f8c86ffc14399d2524152e1c669a7` |
| Built from | commit `dd568ba07e5630c00f3a6569dd1fc733a9f00c0f` on branch `nitya/sellable-build`, 11 October 2026, with `:app:assembleRelease` |
| Signature | APK Signature Scheme v2 (minSdk 29 needs no v1 JAR signature); one signer |
| Certificate | `CN=TraceVeil, O=Team Kernel Panic`, RSA 4096, SHA256withRSA, self-signed, valid 11 October 2026 to 26 February 2054 |
| **Certificate SHA-256** | `c927b0797bacf173366ed1f6456dc5eaf09adceae92ffa4ba2cdf8da4bedce21` |
| Same, colon form | `C9:27:B0:79:7B:AC:F1:73:36:6E:D1:F6:45:6D:C5:EA:F0:9A:DC:EA:E9:2F:FA:4B:A2:CD:F8:DA:4B:ED:CE:21` |
| Certificate SHA-1 | `215fa0ecdc5679bf2904d347a858ad1beac86933` |

The certificate digest is the one to publish and compare: every future release signed with the same key has it, while the APK SHA-256 matches only this exact file.

## Check a copy you were given

```text
apksigner verify --print-certs TraceVeil-1.1.0-release.apk
sha256sum TraceVeil-1.1.0-release.apk
```

`apksigner` must print `Verifies` and `Signer #1 certificate SHA-256 digest: c927b0797bacf173366ed1f6456dc5eaf09adceae92ffa4ba2cdf8da4bedce21`. On the phone, TraceVeil's Trust screen ("Identity to compare") shows the same certificate SHA-256 for a release install, without the "shared debug key" warning that debug builds carry (`ReleaseCertificateTest` checks this with the public certificate in `app/src/test/resources/trust/`).

## Build a release

1. Get the keystore and its properties file from the release owner. They are kept outside the repository and must never be committed (`*.jks` and `keystore.properties` are in `.gitignore`).
2. Put `keystore.properties` at the repository root:

   ```properties
   storeFile=D:/DevTools/keys/traceveil-release.jks
   storeType=PKCS12
   storePassword=...
   keyAlias=traceveil
   keyPassword=...
   ```

   Or set the environment variables `TRACEVEIL_KEYSTORE`, `TRACEVEIL_KEYSTORE_TYPE`, `TRACEVEIL_KEYSTORE_PASSWORD`, `TRACEVEIL_KEY_ALIAS` and `TRACEVEIL_KEY_PASSWORD` (a CI secret store, for example). The file wins over the environment for each value. It is a PKCS12 keystore, so the store and key passwords are the same.
3. Build: `gradlew :app:assembleRelease` (on the team laptop, through the locked runner: `tv-build.ps1 -Tasks ":app:assembleRelease"`). The signed APK is `app/build/outputs/apk/release/app-release.apk`.
4. Check it as above and record its SHA-256 next to the file (`SHA256SUMS.txt`).

Without the settings, release builds are left **unsigned** (`app-release-unsigned.apk`); with only some of them, Gradle logs `Release signing: incomplete settings ...` and also leaves the build unsigned. Debug builds always use the Android debug key and are not affected. (Earlier, release builds were signed with the debug key.)

**Look after the key.** Keep an offline backup of `traceveil-release.jks` and its properties file somewhere other than this laptop. Android only installs an update signed with the same key, so if the key is lost, existing users cannot update and must uninstall. If the app is later published on Google Play, Play App Signing would add Google's own app-signing key; the published certificate digest would then change and this page must be updated.

## The demo phone runs a debug build: switching to the release build

The phone used in demos has a **debug** build, signed with an Android debug key (the team laptop's `debug.keystore` has certificate SHA-256 `0d102396fb053ee498e37cb198340d3962a2d7823e6a7fb92fef8f70f01b0df5`; the Trust screen shows which one the phone's install has). The release APK has the same package name but a different signing key, and **Android will not install it over the debug install** (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`). The debug build must be uninstalled first, and uninstalling deletes TraceVeil's data on the phone (saved checks, settings, downloaded models in the app's folder). `android:allowBackup` is `false`, so Android's own backup will not bring the data back.

To switch without losing saved checks:

1. In the debug build, Reports (or Settings) → **Back up my checks** and save the file outside TraceVeil's folder (for example Downloads). Consent-screen screenshots are not included in the backup.
2. Uninstall the debug build.
3. Install `TraceVeil-1.1.0-release.apk`.
4. Reports → **Restore from a backup** and pick the file.
5. Copy back anything else that lived in the app's folder (the Gemma model, a GeoLite2 file, `api.env`) the same way it was first put there, and grant TraceVeil's permissions again (VPN, accessibility and the others) when it asks.

Do not switch the phone shortly before a demo; the debug build on it is a separate install and keeps working.
