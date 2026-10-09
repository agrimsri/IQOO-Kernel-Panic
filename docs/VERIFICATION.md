# TraceVeil baseline verification

Verified on 9 October 2026 from the clean `kernel-panic` directory.

```text
gradlew.bat --offline :core:test :app:assembleDebug :leakyshop:assembleLeakyDebug :leakyshop:assembleFixedDebug --console=plain

BUILD SUCCESSFUL in 1m 10s
121 actionable tasks: 70 executed, 51 from cache
Core tests: 65; failures: 0; errors: 0; skipped: 0
```

The core tests executed in the destination directory. Kotlin compilation and APK packaging executed for the rebranded application; some unchanged dependency/resource tasks used the local Gradle cache.

Before creating the first six local import commits, the same full command was run again on 9 October 2026: `BUILD SUCCESSFUL in 3s`, with all 121 actionable tasks up-to-date. This second run checked the complete local working tree, not a checkout of the partial committed branch.

Toolchain: JDK 17.0.20.1, Gradle 9.6.0, Android SDK platform 37, build-tools 36.0.0. Local SDK configuration is ignored. Dependency/model installation remains per developer/device.

## Outputs

- `app/build/outputs/apk/debug/app-debug.apk`
- `leakyshop/build/outputs/apk/leaky/debug/leakyshop-leaky-debug.apk`
- `leakyshop/build/outputs/apk/fixed/debug/leakyshop-fixed-debug.apk`

APK files and generated build directories are excluded from source commits.

## Source and repository checks

- All 86 imported files match `docs/baseline-manifest.json`; 15 files differ intentionally from the original, comprising branding/setup text and ignore rules.
- The source MIT licence is byte-for-byte identical.
- No old product labels remain in copied Kotlin or XML files; internal package/class identifiers retain compatibility.
- Local Git HEAD references `setup/traceveil-baseline`.
- `origin` is `https://github.com/agrimsri/IQOO-Kernel-Panic.git`.
- Six import commits exist under Nitya's configured identity; HEAD is `445db1d`. The index is empty after those commits. All six were pushed to `origin/setup/traceveil-baseline`.
- Remote `setup/traceveil-baseline`, `agrim/rules-reports`, and `yashvi/ui-ai` were verified at `445db1d597d2ab374367d5bad06b12841416ba12` after an atomic push. No teammate-authored commits or changes to `main` were made.
- Remaining source files are untracked. The committed branch alone is incomplete; the successful builds above used the complete local working tree.

## Remaining device checks

The team confirms Office Kit installation and pairing on phones and laptops. The earlier ADB probe listed no devices; no phone APK installation, model inference, live audit, or UI smoke test has been performed in this pass.

The build emits existing warnings for an unnecessary safe call, a deprecated accessibility-node property, and native libraries whose debug symbols cannot be stripped. These do not fail debug assembly.

Event eligibility remains separate from build correctness. See `PROVENANCE.md` for the copied baseline and the unresolved reuse-rule conflict.
