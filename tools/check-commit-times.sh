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

if ! rows=$(git --no-pager log --no-show-signature --no-notes --format='%H|%at|%ct|%aI|%cI' "$commit"); then
    printf 'ERROR: unable to read complete history for revision: %s\n' "$revision" >&2
    exit 2
fi

checked=0
failed=0
while IFS='|' read -r sha author_epoch committer_epoch author_date committer_date extra; do
    if [ -z "$sha" ] || [ -z "$author_date" ] || [ -z "$committer_date" ] || [ -n "$extra" ]; then
        printf 'ERROR: incomplete commit-date metadata for %s.\n' "$sha" >&2
        exit 2
    fi
    case "$author_date/$committer_date" in
        *%*)
            printf 'ERROR: unformattable commit-date metadata for %s.\n' "$sha" >&2
            exit 2
            ;;
    esac
    checked=$((checked + 1))
    for field in author committer; do
        if [ "$field" = 'author' ]; then
            epoch=$author_epoch
            date=$author_date
        else
            epoch=$committer_epoch
            date=$committer_date
        fi
        case "$epoch" in
            ''|*[!0-9]*)
                printf 'ERROR: invalid %s timestamp for %s.\n' "$field" "$sha" >&2
                exit 2
                ;;
        esac
        comparison_status=0
        [ "$epoch" -ge "$cutoff_epoch" ] 2>/dev/null || comparison_status=$?
        case "$comparison_status" in
            0) ;;
            1)
                printf 'FAIL: %s %s date %s is before %s.\n' "$sha" "$field" "$date" "$cutoff" >&2
                failed=1
                ;;
            *)
                printf 'ERROR: uncomparable %s timestamp for %s.\n' "$field" "$sha" >&2
                exit 2
                ;;
        esac
    done
done <<EOF
$rows
EOF

if [ "$checked" -eq 0 ]; then
    printf 'ERROR: no complete commit-date metadata was read.\n' >&2
    exit 2
fi

if [ "$failed" -ne 0 ]; then
    printf 'Commit-date check failed; do not rewrite history to hide early commits.\n' >&2
    exit 1
fi

printf 'PASS: checked %s reachable commits at %s; author and committer dates are at or after %s.\n' "$checked" "$commit" "$cutoff"
printf 'Metadata only: imported source provenance and organiser eligibility remain separate checks.\n'
