# Cesium ion Self-Hosted 1.10.0: asset server crashes on requests for terrain tiles that do not exist

Found while testing the Cesium ion TAK plugin against a single-user ion Self-Hosted 1.10.0 install
(Helm chart `cesium-ion-1.10.0`, MicroK8s in WSL2). Reproduced with plain `curl`, so this is a
server issue, independent of the plugin.

## Summary

Requesting a quantized-mesh tile that the terrain asset does not contain makes
`cesium-ion-asset-server` recurse without end in its terrain reader. The process overflows its
stack and exits with code 2, after a 45 s hang or a dropped connection. Kubernetes restarts the
pod, and repeated crashes put it in `CrashLoopBackOff`. While the pod is down it serves nothing:
imagery, 3D Tiles and terrain all fail for every client.

Tiles that the asset *does* contain are served normally, in about 1 ms.

## Asset

A terrain asset (here #9, built from a small Philadelphia DEM; the stored tileset is 2.8 MB). Its
`layer.json` has no `available` list, only the metadata extension:

```json
{"extensions":["metadata","octvertexnormals"],"metadataAvailability":10,"maxzoom":12,
 "minzoom":0,"projection":"EPSG:4326","scheme":"tms","format":"quantized-mesh-1.0", ...}
```

The level-0 tiles' metadata lists, for example: z8 149/185, z9 298/371 and z10 596..597/742..743.

## Reproduce

Use `ENDPOINT=$(curl -s http://<api>/v1/assets/9/endpoint)` and take `url` and `accessToken` from it.
Then request each tile on its own, giving the asset server time to restart between requests:

| Request | Listed in metadata? | Result |
|---|---|---|
| `8/149/185.terrain` | yes | 200, 315 B, ~1 ms |
| `9/298/371.terrain` | yes | 200, 1446 B, ~1 ms |
| `10/596/742.terrain` | yes | 200, 2287 B, ~1 ms |
| `8/149/184.terrain` | no | hangs 45 s, then the pod restarts |
| `9/298/369.terrain` | no | connection dropped, pod restarted |
| `10/596/739.terrain` | no | hangs 45 s, then the pod restarts |
| `12/2385/2957.terrain` | no (beyond the level-10 metadata) | hang, then the pod restarts |

The Accept header makes no difference (`application/vnd.quantized-mesh`, with or without
`extensions=octvertexnormals-metadata`).

## Stack (repeating frames shortened)

```
github.com/CesiumGS/asset-server/lib.(*TerrainReader).checkParentTile(..., 0x254, 0x2e3, ...)
        asset-server@v1.10.0/lib/terrainReader.go:271
github.com/CesiumGS/asset-server/lib.(*TerrainReader).hasTile(..., 0xa, 0x254, 0x2e3, ...)
        asset-server@v1.10.0/lib/terrainReader.go:240
   ... (the two frames above repeat with identical arguments: level 10, x 596, y 739) ...
github.com/CesiumGS/asset-server/lib.(*TerrainReader).checkParentTile(..., 0x951, 0xb8e, ...)
        asset-server@v1.10.0/lib/terrainReader.go:265
github.com/CesiumGS/asset-server/lib.(*TerrainReader).hasTile(..., 0xc, 0x951, 0xb8e, ...)
        asset-server@v1.10.0/lib/terrainReader.go:240
github.com/CesiumGS/asset-server/lib.(*TerrainReader).GetTile(..., 0xc, 0x951, 0xb8e, ...)
        asset-server@v1.10.0/lib/terrainReader.go:408
github.com/CesiumGS/asset-server/handlers/terrain.GetTerrainTileImplementation(...)
        asset-server@v1.10.0/handlers/terrain/getTile.go:48
github.com/CesiumGS/cesium-ion-self-hosted/asset-server/lib.routeTerrainRequest(...)
        packages/ion-asset-server-go/lib/routeFromUri.go:156
```

The container's last state was `terminated`, reason `Error`, exit code 2. It was not
`OOMKilled` (the 4 Gi limit was not reached; the node had 13 GB free).

`hasTile(10, 596, 739)` calls `checkParentTile`, which calls `hasTile(10, 596, 739)` again. The
level-10 metadata tile appears to resolve to itself as its own metadata parent: level 10 is a
multiple of `metadataAvailability`. So availability is never decided and the recursion never ends.

## Expected

An unavailable tile should get a `404` (as Cesium ion SaaS returns), not a crash. A request
should not be able to take down the asset server for every other client and asset.

## Workaround in the TAK plugin

From commit `0a0ca03`, the plugin reads availability from the tiles' metadata extension, as
CesiumJS does. It requests only tiles that are listed, so it no longer triggers this crash.
Clients that probe for tiles still do. Other workarounds would be to give the terrain asset an
explicit `available` list, or to rate-limit and recover the asset server.
