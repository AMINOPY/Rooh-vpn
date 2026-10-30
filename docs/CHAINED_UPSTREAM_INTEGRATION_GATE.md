# Chained upstream integration gate

## Implemented in this branch

- `EngineLaunchOptions.chainedSocksPort` selects an already-running, loopback-only SOCKS5 listener.
- `ConnectionCoordinator` performs a bounded SOCKS5 no-auth readiness handshake before transforming the profile.
- Xray profiles are chained with `proxySettings.tag`; sing-box profiles are chained with `detour`.
- The adapters accept loopback only, validate the port, reject ambiguous proxy outbounds, tag collisions, and existing chains.
- The transformed profile is passed to the selected backend; original and transformed profile buffers are zeroed after the launch attempt.
- Coordinator tests verify the SOCKS5 handshake and that the transformed Xray profile reaches backend validation.


## Not implemented / not release-ready

These adapters do not start Psiphon or Aether, do not create a SOCKS listener, do not own an Android VPN TUN, and do not establish an end-to-end tunnel. They must not be exposed as a working connection mode until a real upstream runtime is integrated and device-tested.

## Required runtime sequence

1. Start a proxy-only upstream runtime (no second `VpnService` / TUN) and wait for an explicit ready signal.
2. Confirm its SOCKS listener binds only to loopback and perform a protocol-level health check.
3. Start exactly one Android `VpnService` / TUN owner for Xray or sing-box.
4. Start the inner engine with the corresponding adapter output and ensure every relevant outbound, including DNS, follows the intended chain.
5. If the upstream exits or becomes unhealthy, stop the inner engine and keep traffic fail-closed; never fall back silently to direct.
6. On disconnect, stop the inner engine first, then the upstream, then release the TUN and socket-protection resources.

## Aether / Psiphon source and licensing

Do not copy Aether application code or package its native runtime until the exact upstream revision, native build inputs, third-party notices, and applicable AGPL/GPL obligations have been reviewed for this product's distribution model. Do not bundle Psiphon client configuration identifiers or credentials that are not issued for this app. A dependency declaration alone is not an integration.

## Release gates

- [ ] Confirm and document the AGPL/GPL-compatible distribution and corresponding-source path for Aether/Psiphon components.
- [x] Add a pinned Aether/HEV native build script and ABI packaging checks to Android CI.
- [x] Add a standalone Aether service with one TUN owner, app-package loop prevention, startup readiness, and guarded teardown.
- [ ] Wire Aether as a managed upstream for Xray/sing-box without introducing a second TUN owner.
- [ ] Add full Psiphon runtime lifecycle and credentials/bootstrap handling.
- [ ] Confirm DNS/TCP/UDP and IPv4/IPv6 behavior through the full chain.
- [ ] Verify no direct traffic escapes on startup, upstream failure, engine crash, network handoff, or disconnect.
- [ ] Run physical-device soak tests and attach redacted evidence.
