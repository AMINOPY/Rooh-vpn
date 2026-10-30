# Chained upstream integration gate

## Implemented

- `EngineLaunchOptions.chainedSocksPort` selects an already-running loopback-only SOCKS5 listener.
- `ConnectionCoordinator` performs a bounded SOCKS5 no-auth handshake before applying the profile adapter.
- Xray profiles are chained with `proxySettings.tag`; sing-box profiles use `detour`.
- The adapters reject non-loopback hosts, invalid ports, ambiguous proxy outbounds, tag collisions, and pre-existing chains.
- Existing sing-box outbounds are preserved; temporary source and transformed profile buffers are cleared after launch.
- JVM tests cover adapter transformation and coordinator handshake behavior.

## Separate Aether mode

The Aether backend is a standalone VPN mode. It starts the pinned Aether engine, establishes one Android TUN, and forwards that TUN through the upstream HEV JNI pthread bridge to Aether's local SOCKS5 listener.

It is **not** yet a managed upstream for Xray/sing-box. Starting the current Aether `VpnService` beside an Xray/sing-box `VpnService` would create competing TUN owners and is not an acceptable chaining implementation.

## Required managed-upstream architecture

To use Aether (or Psiphon) as an upstream for Xray/sing-box:

1. Start the upstream in proxy-only mode, without establishing a second Android TUN.
2. Keep its SOCKS listener bound to loopback and verify the SOCKS5 handshake.
3. Ensure upstream process sockets bypass the inner VPN TUN, without excluding unrelated app traffic or weakening socket protection.
4. Start exactly one inner `VpnService` / TUN owner for Xray or sing-box.
5. Apply the Xray `proxySettings.tag` or sing-box `detour` profile transformation.
6. Monitor upstream and inner-engine health; on either failure, stop the inner engine and keep traffic fail-closed.
7. On disconnect, stop the inner engine first, then the upstream, then release TUN and socket-protection resources.

## Psiphon constraints

The pinned Aether engine can spawn its bundled Psiphon client, and CI stages the Psiphon executable. However, Psiphon's SOCKS path is TCP-only. The full-device TUN path needs the DNS/UDP-aware front used by the upstream Aether Android app; simply pointing HEV at Psiphon's raw SOCKS listener is not a valid UDP/DNS integration. The RØH UI therefore does not advertise Aether→Psiphon as a working full-device mode yet.

## Release gates

- [x] Add pinned Aether/HEV native source build and ABI staging.
- [x] Add standalone Aether service and HEV in-process TUN bridge.
- [x] Add Xray/sing-box profile-side SOCKS5 adapters.
- [ ] Confirm Android CI success for the latest PR head.
- [ ] Add a proxy-only managed Aether upstream and single-TUN chaining with Xray/sing-box.
- [ ] Integrate Psiphon bootstrap/trust assets and its UDP/DNS-aware front before exposing that mode.
- [ ] Confirm DNS, TCP, UDP, IPv4 and IPv6 behavior through each complete chain.
- [ ] Verify no direct traffic escapes on startup, upstream failure, engine crash, network handoff, or disconnect.
- [ ] Run physical-device soak tests and attach redacted evidence.
- [ ] Review AGPL corresponding-source and third-party notice obligations before binary distribution.
