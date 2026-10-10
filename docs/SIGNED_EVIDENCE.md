# Hardware-signed evidence

Every audit-data export (`Export audit data (JSON)` / `Share audit data (JSON)` on the Report screen, and `bundle.json`
from TraceVeil Runner) can carry a signature made by a key that lives in the phone's secure hardware, with that key's
Android Key Attestation certificate chain. `traceveil-ci verify` checks it on a laptop, offline.

The pairing-token HMAC of TraceVeil Runner (`BundleSignatures` in `core/.../automation/AutomationContract.kt`) is
unchanged. It shows a laptop that the bundle came from the phone it paired with, for that run. The hardware signature
shows more, and anyone can check it without a shared secret.

## What a verified signature proves

`traceveil-ci verify` passes only when all of these hold:

| Check | What it means |
| --- | --- |
| Bundle unchanged | The SHA-256 of the file equals the signed hash, and the ECDSA P-256/SHA-256 signature over the file's exact bytes verifies with the key in certificate 1. Any changed, added or removed byte fails. |
| Certificate chain | Every certificate is signed by the next one. |
| Root | The last certificate carries one of Google's two hardware attestation root public keys (see Sources). |
| Key attestation | The attestation extension (OID 1.3.6.1.4.1.11129.2.1.17) nearest the root is on the signing key's own certificate. If it is anywhere else, someone signed a certificate under an attested key, and the check fails. |
| Secure hardware | The attestation and KeyMint security level are both `TrustedEnvironment` (TEE) or both `StrongBox`. `Software` fails. |
| Key origin | The hardware-enforced list says `origin = GENERATED`: the key was generated inside that hardware, not imported. |
| App that created the key | `attestationApplicationId` lists `com.dpdpxray.app` (or `--package`), plus the SHA-256 digests of its signing certificates. `--signer-sha256` also requires one exact digest. |

It also reports these lines, which never make a verification pass or fail:

- **Certificate dates**: a warning when a certificate was outside its validity dates at the signing time written in the
  file. Google advises that expired factory certificates under the RSA root stay trustworthy unless revoked.
- **Attestation challenge**: identifies this install's key.
- **Boot state**: bootloader locked or unlocked, verified-boot state and OS patch level, as the secure hardware
  reported them when the key was made.

When the chain is broken, or does not end at a Google root, the security level, origin, app, challenge and boot lines
start with "Not established", and passes become warnings. A self-made chain can claim anything, so its claims are never
shown with a tick.

## What it does not prove

- **Revocation is not checked.** Google lists revoked attestation keys online at
  https://android.googleapis.com/attestation/status. An offline check cannot read it, so a leaked and since-revoked
  factory key would still pass here.
- **It does not prove the phone was uncompromised.** Attestation says where the key lives and what the secure hardware
  saw at boot. A rooted or compromised Android could still feed TraceVeil false observations before they are signed.
- **It does not prove the audit is true.** It proves these exact bytes were signed by that key. What TraceVeil observed
  keeps its own labels (OBSERVED / INFERRED / NOT ESTABLISHED).
- **Only some fields are signed.** The app name and signing-certificate digests come from Android's keystore service.
  `createdAt`, `appVersion` and `securityLevel` in the signature file are written by the app and are not covered by
  the signature. The verifier reports the attestation's own security level.
- **The debug build proves little about who built it.** It is signed with the shared Android debug key. Compare the
  digest with `apksigner verify --print-certs TraceVeil.apk`.

## The key on the phone

- Android Keystore alias `traceveil-evidence-v1`. EC P-256, `PURPOSE_SIGN`, SHA-256, no user authentication. It is made
  the first time something is signed (`app/.../evidence/AndroidEvidenceKey.kt`).
- StrongBox is requested when the phone has `FEATURE_STRONGBOX_KEYSTORE`. If that fails, the key is made in the TEE.
- Attestation challenge: `traceveil-evidence-v1:` + 16 random bytes in hex, made once with the key. One key signs many
  exports, so the challenge cannot be a bundle hash. It is not a freshness proof.
- Remotely provisioned attestation certificates expire after days or weeks, while Keystore keeps the chain from the
  day the key was made. If a stored chain has an expired certificate, the key is replaced once by a new one, which gets
  a new attestation.
- Signing never blocks an export. If the phone has no secure hardware key, no attestation chain, or a Keystore error,
  the export is written unsigned, no signature file is written, and the screen says "Signing unavailable on this phone".
