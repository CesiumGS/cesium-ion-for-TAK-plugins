#!/usr/bin/env bash
# One-time setup: registers this Mac as the GitHub Actions runner that runs
# device tests on the attached tablet (.github/workflows/device-test.yml),
# and starts it as a background service so it survives restarts.
#
#   atak/scripts/setup-runner.sh            # install and start
#   atak/scripts/setup-runner.sh --remove   # stop and unregister
#
# It needs a short-lived registration token from GitHub. With the GitHub CLI
# (`gh auth login`) the script fetches one itself; otherwise open the
# repository on GitHub > Settings > Actions > Runners > New self-hosted
# runner, copy the token shown after --token, and paste it when asked.
#
# While the runner is up, anyone who can push to the development branch or
# dispatch workflows in this private repository can run a build on this Mac.
# Stop it with: cd ~/actions-runner-atak && ./svc.sh stop
set -euo pipefail

REPO="${REPO:-CesiumGS/cesium-ion-for-TAK-plugins}"
DIR="${RUNNER_DIR:-$HOME/actions-runner-atak}"
LABEL="atak-device"

fail() { printf '\nSTOPPED: %s\n' "$*" >&2; exit 1; }

[ "$(uname -s)" = "Darwin" ] || fail "This script sets up a macOS runner."

registration_token() {
    local kind="$1" token=""
    if command -v gh >/dev/null 2>&1 && gh auth status >/dev/null 2>&1; then
        token="$(gh api -X POST "repos/$REPO/actions/runners/$kind-token" --jq .token 2>/dev/null || true)"
    fi
    if [ -z "$token" ]; then
        echo "Paste the $kind token from GitHub > $REPO > Settings > Actions > Runners" >&2
        echo "(New self-hosted runner shows it after --token). It is not echoed:" >&2
        read -r -s token
        echo >&2
    fi
    [ -n "$token" ] || fail "No token."
    printf '%s' "$token"
}

if [ "${1:-}" = "--remove" ]; then
    [ -d "$DIR" ] || fail "No runner in $DIR."
    cd "$DIR"
    ./svc.sh stop >/dev/null 2>&1 || true
    ./svc.sh uninstall >/dev/null 2>&1 || true
    ./config.sh remove --token "$(registration_token remove)"
    echo "Runner removed. You can delete $DIR."
    exit 0
fi

# Tools the device test needs, checked up front so failures are clear.
ADB="$(command -v adb || true)"
[ -n "$ADB" ] || [ -x "$HOME/Library/Android/sdk/platform-tools/adb" ] \
    || fail "adb not found. Install Android Studio (SDK platform-tools)."
[ -d "/Applications/Android Studio.app" ] || [ -n "${JAVA_HOME:-}" ] \
    || fail "Android Studio (or JAVA_HOME) is needed for the JDK."

case "$(uname -m)" in
    arm64) ARCH="arm64" ;;
    x86_64) ARCH="x64" ;;
    *) fail "Unsupported CPU $(uname -m)." ;;
esac

if [ ! -x "$DIR/config.sh" ]; then
    VERSION="$(curl -fsSL https://api.github.com/repos/actions/runner/releases/latest \
        | sed -n 's/.*"tag_name": *"v\([0-9.]*\)".*/\1/p' | head -1)"
    [ -n "$VERSION" ] || fail "Could not find the latest runner version."
    echo "Downloading GitHub Actions runner $VERSION ($ARCH) into $DIR"
    mkdir -p "$DIR"
    curl -fsSL "https://github.com/actions/runner/releases/download/v$VERSION/actions-runner-osx-$ARCH-$VERSION.tar.gz" \
        | tar -xz -C "$DIR"
fi

cd "$DIR"
if [ ! -f .runner ]; then
    # The runner records this shell's PATH; make adb findable for the service.
    export PATH="$PATH:$HOME/Library/Android/sdk/platform-tools"
    ./config.sh --unattended --replace \
        --url "https://github.com/$REPO" \
        --token "$(registration_token registration)" \
        --name "$(scutil --get LocalHostName 2>/dev/null || hostname -s)-atak" \
        --labels "$LABEL" \
        --work _work
fi

./svc.sh install >/dev/null 2>&1 || true
./svc.sh start
cat <<DONE

Runner installed and started (label $LABEL), in $DIR.
Keep the tablet attached by USB, unlocked, with Developer options > Stay awake on.
Stop it any time with:  cd "$DIR" && ./svc.sh stop
Start it again with:    cd "$DIR" && ./svc.sh start
DONE
