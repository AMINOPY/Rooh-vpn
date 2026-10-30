# PG CORE + Xray engine integration and fusion gate

This document records the actual integration boundary for RØH VPN. It is a release gate, not a claim that a production tunnel or multi-engine bonding is already implemented.

## Current repository state on the hardening branch

- The Android app includes the PG CORE Android module through `:pg-core-android`.
- PG CORE has a concrete integration chain in its library: the Go mobile engine exposes a loopback SOCKS endpoint, and the pinned Hev JNI backend forwards packets from Android TUN to that endpoint. The service owns the TUN descriptor and passes Android's socket-protection callback to the engine.
- The host app's default PG CORE backend remains deliberately unavailable. The chain is not yet registered as an app `EngineBackend`, and a TUN descriptor or running native process alone is not evidence of traffic forwarding.
- The hardening branch now builds the PG CORE mobile package into the same gomobile AAR as Xray and sing-box. This is specifically to avoid packaging a second gomobile runtime with duplicate `go.Seq` classes. The PG CORE reflection adapter is aligned with that shared package; the exact CI build must pass before this is treated as verified.
- CI fetches the pinned Hev 2.19.3 AAR and checks its SHA-256 before packaging it. That upstream AAR declares Android API 29; the app keeps its existing API 26 minimum, while PG CORE's Hev installer explicitly refuses to run below API 29. Do not bypass this runtime guard.
- PG CORE now exposes explicit service lifecycle states so a future host adapter can distinguish confirmed startup, shutdown, and cleanup failure.
- No real host-side `VpnTrafficProbe` has yet been implemented. The service must remain fail-closed until a probe independently verifies TCP, UDP and DNS traffic through the TUN.
- The existing `FusionFrame` codec is bounded framing only; it is not an authenticated tunnel or a packet-bonding data plane.
- Do not mark PG CORE or multi-engine fusion as available until the host registration, end-to-end probe, and device tests pass.


## Required PG CORE fusion design

“Fusion” means coordinated use of multiple transport/engine paths, not simply combining config files or concatenating framed bytes. A real implementation must define:

1. **Transport model:** whether fusion means active-active packet bonding, health-aware failover, or selecting one engine per session. These are different behaviours and must not be conflated.
2. **Authenticated framing:** bind each session to an authenticated peer; reject replay, out-of-order/duplicate sequence numbers where required, oversized frames, malformed control frames, and unauthenticated payloads. The existing frame codec is not a security protocol.
3. **Packet semantics:** preserve packet boundaries and ordering where required; define fragmentation/reassembly, MTU, congestion/back-pressure, retransmission policy, and how UDP behaves.
4. **Path scheduler:** measure bounded real RTT/loss/throughput per path, stop sending on unhealthy paths, and avoid claiming a “real ping” unless a live endpoint probe actually ran.
5. **Single TUN owner:** one service owns the Android TUN descriptor. Engine adapters must not independently establish competing full-device VPNs. Socket protection must happen before dialing upstream.
6. **Fail-closed lifecycle:** consent, foreground-service start, cancellation, revocation, network handoff, partial startup rollback, stop timeout, descriptor ownership, and secret redaction.
7. **No false availability:** if any required native component or path is unavailable, the app must report degraded/failed state rather than “connected”.

## Required Xray integration

- Resolve the Go-mobile runtime collision with sing-box using one compatible combined binding, or an explicitly isolated process architecture with a real and tested packet bridge. Do not suppress duplicate-class checks.
- Build a narrow adapter around the pinned `libv2ray.CoreController` API and its callbacks.
- Connect Xray to the one TUN descriptor with documented ownership and deterministic close semantics.
- Protect upstream sockets and implement native config validation, cancellation, network-change handling, safe logging and startup rollback.
- Add tests for native load failure, invalid profiles, failed startup cleanup, stop idempotency, and ABI packaging.
- Keep Xray unregistered as a usable engine until the packet path works.

## Release acceptance tests

A green Gradle build and unit-test suite are necessary but insufficient. Before release, run on a real supported Android device:

- TCP HTTPS request through the tunnel
- UDP exchange through the tunnel
- DNS resolution and DNS leak checks
- IPv4 and IPv6 route/leak checks
- Upstream socket protection / no recursive VPN loop
- Wi-Fi to mobile-network handoff and recovery
- Revoke VPN permission during a session
- Force-stop/crash cleanup and reconnect
- Fusion tests with one path removed, high latency, packet loss, duplicate/replayed frames and malformed frames
- Throughput/RTT measurements against controlled endpoints

Record device model, Android version, core versions, test endpoint, results and failures. Do not publish a production-ready claim until these gates pass.
