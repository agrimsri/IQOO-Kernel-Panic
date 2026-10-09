# TraceVeil repository checks

N2 adds repeatable history and core-test checks without changing app behavior or teammate feature contracts. The CI workflow runs on pushes, pull requests and manual dispatch with a read-only repository token, complete history and immutable action revisions. It does not install anything on a phone, accept permissions, publish an APK or merge branches.

## Commit date policy

`tools/check-commit-times.sh` checks every commit reachable from a requested revision, including both parents of merges. The default is `HEAD`. Both author and committer dates must be at or after **9 October 2026, 19:00 IST** (`2026-10-09T19:00:00+05:30`). Comparisons use Unix seconds, so different displayed time zones do not change the boundary.

The checker returns:

- `0`: all reachable commit dates pass.
- `1`: at least one author/committer date precedes the cutoff; offending hashes and dates are reported.
- `2`: history cannot be checked, including shallow clones, missing/invalid revisions or usage errors.

It reads metadata only. It never edits dates, identities, commits, branches or remotes. A passing result does **not** establish originality, phone-authored work, schedule compliance or eligibility of the imported pre-event baseline. Never amend or rebase published history to hide a failure; ask the team to resolve the provenance/rules question.

In Bash (Linux/macOS or Git Bash on Windows):

```bash
bash tools/check-commit-times.sh
bash tools/check-commit-times.sh origin/main
python3 -m unittest discover -s tools/tests -v
```

From PowerShell, point `TRACEVEIL_BASH` to your actual Git Bash executable (not Windows' WSL launcher):

```powershell
$env:TRACEVEIL_BASH='C:\Program Files\Git\bin\bash.exe'
$env:PYTHONDONTWRITEBYTECODE='1'
python -m unittest discover -s tools/tests -v
& $env:TRACEVEIL_BASH tools/check-commit-times.sh
```

The test suite creates disposable repositories with explicitly labelled synthetic commits. Those dates never apply to the team's repository. It tests the exact cutoff, both timestamp fields, early ancestors, merged and unreachable branches, explicit revisions, invalid options, extra arguments, non-repositories and shallow history. No network access or extra Python package is required for the tests.

## Core and APK checks

On Windows, use JDK 17 and your own installed SDK/cache paths:

```powershell
.\gradlew.bat :core:test :app:assembleDebug :leakyshop:assembleLeakyDebug :leakyshop:assembleFixedDebug --console=plain
```

On Linux/macOS, the wrapper now has Git executable mode `100755`:

```bash
./gradlew :core:test --no-daemon --console=plain
```

CI runs the full `:core:test` suite, not an APK/device acceptance test. It downloads the pinned build dependencies; `--offline` is only appropriate for a populated local cache. Teammates keep their existing feature branches and ownership. Once N2 is reviewed and merged, pull `main` and preserve published history when updating branches.

See [device verification](VERIFICATION.md) for actual phone evidence and known failures, [attributions](../ATTRIBUTIONS.md) for dependency decisions, and [provenance](../PROVENANCE.md) for the pending organiser ruling. Local passing commands are not a claim that hosted GitHub Actions or either teammate's phone has passed.
