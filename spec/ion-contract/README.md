# Cesium ion client contract

This is the behavior every TAK ion client must share: the Java core used by
ATAK (`core/ion-client-java`), and the C++ client planned for VR-TAK in
Phase 2. Each language runs the same JSON cases in `cases/`, so the two clients
can't drift apart.

Everything in these files is fake: tokens, keys, sessions and servers.

## REST surface

All calls are `GET` with `Authorization: Bearer <ion access token>` and
`Accept: application/json`, relative to the API base (see discovery below).
A Self-Hosted server in **single-user mode** accepts requests without a token;
the client then sends no `Authorization` header. Cloud always requires a token.

| Call | Scope | Used for |
|---|---|---|
| `v1/me` | `profile:read` (optional) | "Test connection" shows the user name. A 403 here is not an error. |
| `v1/assets[?search=&limit=&pageToken=&team=]` | `assets:list` | Asset browser |
| `v1/assets/{id}[?team=]` | `assets:read` | Asset details |
| `v1/assets/{id}/endpoint[?team=]` | `assets:read` | Streaming URL plus short-lived credentials |
| `v1/defaults` | – | Global datasets (default imagery, terrain, buildings; quick-add list) |
| `v1/teams` | – | Team workspaces. 403/404/405 mean "no teams" |

The minimum token for the plugins is **`assets:list` + `assets:read`**.

### Behaviors (see `cases/`)

- **API discovery** (`server-config.json`) follows cesium-native
  `CesiumIonClient::Connection::getApiUrl`. An override wins. Cloud uses
  `https://api.cesium.com/`. Self-Hosted reads `apiHostname` from
  `<server>/config.json`, and falls back to `<server>/api/` if it can't. HTTPS
  is required unless the user explicitly allows cleartext, or the host is
  loopback.
- **Endpoints** (`endpoints.json`):
  - An ion-hosted asset's URL, token and attributions become `ION_HOSTED`.
  - `externalType: 3DTILES` with `options.url` becomes `EXTERNAL_3D_TILES`. Google
    Photorealistic 3D Tiles (asset 2275207) arrive this way, with the Google
    `key` in the URL.
  - `BING`, `GOOGLE_2D_MAPS` and `STK_TERRAIN_SERVER` have their own variants.
  - Anything else is `UNSUPPORTED`.
  - Credentials are always split out of URLs and re-applied only to requests
    on the endpoint's own origin.
- **URL derivation** (`url-derivation.json`):
  - References resolve per RFC 3986 and keep implicit-tiling templates.
  - Children inherit the parent's query parameters on the same origin. This is
    how Google's `key` (from the root URL) and `session` (from the first child)
    reach every tile.
- **Redaction** (`redaction.json`): no token, key or session may appear in
  logs, UI or errors.
- **Asset lists** (`assets.json`):
  - `status` may be a string or `{type}`.
  - Items without an id are skipped.
  - Paging uses `nextPageToken` or a same-origin `Link: rel="next"` header.
- **Tileset references** (`tileset-references.json`): which strings a tile proxy
  must route (`uri`, `url`, `schemaUri`), and the requirement that rewriting
  leaves every other byte untouched.

### Verified vs. assumed

The endpoint shapes for ion-hosted assets, Bing, Google 2D, and Google
Photorealistic 3D Tiles (`externalType: 3DTILES` + `options.url`) were proven
against real ion by the earlier web prototype (removed; in the git history). The rest is assumed and needs
confirming against live servers (these items are in the Phase 1 test plan):

- `team=` scoping on asset calls (carried over from the prototype)
- pagination with more than 100 assets
- Self-Hosted `config.json` discovery

## Case file format

Each file has a `description`, then arrays of cases. Expected values are exact
strings. Error cases name an `IonException.Kind`:

```
CONFIGURATION | UNAUTHORIZED | FORBIDDEN | NOT_FOUND | RATE_LIMITED | SERVER | NETWORK | INVALID_RESPONSE
```

To add a behavior, add a case here first, then make each client pass it.
