# Y5: removing Camera Check without breaking the build

Status: **prepared and verified, not applied.** Camera Check is still on `main` (the Home button and the `camera` route, both Nitya's files), so Y5's own commit must wait for her part. The YASHVI prompt says the same: remove the screen from `ToolScreens.kt` only after Nitya removes the Home entry and route, and never leave a dangling route.

## Why the order matters

`CameraCheckScreen` (Yashvi, `ToolScreens.kt`) reads state that lives in `XrayViewModel.kt` (Nitya), and `MainActivity.kt` (Nitya) imports the screen and routes to it. Removing any one of them first breaks the build:

| If this goes first | What breaks |
| --- | --- |
| The screen (mine) | `MainActivity` still imports `CameraCheckScreen` and routes `"camera"` to it |
| The ViewModel state (hers) | The screen still reads `vm.cameraResult` and imports `CameraCheckState` |
| The Home button and route (hers) | Nothing: the screen is simply unused |

So there is exactly one order in which every commit builds on its own:

| Step | Owner | Files | Patch |
| --- | --- | --- | --- |
| 1 | Nitya | `HomeScreen.kt`, `MainActivity.kt` | `y5/1-nitya-home-button-and-route.patch` |
| 2 | Yashvi | `ToolScreens.kt` | `y5/2-yashvi-toolscreens.patch` |
| 3 | Nitya | `XrayViewModel.kt` | `y5/3-nitya-viewmodel.patch` |

## What each step does

1. **Home button and route.** Drops the `onCamera` parameter and the "Camera check" button from `HomeScreen` (the "Open sample audit" button now spans the full width instead of half), and removes the import, the `onCamera = ...` argument and the `composable("camera")` route from `MainActivity`.
2. **The screen.** Deletes `CameraCheckScreen`, `Verdict` and `VerdictLine` (69 lines) and the 13 imports only they used. `AiLabScreen` and `SettingsScreen` are untouched.
3. **ViewModel state.** Deletes `_cameraResult`, `cameraResult`, `cameraCheck()`, `resetCamera()`, the `CameraCheckState` interface and the two imports (`Bitmap`, `ScreenAudit`) nothing else uses.

Deliberately **kept**: `LlmEngine.auditConsentScreen`. The audit orchestrator uses it for the real consent-screen review, so it is not a camera helper. There is no camera permission in the manifest, so there is nothing to remove there.

## Applying it

Nitya applies patches 1 and 3 to her own branch off `main` (they apply cleanly to current `main`): `git apply docs/proposals/y5/1-nitya-home-button-and-route.patch`, commit, and open the PR; patch 3 goes in after patch 2 is on `main`.

Yashvi applies patch 2 **after** step 1 is on `main` and merged into `yashvi/phone-experience`. It applies to the committed branch but **not** on top of uncommitted edits to `ToolScreens.kt` (the import block it trims overlaps them), so commit or stash those first. Do not merge a branch that contains step 2 before step 1 is on `main`.

## Verification

Done in a throwaway worktree on top of this branch's HEAD; nothing on the real branch or in Nitya's files was changed.

| State | Build | App unit tests |
| --- | --- | --- |
| After step 1 | compiles | 60 passed, 0 failed |
| After step 2 | compiles | 60 passed, 0 failed (re-run forced) |
| After step 3 (final) | compiles; all three APKs assemble | 60 passed, 0 failed |
| Final, core | n/a | 296 passed, 0 failed |

After step 3 nothing in `app`, `core` or `leakyshop` mentions Camera Check, `CameraCheckState`, `cameraResult`, `resetCamera` or `VerdictLine`.

On the iQOO 15 (final-state build installed over the existing app): Home has no "Camera check" button and "Open sample audit" spans the width; AI Lab opens and its sections, including the Endpoint map, work; Settings opens with all toggles and notes; no crash in logcat.

## Not covered

No automated UI test exists for Home or navigation, so "no dangling route" is established by the compiler (an unresolved `CameraCheckScreen` or `onCamera` is a build error) and by the phone check above, not by a navigation test.
