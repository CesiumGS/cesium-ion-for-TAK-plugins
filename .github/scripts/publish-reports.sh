#!/usr/bin/env bash
# Publishes files to <dest> on the device-reports branch. Two runners (the
# Mac and the work PC) publish to the same branch, so a rejected push is
# retried on top of the new tip; each run writes its own folder, so the
# retry never conflicts.
# Usage: publish-reports.sh <dest-dir> <commit-message> <path>...
set -euo pipefail
dest="$1"; message="$2"; shift 2
tree="$(mktemp -d)"
trap 'git worktree remove --force "$tree" >/dev/null 2>&1 || true' EXIT
if git fetch -q --depth 1 origin device-reports 2>/dev/null; then
    git worktree add -q -B device-reports "$tree" FETCH_HEAD
else
    git worktree add -q --detach "$tree"
    git -C "$tree" checkout -q --orphan device-reports
    git -C "$tree" rm -rq --cached --ignore-unmatch .
    find "$tree" -mindepth 1 -maxdepth 1 ! -name .git -exec rm -rf {} +
fi
for attempt in 1 2 3 4 5 6; do
    rm -rf "${tree:?}/$dest"
    mkdir -p "$tree/$dest"
    cp -R "$@" "$tree/$dest/"
    git -C "$tree" add "$dest"
    git -C "$tree" -c user.name="device-test" -c user.email="device-test@users.noreply.github.com" \
        commit -qm "$message" || { echo "Nothing new to publish."; exit 0; }
    if git -C "$tree" push -q origin device-reports; then
        echo "Published to device-reports:$dest"
        exit 0
    fi
    echo "Push rejected (another runner published); retrying on the new tip ($attempt)."
    sleep $((attempt * 3))
    # FETCH_HEAD is per worktree: fetch and reset inside the report tree.
    git -C "$tree" fetch -q --depth 1 origin device-reports
    git -C "$tree" reset -q --hard FETCH_HEAD
done
echo "Could not publish after several attempts." >&2
exit 1
