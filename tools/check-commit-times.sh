#!/bin/sh
# Read-only metadata check, not proof of originality or event eligibility.
set -eu

cutoff='2026-10-09T19:00:00+05:30'
cutoff_epoch=1791552600

if [ "$#" -gt 1 ]; then
    printf 'Usage: %s [revision] (default: HEAD)\n' "$0" >&2
    exit 2
fi

if ! git rev-parse --git-dir >/dev/null 2>&1; then
    printf 'ERROR: run this check inside a Git repository.\n' >&2
    exit 2
fi

if [ "$(git rev-parse --is-shallow-repository)" != 'false' ]; then
    printf 'ERROR: shallow history cannot establish all commit dates; fetch complete history first.\n' >&2
    exit 2
fi

revision=${1:-HEAD}
if ! commit=$(git rev-parse --verify --end-of-options "${revision}^{commit}" 2>/dev/null); then
    printf 'ERROR: invalid or missing commit revision: %s\n' "$revision" >&2
    exit 2
fi

if ! rows=$(git --no-pager log --no-show-signature --no-notes --format='%H %at %ct %aI %cI' "$commit"); then
    printf 'ERROR: unable to read complete history for revision: %s\n' "$revision" >&2
    exit 2
fi

checked=0
failed=0
while IFS=' ' read -r sha author_epoch committer_epoch author_date committer_date; do
    checked=$((checked + 1))
    if [ "$author_epoch" -lt "$cutoff_epoch" ]; then
        printf 'FAIL: %s author date %s is before %s.\n' "$sha" "$author_date" "$cutoff" >&2
        failed=1
    fi
    if [ "$committer_epoch" -lt "$cutoff_epoch" ]; then
        printf 'FAIL: %s committer date %s is before %s.\n' "$sha" "$committer_date" "$cutoff" >&2
        failed=1
    fi
done <<EOF
$rows
EOF

if [ "$failed" -ne 0 ]; then
    printf 'Commit-date check failed; do not rewrite history to hide early commits.\n' >&2
    exit 1
fi

printf 'PASS: checked %s reachable commits at %s; author and committer dates are at or after %s.\n' "$checked" "$commit" "$cutoff"
printf 'Metadata only: imported source provenance and organiser eligibility remain separate checks.\n'
