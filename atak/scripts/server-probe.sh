#!/usr/bin/env bash
# Probes a single-user Cesium ion Self-Hosted server from the runner's LAN:
# for each asset, its endpoint, then the asset's own URLs with a few header
# variations, printing status, size, time and content type (never tokens).
# Terrain gets layer.json, one tile per zoom level over TERRAIN_AT (lat,lon;
# Philadelphia by default) and a burst of parallel tiles, as ATAK requests
# them when the view moves.
#   atak/scripts/server-probe.sh <api-url> <assetId>...
set -uo pipefail
API="${1:?api url}"; shift
TERRAIN_AT="${TERRAIN_AT:-39.9526,-75.1635}"
BURST="${BURST:-24}"
redact() { perl -pe 's/((?:access_token|token|key)=)[^&"\s]+/${1}REDACTED/gi; s/("accessToken"\s*:\s*")[^"]+/${1}REDACTED/g; s/eyJ[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]+/REDACTED/g'; }
fetch() { # label url [curl header args...]
    local label="$1" url="$2"; shift 2
    printf '  %-34s ' "$label"
    curl -sS -m 30 -o /dev/null -w '%{http_code} %{size_download}B %{time_total}s %{content_type} enc=%header{content-encoding}\n' "$@" "$url" 2>&1 | redact
}
for id in "$@"; do
    echo "== asset $id"
    json="$(curl -sS -m 15 "$API/v1/assets/$id/endpoint" 2>&1)"
    printf '%s\n' "$json" | redact | cut -c1-400
    url="$(printf '%s' "$json" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("url",""))' 2>/dev/null)"
    token="$(printf '%s' "$json" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("accessToken",""))' 2>/dev/null)"
    type="$(printf '%s' "$json" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("type",""))' 2>/dev/null)"
    [ -n "$url" ] || { echo "  no url"; continue; }
    sep='?'; case "$url" in *\?*) sep='&';; esac
    auth="${sep}access_token=$token"
    echo "  type $type, url $(printf '%s' "$url" | redact)"
    if [ "$type" = "TERRAIN" ]; then
        base="${url%/}/"; base="${base%layer.json/}"
        fetch "layer.json" "${base}layer.json${auth}"
        tile="${base}12/2385/2958.terrain${auth}"
        fetch "tile, plugin Accept + gzip" "$tile" -H 'Accept: application/vnd.quantized-mesh,application/octet-stream;q=0.9,*/*;q=0.01' -H 'Accept-Encoding: gzip'
        fetch "tile, CesiumJS Accept" "$tile" -H 'Accept: application/vnd.quantized-mesh;extensions=octvertexnormals-metadata,application/octet-stream;q=0.9,*/*;q=0.01'
        fetch "tile, Accept */*" "$tile" -H 'Accept: */*'
        fetch "tile, no gzip" "$tile" -H 'Accept: */*' -H 'Accept-Encoding: identity'
        fetch "tile, Authorization header" "${base}12/2385/2958.terrain" -H "Authorization: Bearer $token"
        accept='Accept: application/vnd.quantized-mesh,application/octet-stream;q=0.9,*/*;q=0.01'
        # Geographic TMS tiles (2 x 1 at level 0) containing TERRAIN_AT, up to
        # the deepest level layer.json lists as available.
        layer="$(curl -sS -m 30 "${base}layer.json${auth}" 2>/dev/null)"
        tiles="$(printf '%s' "$layer" | TERRAIN_AT="$TERRAIN_AT" python3 -c '
import json, math, os, sys
lat, lon = (float(v) for v in os.environ["TERRAIN_AT"].split(","))
try:
    layer = json.load(sys.stdin)
except ValueError:
    sys.exit(0)
available = layer.get("available") or []
deepest = min(len(available) - 1, 16) if available else 14
print("levels", deepest, "scheme", layer.get("scheme"), "extensions", ",".join(layer.get("extensions") or []), file=sys.stderr)
for z in range(deepest + 1):
    x = int((lon + 180) / 360 * (2 << z))
    y = int((lat + 90) / 180 * (1 << z))
    print(z, x, y)
')"
        echo "  -- one tile per level at $TERRAIN_AT"
        printf '%s\n' "$tiles" | while read -r z x y; do
            [ -n "$z" ] && fetch "z$z $x/$y" "${base}$z/$x/$y.terrain${auth}" -H "$accept" -H 'Accept-Encoding: gzip'
        done
        deep="$(printf '%s\n' "$tiles" | tail -1)"
        if [ -n "$deep" ]; then
            set -- $deep
            echo "  -- burst: $BURST tiles in parallel around z$1 $2/$3"
            start=$(date +%s.%N)
            for i in $(seq 0 $((BURST - 1))); do
                dx=$((i % 6 - 3)); dy=$((i / 6 - 2))
                url="${base}$1/$(($2 + dx))/$(($3 + dy)).terrain${auth}"
                curl -sS -m 60 -o /dev/null -w "     $(($2 + dx))/$(($3 + dy)) %{http_code} %{size_download}B %{time_total}s\n" \
                    -H "$accept" -H 'Accept-Encoding: gzip' "$url" 2>&1 | redact &
            done
            wait
            echo "  burst took $(python3 -c "import time; print(round(time.time() - $start, 2))")s"
            set --
        fi
    elif [ "$type" = "IMAGERY" ] && [ "${url%/}/" = "$url" ]; then
        # ion-hosted imagery is TMS: tilemapresource.xml, then one tile per
        # listed level over TERRAIN_AT in both tilings and both formats, to
        # tell which one the server actually holds.
        fetch "tilemapresource.xml" "${url}tilemapresource.xml${auth}"
        xml="$(curl -sS -m 30 "${url}tilemapresource.xml${auth}" 2>/dev/null)"
        printf '%s\n' "$xml" | redact | head -c 1500; echo
        printf '%s' "$xml" | TERRAIN_AT="$TERRAIN_AT" python3 -c '
import math, os, re, sys
lat, lon = (float(v) for v in os.environ["TERRAIN_AT"].split(","))
xml = sys.stdin.read()
for order in sorted({int(o) for o in re.findall(r"order=\"(\d+)\"", xml)}):
    n = 1 << order
    mx = int((lon + 180) / 360 * n)
    my = int((1 - math.log(math.tan(math.radians(lat)) + 1 / math.cos(math.radians(lat))) / math.pi) / 2 * n)
    print(order, "mercator", mx, n - 1 - my)
    print(order, "geographic", int((lon + 180) / 360 * 2 * n), int((lat + 90) / 180 * n))
' | while read -r z kind x y; do
            for ext in jpg png; do
                fetch "z$z $kind $x/$y.$ext" "${url}$z/$x/$y.$ext${auth}"
            done
        done
    else
        fetch "file, gzip" "${url}${auth}" -H 'Accept-Encoding: gzip'
        fetch "file, identity" "${url}${auth}" -H 'Accept-Encoding: identity'
        fetch "file, Authorization header" "$url" -H "Authorization: Bearer $token"
    fi
done
