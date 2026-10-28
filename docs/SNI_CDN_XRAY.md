# Xray SNI and CDN transport support

## Scope

The app accepts optional launch-time overrides for an imported Xray JSON profile:

- **SNI**: the TLS `serverName`, or the REALITY `serverName` when the profile uses REALITY and no CDN edge IP is supplied.
- **CDN edge IP**: a literal IPv4 or IPv6 address that replaces the single configured proxy endpoint address while retaining its port and credentials.
- **HTTP host / authority**: an independent hostname used by WebSocket, HTTPUpgrade, gRPC, or XHTTP. It may differ from SNI when the server/CDN is configured for that arrangement.

The overrides are passed through `EngineLaunchOptions` to `XrayEngineBackend`, then applied to a copy of the profile immediately before the Android TUN inbound is added and Xray is started. The original imported profile remains in memory and is not rewritten on disk.

## Supported combinations

| Profile transport | TLS SNI | Edge IP | Host override |
| --- | --- | --- | --- |
| WebSocket (`ws`) | TLS | TLS only | `wsSettings.host` |
| HTTPUpgrade | TLS | TLS only | `httpupgradeSettings.host` |
| gRPC | TLS | TLS only | `grpcSettings.authority` |
| XHTTP (`xhttp`) | TLS | TLS only | `xhttpSettings.host` |
| Legacy SplitHTTP (`splithttp`) | TLS | TLS only | `splithttpSettings.host` |
| Other TLS transports | TLS | Rejected | Rejected unless host semantics are explicitly supported |
| REALITY | `realitySettings.serverName` | Rejected | Transport-dependent; not a CDN TLS-termination mode |

For a CDN edge IP, the adapter requires TLS and one supported HTTP-based transport. It does not silently turn REALITY or raw TCP into a CDN tunnel. An IPv6 edge is accepted as a literal; the adapter does not resolve hostnames for the edge-IP field.

## Validation and security properties

- SNI and host values are normalized as DNS hostnames; whitespace, paths, IP literals, and malformed labels are rejected.
- CDN addresses must be literal IPv4/IPv6 values. DNS is never invoked to parse the edge-IP field.
- The adapter requires exactly one supported proxy outbound and exactly one server endpoint before changing the address. Ambiguous multi-outbound profiles are rejected rather than partially rewritten.
- TLS certificate validation settings, credentials, port, transport path, gRPC service name, XHTTP mode, and other transport parameters are preserved.
- `allowInsecure` is never enabled automatically.
- Current Xray WebSocket configuration uses `wsSettings.host`; the adapter avoids relying on the deprecated `headers.Host` convention.
- A successful transformation means only that the profile is structurally compatible with the requested override. It does not guarantee that the selected CDN routes to the origin or that the origin accepts the chosen SNI, Host/authority, path, UUID, or TLS certificate.

## External references

- Project X TLS documentation: https://xtls.github.io/en/config/transports/tls.html
- Project X transport documentation: https://xtls.github.io/en/config/transport.html
- Xray XHTTP design discussion: https://github.com/XTLS/Xray-core/discussions/4113
- Shiro Khorshid Android project: https://github.com/shirokhorshid/shirokhorshid-android
- Shiro Khorshid Scanner: https://github.com/mwhammadrezss/ShiroKhorshidScanner

The Shiro Khorshid/Psiphon domain-fronting approach and Xray's TLS + HTTP transport approach are related at a high level but are not interchangeable implementations. This app uses Xray's own configuration schema and does not import Psiphon code or assume that a CDN supports arbitrary tunnel protocols.

## Verification gate

Unit tests cover WebSocket, HTTPUpgrade, gRPC, XHTTP, legacy SplitHTTP, IPv6 literals, SNI-only REALITY, malformed values, ambiguous profiles, and preservation of TLS verification settings. CI success verifies build/tests only. A real device and a server/CDN configured for the same transport are still required to verify connection establishment, TCP/UDP/DNS forwarding, IPv4/IPv6 behavior, and leak resistance.
