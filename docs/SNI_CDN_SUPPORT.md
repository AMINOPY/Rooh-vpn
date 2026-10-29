# Xray SNI and CDN endpoint overrides

## Implemented configuration path

The Android Xray launch contract accepts three optional values:

- `xraySni`: TLS `tlsSettings.serverName`, or REALITY `realitySettings.serverName` for SNI-only overrides.
- `xrayCdnEdgeIp`: a literal IPv4/IPv6 edge address. When present, the adapter replaces the single proxy outbound's server address while preserving its port and credentials.
- `xrayHostHeader`: the application-layer host value. It maps to WebSocket `wsSettings.headers.Host`, gRPC `grpcSettings.authority`, or XHTTP `xhttpSettings.host`.

For CDN endpoint mode, the adapter requires TLS and a compatible HTTP-based transport: WebSocket, gRPC, or XHTTP. It does not claim CDN fronting for REALITY, plain TCP, or arbitrary transports. SNI and Host can be different, but only when the selected CDN and origin are configured to accept that combination.

## Security and fail-closed behavior

- TLS certificate verification is not disabled by the adapter. Existing `allowInsecure` settings are preserved rather than forced on.
- SNI and host values are validated as DNS hostnames; CDN endpoints must be literal IP addresses. No implicit DNS lookup is used to turn a hostname into a CDN IP.
- The Xray TUN adapter requires exactly one supported proxy outbound. It inserts a highest-priority route from the app-owned TUN inbound to that outbound, so a lower-priority direct rule cannot accidentally carry full-tunnel traffic outside the proxy.
- A profile that has no unambiguous proxy outbound, an existing TUN inbound owned elsewhere, or incompatible transport settings is rejected instead of being started with guessed values.
- Socket protection is fail-closed: Xray's default TCP/UDP dialer rejects a socket if Android `VpnService.protect(fd)` cannot be confirmed.
- Native shutdown errors are propagated to Android so the service can retain TUN/protection resources when stop is not confirmed.

## CDN research and compatibility

The official Xray TLS documentation describes `serverName` as the hostname sent in TLS SNI and notes that certificate validation remains important:
https://xtls.github.io/en/config/transports/tls.html

XHTTP is a transport, not a replacement for VLESS/VMess/Trojan credentials. Its host, path, mode, and extra settings must match the provider's server configuration:
https://onexray.com/docs/protocols/xhttp/

The community ShirOKhorshid CDN IP Finder demonstrates the separate concepts of CDN edge IP, SNI hostname, and ISP-specific endpoint testing:
https://github.com/miladtahanian/ShirOKhorshid-IP-Finder

RØH does not copy static CDN IP ranges from those projects into its defaults because edge IP reachability changes by provider, region, ISP, and time. A reachable TLS edge is not proof that the CDN will forward traffic to a given origin. End-to-end success requires a compatible CDN/origin configuration and real traffic verification.

## Test and release gate

Unit tests cover SNI-only TLS and REALITY overrides, CDN edge replacement, WebSocket/gRPC/XHTTP host mapping, malformed inputs, unsupported transport combinations, ambiguous outbounds, and the TUN route guard.

These tests verify configuration transformation only. They do not prove that a specific CDN edge is reachable, that an origin accepts the Host/authority, or that a real Android device successfully forwards TCP/UDP/DNS. Those checks remain a device-level release gate.
