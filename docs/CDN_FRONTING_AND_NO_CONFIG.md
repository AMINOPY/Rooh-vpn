# Xray SNI, CDN fronting, and no-manual-config bootstrap

## Current implementation

- `XrayCdnProfileAdapter` can apply explicit TLS SNI, CDN edge IP, and HTTP Host/authority overrides to one unambiguous Xray proxy outbound.
- Supported CDN-facing transports are TLS WebSocket, gRPC, HTTPUpgrade, and XHTTP/SplitHTTP (subject to the pinned Xray schema and the remote server's configuration).
- SNI-only changes support TLS and REALITY. A CDN edge override is rejected for REALITY because a generic CDN edge cannot terminate and forward REALITY as if it were ordinary HTTPS.
- TLS certificate verification remains enabled; the adapter never enables `allowInsecure`.
- The Android Xray TUN adapter routes packets entering its app-owned TUN to the selected proxy outbound. Startup is not proof of successful remote egress.

## Automatic/no-manual-config path

`XrayFrontingDirectory` and `XrayFrontingAutoConnector` implement the client-side bootstrap contract:

1. Download a bounded manifest over HTTPS, with redirects disabled.
2. Verify an Ed25519 signature over the exact payload bytes using an app-pinned X.509 public key.
3. Reject expired directories, expired entries, malformed profiles, unsupported transport combinations, and invalid endpoint overrides.
4. Probe a bounded number of edge IPs on TCP/443, record actual connect time, and rank only fresh, reachable measurements.
5. Try candidates in measured order. If engine cleanup after a failed start is unconfirmed, do not start another candidate over the potentially live tunnel.

The manifest carries a complete Xray profile because Xray needs an actual remote relay address, protocol, authentication material, and compatible server-side transport. The end user need not import a profile manually, but the app operator must deploy and pin a trusted directory that distributes credentials for relays they control or are authorized to use.

## Why arbitrary SNI + CDN IP is not enough

SNI is sent during TLS negotiation; HTTP Host/authority is a separate HTTP-layer value. The CDN edge must accept the SNI, the origin must accept the Host/authority and path/service name, and the remote service must implement the matching VLESS/VMess/Trojan or other selected protocol. A random CDN IP plus a public domain does not create a tunnel and often fails by design.

A genuine no-config service also needs a maintained relay fleet, credential issuance/rotation, signed directory publishing, health checks, abuse prevention, and a stable trust root. A client-only change cannot invent that network. Psiphon-derived clients illustrate this architecture: they rely on a maintained tunnel core and service-side infrastructure, not only a SNI field.

## Remaining release gates

- Configure a production HTTPS directory URL and pin its Ed25519 public key in the app's release configuration. No endpoint or signing key is fabricated in this repository.
- Deploy compatible relay endpoints and issue short-lived client profiles; never publish private server keys in the client manifest.
- Add end-to-end health confirmation after Xray starts, beyond TCP/443 reachability.
- Test real HTTPS egress, DNS, UDP, IPv4/IPv6, certificate validation, failure rollback, and leak behavior on physical devices and multiple networks.
- Keep auto-fronting disabled when the pinned directory is absent, signature verification fails, or no candidate passes validation. Do not fall back to arbitrary unsigned public config feeds.
