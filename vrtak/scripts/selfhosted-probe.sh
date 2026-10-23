#!/usr/bin/env bash
# Inside WSL (started by selfhosted-probe.ps1): waits for MicroK8s and the
# ion pods, then probes ion Self-Hosted with atak/scripts/server-probe.sh
# through the LAN address (Windows port proxy) and the WSL VM directly.
#   selfhosted-probe.sh <lan-api-url> <assetId>...
export PATH="$PATH:/snap/bin"
LAN_API="${1:?lan api url}"; shift
HERE="$(cd "$(dirname "$0")" && pwd)"
PROBE="$HERE/../../atak/scripts/server-probe.sh"
section() { printf '\n==== %s  [%s] ====\n' "$*" "$(date +%H:%M:%S)"; }
mask() { sed -E 's/(token|access_token|authorization)([=: "]+)[A-Za-z0-9._-]+/\1\2***/Ig'; }

section "WSL"
uptime
section "Waiting for MicroK8s and the ion pods (up to 10 minutes)"
timeout 440 microk8s status --wait-ready --timeout 420 2>&1 | head -1
timeout 200 microk8s kubectl wait --for=condition=Ready pod --all -n cesium-ion --timeout=180s 2>&1
microk8s kubectl get pods -n cesium-ion 2>&1
NODE_IP="$(hostname -I | cut -d' ' -f1)"
echo "WSL VM address: $NODE_IP"

section "Probe through the LAN address and the Windows port proxy ($LAN_API)"
timeout 600 bash "$PROBE" "$LAN_API" "$@"

section "Probe of the WSL VM directly (http://$NODE_IP:31813), terrain only"
timeout 300 bash "$PROBE" "http://$NODE_IP:31813" 9

section "Ion pods after the probe (restarts mean crashes under load)"
microk8s kubectl get pods -n cesium-ion 2>&1
section "Asset server log tail"
microk8s kubectl logs -n cesium-ion deploy/cesium-ion-v2-assets --tail=40 2>&1 | mask | cut -c1-300
echo "Done."