- The private key never leaves the phone. TraceVeil uses it only to sign audit data that is exported or written by
  TraceVeil Runner.

## Where it appears

- **Report screen, "Save to this phone" card.** After `Export audit data (JSON)` or `Share audit data (JSON)`, one line
  appears under the export button:
  - `Signed in this phone's secure hardware (StrongBox) · verify with traceveil-ci verify`
  - `Signed in this phone's secure hardware (TEE) · verify with traceveil-ci verify`
  - `Signed in this phone's secure hardware · verify with traceveil-ci verify`, on Android 10 and 11, which do not say
    which kind
  - `Signing unavailable on this phone`
- **Save** copies `TraceVeil-<audit id>.json` and `TraceVeil-<audit id>.json.sig.json` to Downloads/TraceVeil. The toast
  ends with the same line.
- **Share** sends both files together.
- **Runner** writes `bundle.json.sig.json` next to `bundle.json` in
  `/sdcard/Android/data/com.dpdpxray.app/files/automation/<run id>/`.
- **Trust screen**, "What stays on this phone": "Signing key for exports".

## Format: `traceveil.signature.v1`

This is a detached JSON file next to the signed file, named `<signed file name>.sig.json`. The verifier also looks for
`<name without .json>.sig.json`. The `traceveil.audit.v1` bundle is not changed at all, so every existing reader (CLI
`check`/`diff`, console, backups) is unaffected.

```json
{
  "schema": "traceveil.signature.v1",
  "algorithm": "ES256",
  "signedFile": "TraceVeil-audit-1791595268053.json",
  "sha256": "<lower-case hex SHA-256 of the signed file's exact bytes>",
  "signature": "<base64 ASN.1 DER ECDSA P-256 signature over those exact bytes>",
  "certificateChain": ["<base64 DER, signing key's certificate>", "...", "<base64 DER, root>"],
  "createdAt": "2026-10-11T08:00:05Z",
  "appVersion": "1.1.0",
  "keyAlias": "traceveil-evidence-v1",
  "securityLevel": "StrongBox | TrustedEnvironment | UnknownSecure",
  "about": "Detached signature for the file named in signedFile: ..."
}
```

The signed bytes are the file exactly as written: UTF-8, as TraceVeil wrote it. Re-indenting or re-saving the JSON
breaks the signature. That is intended.

## Verify on a laptop

Build the tool: `./gradlew :cli:installDist` (scripts in `cli/build/install/traceveil-ci/bin`) or `./gradlew :cli:fatJar`.

```
traceveil-ci verify <bundle.json> [--sig file] [--format text|json] [--package pkg] [--signer-sha256 hex]
```

Exit codes:

- 0: verified.
- 1: not verified. This includes "no signature file found".
- 2: usage error, for example a missing bundle, a missing `--sig` file or an unknown option.

`--format json` prints a `traceveil.verify.v1` object with `verified`, `status` (`verified` / `failed` / `absent`),
`securityLevel`, `keyOrigin`, `root`, `app`, every check and the limits.

Example. This output used a self-made test chain, so the root is the test root. A real phone shows a Google root:

```
TraceVeil evidence check · TraceVeil CI 0.1.0 · offline
File:           TraceVeil-audit-1791595268053.json
Signature file: TraceVeil-audit-1791595268053.json.sig.json

  ✓ Signature file: traceveil.signature.v1, ES256, written by TraceVeil 1.1.0 at 2026-10-11T08:00:05Z for TraceVeil-audit-1791595268053.json.
  ✓ Bundle unchanged: SHA-256 of the file matches the signed hash (c3fe01bb…).
  ✓ Signature: Valid ECDSA P-256 / SHA-256 signature over the file's exact bytes, by the key in certificate 1.
  ✓ Certificate chain: 3 certificates, each signed by the next (key certificate first, root last).
  ✓ Root: Ends at <root name>, public key SHA-256 <hash>.
  ✓ Key attestation: The signing key's own certificate carries the attestation (attestation version 300, KeyMint version 300).
  ✓ Secure hardware: The key is in the phone's StrongBox (attestation and KeyMint security level StrongBox).
  ✓ Key origin: The key was generated inside the secure hardware (origin GENERATED, in the hardware-enforced list).
  ✓ App that created the key: com.dpdpxray.app (version code 1); signing certificate SHA-256: <digest>. Compare with: apksigner verify --print-certs TraceVeil.apk
  i Certificate dates: All 3 certificates were within their validity dates at the stated signing time 2026-10-11T08:00:05Z (that time comes from the phone and is not signed).
  i Attestation challenge: "traceveil-evidence-v1:…" (identifies this install's key; not a freshness check).
  i Boot state: Bootloader locked, verified boot Verified, OS patch level 2026-09 (reported by the secure hardware when the key was created; it describes the boot, not the running system).

Result: VERIFIED (exit 0). These exact bytes were signed by a key generated inside a phone's StrongBox; the attestation says com.dpdpxray.app created the key; the chain ends at <root name>.

Limits:
  - Revocation is not checked: ...
```

