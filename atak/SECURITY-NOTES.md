# Cesium ion for ATAK: static-analysis findings and their handling

The first TAK.gov Third-Party Pipeline submission (21 October 2026, version
1.0.0) produced a Fortify report (30 findings) and an OWASP
dependency-check report. This file records each finding and what was done.
It ships in the submission package as `SECURITY-NOTES.md`.

## Summary

| Priority | Submission 1 | Submission 2 (signed) |
|---|---|---|
| Critical | 5 | 1 |
| High | 11 | 5 |
| Medium | 3 | 2 |
| Low | 11 | 9 |
| **Total** | **30** | **17** |

Submission 2 (21 October 2026) built, passed and was signed. Every finding
it still reports is one of the "by design" items below. The tile proxy's
origin check (see Fixed) did not change Fortify's Server-Side Request
Forgery count: the scanner still traces request data to the upstream
connection.

"Removed": the finding was in unit-test code, which is no longer part of the
submission package (tests stay in the project repository and run in CI).

## Fixed

| Finding | Where | Change |
|---|---|---|
| XML External Entity Injection (Critical), XML Entity Expansion Injection (Medium) | `TmsMetadata.java` | The parser already rejected any document with a DOCTYPE before parsing and set the secure-processing features through a helper, which the scanner could not follow. The features are now set directly on the factory (DOCTYPE disallowed, external general and parameter entities off, external DTD loading off, secure processing on, `ACCESS_EXTERNAL_DTD` and `ACCESS_EXTERNAL_SCHEMA` empty, XInclude off). |
| Server-Side Request Forgery (High, defense in depth) | `TileProxy.java` | The upstream URL was already built from the asset endpoint's origin plus the request's path; the proxy now also refuses any request whose resolved origin differs from the endpoint's. Unit test: `requestPathsNeverChooseTheUpstreamHost`. |

## Removed with the unit tests

- Credential Management: Hardcoded API Credentials (5 High):
  `ContractTest`, `TransportTest`, `IonClientTest`. Fake tokens such as
  `"Bearer fake-user-token"` sent to local test servers.
- Password Management: Hardcoded Password (1 High): `TileProxyTest`, a fixed
  test secret.
- Privacy Violation (3 Critical): `TileProxyTest`, test requests to the
  proxy's loopback URL.
- System Information Leak (2 Low): `TransportTest`, a local test server's
  port.

## Remaining, by design

**Privacy Violation (Critical), `AtakImports.java:110`; Privacy Violation:
Heap Inspection (High), `TileProxy.java:286`.** The value is the tile
proxy's per-install path key, not a user credential. The proxy listens on
127.0.0.1 only and serves only assets the user has added; the key keeps other
apps on the device from using it. ATAK's map engine fetches imagery tiles
from a map-source file, so the proxy URL, key included, has to be written to
that file. The file is written to ATAK's private app storage (not shared
storage). The user's Cesium ion token never appears in it: the proxy adds
credentials only to requests it forwards to the asset's own server. The
Heap Inspection finding is the first 200 bytes of an upstream error
response, kept for the plugin's diagnostics log after redaction.

**Server-Side Request Forgery (3 High), `UrlConnectionTransport.java:73`,
`TileProxy.java:379` and `:381`.** These follow the plugin's two loopback
servers, both by design:

- The OAuth redirect receiver (`LoopbackReceiver`) takes the authorization
  code from the browser redirect and sends it to Cesium ion's fixed token
  endpoint (OAuth 2.0 with PKCE, RFC 7636).
- The tile proxy returns the asset server's response to ATAK.

Neither lets a request choose a scheme, host or port: only `http`/`https`
URLs on the configured ion server or the asset's endpoint origin are opened.

**System Information Leak: External (2 Medium), `TileProxy.java:381`.**
The proxy passes an upstream error response through to ATAK's map engine
on the same device (loopback only).

**Password Management: Password in Comment (8 Low).** Javadoc that uses the
words "token", "secret" or "password" to say what is not stored, for
example "Non-secret settings only". No values.

**Password Management: Null Password (1 Low), `SecureTokenStore.java:110`.**
`KeyStore.getKey(alias, null)` for the Android Keystore, which does not use
key passwords; the key never leaves the Keystore.

## Dependency check

One match: `takdevlint.aar` (TAK's own lint package, added by the takdev
Gradle plugin), matched to Apache SkyWalking CVE-2025-54057 with Low
confidence. It is a build-time lint tool, not part of the plugin, and not
SkyWalking. The plugin itself has no third-party runtime dependencies.
