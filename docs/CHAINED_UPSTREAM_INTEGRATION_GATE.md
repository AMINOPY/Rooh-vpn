# Chained upstream integration gate

## Implemented in this branch

- A profile adapter can attach exactly one Xray proxy outbound to a local SOCKS upstream using Xray `proxySettings.tag`.
- A profile adapter can attach exactly one sing-box proxy outbound to a local SOCKS upstream using `detour`.
- Both adapters accept loopback addresses only, validate the port, reject ambiguous proxy outbounds, reject tag collisions, and refuse to overwrite an existing chain.
- Unit tests cover the transformation and the principal rejection paths.

## Not implemented / not release-ready

These adapters do not start Psiphon or Aether, do not create a SOCKS listener, do not own an Android VPN TUN, and do not establish an end-to-end tunnel. They must not be exposed as a working connection mode until a real upstream runtime is integrated and device-tested.

## Required runtime sequence

1. Start the selected upstream runtime and wait for an explicit ready signal.
2. Confirm its SOCKS listener binds only to loopback and perform a protocol-level health check.
3. Start exactly one Android `VpnService` / TUN owner.
4. Start Xray or sing-box with the corresponding adapter output and ensure every relevant outbound, including DNS, follows the intended chain.
5. If the upstream exits or becomes unhealthy, stop the inner engine and keep traffic fail-closed; never fall back silently to direct.
6. On disconnect, stop the inner engine first, then the upstream, then release the TUN and socket-protection resources.

## Aether / Psiphon source and licensing

Do not copy Aether application code or package its native runtime until the exact upstream revision, native build inputs, third-party notices, and applicable AGPL/GPL obligations have been reviewed for this product's distribution model. Do not bundle Psiphon client configuration identifiers or credentials that are not issued for this app. A dependency declaration alone is not an integration.

## Release gates

- [ ] Confirm approved licensing path for Aether and Psiphon components.
- [ ] Build and package native runtime for every advertised Android ABI with reproducible checksums.
- [ ] Implement a single TUN owner and socket-protection contract.
- [ ] Implement upstream startup readiness, crash detection, teardown, and reconnect.
- [ ] Confirm DNS/TCP/UDP and IPv4/IPv6 behavior through the full chain.
- [ ] Verify no direct traffic escapes on startup, upstream failure, engine crash, network handoff, or disconnect.
- [ ] Run physical-device soak tests and attach redacted evidence.