A file changed after signing shows `✗ Bundle unchanged` and `✗ Signature`, then
`Result: NOT VERIFIED (2 checks failed: Bundle unchanged, Signature; exit 1)`.

## Sources for the Google roots

`core/src/main/resources/evidence/google-attestation-roots.json` is Google's published root list, byte for byte. It
was downloaded on 11 October 2026 from https://android.googleapis.com/attestation/root. This is the "JSON-formatted
array" linked from
https://developer.android.com/privacy-and-security/security-key-attestation#root_certificate (page last updated
2026-07-09). The file's SHA-256 is `b79beef96dd68073f1975bab90970ae85d1ae56d451a819ece6bff3d4e253ab4`. It is identical
to `roots.json` in https://github.com/android/keyattestation.

| Root | Public key SHA-256 (SubjectPublicKeyInfo) |
| --- | --- |
| RSA-4096, `serialNumber=f92009e853b6b045` (2022–2042 issue; the 2016, 2019 and 2021 issues on the same page carry the same key) | `feb2ea7551ee316ed4bb443c8293b884dbfdea40b603ee3e4f4a897e4580fbae` |
| ECDSA P-384, `CN=Key Attestation CA1, OU=Android, O=Google LLC, C=US` (2025–2035; Google: signs chains from 1 February 2026) | `3ee44512a1af2beb39c889490c60ea3f82e43f5d5a5532f5ab9419f676cd07ec` |

Trust rests on the root public key, as Google's page says to check. `RealAttestationChainTest` pins both hashes.

The attestation extension schema is at https://source.android.com/docs/security/features/keystore/attestation#schema.

## Tests

- `core/src/test/kotlin/com/dpdpxray/core/evidence/`:
  - DER reader.
  - Format.
  - Sign and verify with a chain built in the test from fresh in-memory keys (`core/src/testFixtures`). No key is
    committed.
  - Failure cases: tampered byte, trailing newline, wrong root, swapped intermediate, other signer, software key,
    imported key, other app, wrong signer digest, attestation on the wrong certificate, malformed files.
  - Three **real Android chains** from Google's `android/keyattestation` test data (Apache-2.0, commit `3f550f94`, source
    and licence in `core/src/test/resources/evidence/keyattestation/`). These are a Pixel 9 Pro StrongBox RKP chain to
    the RSA root, a preproduction Pixel 9a TEE chain to Key Attestation CA1, and a 2018 Pixel 3 factory-key chain.
    They verify and decode to the values upstream published. A dropped or mixed intermediate fails.
- `cli/src/test/kotlin/com/dpdpxray/cli/VerifyCommandTest.kt`: exit codes, text and JSON output.
- `app/src/test/java/com/dpdpxray/app/evidence/EvidenceFilesTest.kt`: file placement, round trip, graceful failure,
  Keystore level mapping and the trust-screen text.

No real *TraceVeil* signature from a phone exists in the repository yet. That needs the phone check below.

## Phone check (for a person with the phone)

1. Install the debug APK built from this branch. Open a saved audit, then Report, then `Export audit data (JSON)`.
2. Under the button, read the line. Expect `Signed in this phone's secure hardware (StrongBox) · verify with
   traceveil-ci verify`, or `(TEE)` on a phone without StrongBox. The first export can take a few seconds while the key
   is made.
3. Pull both files:
   `adb pull /sdcard/Download/TraceVeil/TraceVeil-<audit id>.json` and
   `adb pull /sdcard/Download/TraceVeil/TraceVeil-<audit id>.json.sig.json`.
   If Downloads already had a file of that name, Android adds ` (1)` to the new copies. In that case, pass `--sig`.
4. On the laptop, run `traceveil-ci verify TraceVeil-<audit id>.json`. Expect exit 0, with ✓ on every check. The Root
   line should name one of the two Google roots above, and the App line should say `com.dpdpxray.app`.
5. Change one character in the JSON and run it again. Expect exit 1 with `✗ Bundle unchanged` and `✗ Signature`.
