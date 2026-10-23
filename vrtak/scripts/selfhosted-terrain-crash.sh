#!/usr/bin/env bash
# Inside WSL (started by selfhosted-terrain-crash.ps1): why the ion asset
# server dies serving terrain tiles at zoom 8 and deeper. Waits for the
# pods, requests one shallow and one deep tile of the terrain asset (the
# deep one crashes the asset server, which Kubernetes restarts), then
# shows how the container ended (OOMKilled, exit code, signal), its log
# before the crash, the pod's events, memory, and how the asset is stored.
# Tokens are masked; nothing is changed beyond that one request.
export PATH="$PATH:/snap/bin"
API="${1:-http://192.168.1.247:31813}"; ASSET="${2:-9}"
NS=cesium-ion
K="microk8s kubectl"
section() { printf '\n==== %s  [%s] ====\n' "$*" "$(date +%H:%M:%S)"; }
mask() { sed -E 's/(token|access_token|authorization|accessToken)([=:" ]+)[A-Za-z0-9._-]+/\1\2***/Ig; s/eyJ[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]+/***/g'; }
assets_pod() { $K get pods -n "$NS" -o name 2>/dev/null | grep -- '-assets-' | head -1; }

section "Waiting for MicroK8s and the ion pods"
timeout 440 microk8s status --wait-ready --timeout 420 2>&1 | head -1
timeout 200 $K wait --for=condition=Ready pod -l 'app.kubernetes.io/instance=cesium-ion-v2' -n "$NS" --timeout=180s 2>&1 | grep -v maintain
POD="$(assets_pod)"
echo "asset server pod: $POD"
$K get "$POD" -n "$NS" -o jsonpath='{"restarts="}{.status.containerStatuses[0].restartCount}{"  limits="}{.spec.containers[0].resources.limits}{"\n"}'

section "Endpoint of asset $ASSET"
json="$(curl -sS -m 15 "$API/v1/assets/$ASSET/endpoint")"
url="$(printf '%s' "$json" | python3 -c 'import json,sys; print(json.load(sys.stdin)["url"])')"
token="$(printf '%s' "$json" | python3 -c 'import json,sys; print(json.load(sys.stdin)["accessToken"])')"
echo "url: $url"
base="${url%/}/"

section "Shallow tile (z7), then deep tile (z8)"
for tile in 7/74/92 8/149/184; do
    printf '%-12s ' "$tile"
    curl -sS -m 40 -o /dev/null -w '%{http_code} %{size_download}B %{time_total}s\n' \
        -H 'Accept: application/vnd.quantized-mesh,application/octet-stream;q=0.9,*/*;q=0.01' \
        "${base}${tile}.terrain?access_token=$token" 2>&1 | mask
done
sleep 10

section "How the asset server container ended"
$K get "$POD" -n "$NS" -o jsonpath='{range .status.containerStatuses[*]}{"restarts="}{.restartCount}{"\nstate="}{.state}{"\nlastState="}{.lastState}{"\n"}{end}' | mask
section "Asset server log before the crash (last 80 lines)"
$K logs "$POD" -n "$NS" --previous --tail=80 2>&1 | mask | cut -c1-400
section "Asset server log now (last 20 lines)"
$K logs "$POD" -n "$NS" --tail=20 2>&1 | mask | cut -c1-400
section "Pod events"
$K describe "$POD" -n "$NS" 2>&1 | sed -n '/^Events:/,$p' | tail -25
section "Memory (WSL VM)"
free -m
dmesg 2>/dev/null | grep -i -E 'oom|killed process' | tail -5 || echo "(dmesg not readable)"
section "How asset $ASSET is stored (names and sizes)"
ls -la "$HOME/cesium-ion/volumes/assets/$ASSET" 2>&1 | head -20
du -sh "$HOME/cesium-ion/volumes/assets/$ASSET" 2>&1
ls -la "$HOME/cesium-ion/volumes/assets/" 2>&1 | grep -E " ${ASSET}(-|$)" | head
file "$HOME/cesium-ion/volumes/assets/$ASSET"/* 2>/dev/null | head -10
echo "Done."
