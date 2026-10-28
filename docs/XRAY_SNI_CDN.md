# Xray SNI and CDN endpoint overrides

## Scope

RØH's Xray profile preparation layer now accepts explicit transport overrides:

- `sni`: TLS SNI for ordinary TLS profiles, or `realitySettings.serverName` for REALITY profiles.
- `cdnEdgeIp`: a literal IPv4/IPv6 endpoint address, never a hostname. When set, the adapter currently supports Xray TLS + WebSocket or TLS + gRPC profiles.
- `hostHeader`: optional HTTP Host for WebSocket or gRPC authority. It can differ from SNI when the CDN/origin is deliberately configured for that pairing.

The transform is applied before the Android TUN inbound is inserted by `XrayTunProfileAdapter.prepare(configuration, transportOverrides)`. Existing credentials, paths, ports, TLS verification flags, routing rules, and unrelated outbounds are preserved.

## Important compatibility limits

- This is a profile transformation, not a guarantee that an arbitrary CDN will forward the tunnel. The CDN distribution and origin must explicitly support the chosen hostname/Host/authority and transport.
- CDN endpoint override is intentionally rejected for REALITY, non-TLS security, unsupported transports, ambiguous multi-proxy profiles, and multi-server outbound layouts.
- SNI-only overrides support TLS and REALITY. A Host/authority override without a CDN IP is limited to WebSocket and gRPC.
- The adapter never turns off certificate validation or enables `allowInsecure`.
- IP reachability, TCP/TLS handshake, HTTP 101 WebSocket upgrade / gRPC response, authentication, throughput and stability are not inferred from syntactic validity. Those need real network tests against a user-authorized endpoint.
- A CDN edge IP can be reachable while the hostname/origin mapping is invalid. ICMP ping alone is not proof that a tunnel works.
- This layer does not yet expose controls in the app UI or register a working Xray VPN backend. It is deliberately kept separate until the Xray Android runtime, TUN lifecycle and socket-protection callback are integrated and verified end-to-end.

## Test coverage

Unit tests cover TLS SNI, REALITY SNI, CDN IP rewrite, distinct WebSocket Host, gRPC authority, invalid host/IP values, ambiguous/multi-server configurations, TLS verification preservation and integration with TUN profile preparation.

## References used for independent implementation

- Xray official example: VLESS + WebSocket + TLS, showing `streamSettings.network`, `wsSettings.headers.Host` and `tlsSettings.serverName`: https://github.com/XTLS/Xray-examples/tree/main/VLESS-WSS-Nginx
- Xray Android TUN integration example: https://github.com/atlasru/dot/blob/main/docs/VPN_ENGINE.md
- ShirOKhorshid Android project (studied for its public CDN Fronting feature description; its GPL-3.0 source is not copied into this adapter): https://github.com/shirokhorshid/shirokhorshid-android
- Independent CDN IP finder documentation (used only to understand the separate roles of edge IP and SNI/Host; no scan lists are bundled): https://github.com/hossein8360/cdn-ip-finder

This implementation is an independent Kotlin transformation layer. It does not copy GPL-licensed code from ShirOKhorshid or other GPL projects.
