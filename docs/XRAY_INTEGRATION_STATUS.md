# Xray-core Android integration status

The repository pins AndroidLibXrayLite in the `xray-core` submodule. The pinned binding exposes `CoreController.StartLoop(configContent, tunFd)`, and its Android TUN implementation duplicates the supplied descriptor internally while leaving the original owned by the application. This supports a direct Xray-to-Android-TUN path; a SOCKS-to-TUN bridge is not required for this pinned TUN inbound.

## Changes now staged on `feat/rohvpn-engine-foundation`

- CI builds the Xray AAR from the pinned submodule before Gradle and packages the pinned `geoip.dat` / `geosite.dat` databases as Android assets.
- The Xray AAR is declared as the primary runtime. The sing-box `libbox` AAR is compile-only to avoid packaging two gomobile runtimes that both expose duplicate `go.Seq` classes. CI checks the runtime dependency graph and native library packaging.
- A reproducible patch adds a `SocketProtector` callback to AndroidLibXrayLite and registers it with Xray's default TCP/UDP system dialer. A socket is rejected if `VpnService.protect(fd)` returns false; no unprotected fallback is allowed. CI applies the patch to the pinned submodule and runs the Go tests before building the AAR.
- `XrayTunProfileAdapter` validates an imported JSON profile, rejects a profile that already contains a TUN inbound owned elsewhere, and adds a controlled TUN inbound while preserving existing outbounds and routing rules.
- `XrayVpnService` owns the Android TUN descriptor and foreground lifecycle, hands the descriptor to `CoreController.StartLoop`, and retains resources if shutdown cannot be confirmed.
- `XrayEngineBackend` is registered with the common coordinator; profile import selects Xray for Xray JSON and WireGuard for wg-quick profiles.

These are source/build integration changes. They are **not yet proof of a safe, working end-to-end tunnel**.

## SNI/CDN transport groundwork

- `XrayCdnProfileAdapter` applies explicit TLS SNI, CDN edge-IP and HTTP Host/authority overrides to a single supported proxy outbound. It supports TLS with WebSocket, gRPC, HTTPUpgrade and XHTTP/SplitHTTP settings when the profile contains the required native settings.
- `XrayTransportCapabilityAnalyzer` reports structural compatibility before the options are presented in UI. It distinguishes SNI support from CDN edge-IP support and emits stable, non-secret limitation codes.
- `XrayTunProfileAdapter` applies transport overrides before adding the Android TUN inbound and its top-priority proxy route, so existing route rules cannot silently send packets from the app-owned tunnel to a direct outbound.
- TLS certificate verification remains enabled; profiles with `allowInsecure=true` are rejected by the tunnel adapter. No DNS/network probing or claim of CDN reachability is performed by the analyzer.

Important protocol distinction: SNI selects the TLS name sent in ClientHello, while HTTP Host/gRPC authority selects the application-layer host. A CDN edge IP alone does not make an origin reachable; the edge must route the selected host/path to an origin configured for the same protocol and credentials. Reality's `serverName` is supported as an SNI override, but this adapter intentionally does not treat a Reality endpoint as a generic TLS-CDN edge override.

## Required before Xray can be called complete

1. Confirm the latest branch compiles against the generated gomobile API and that the AAR/runtime packaging checks pass.
2. Audit all outbound paths in the pinned Xray revision. The callback currently hooks the default system dialer; custom dialers or protocol-specific socket paths must not bypass Android socket protection.
3. Verify the exact TUN inbound settings and routing behavior against the pinned Xray version, including UDP and DNS packet handling.
4. Exercise start/stop, failed startup rollback, VPN revocation, process death, and Wi-Fi/mobile handover on emulator and physical Android devices.
5. Record real HTTP(S) egress, TCP/UDP traffic, DNS success and leak checks, IPv4/IPv6 behavior, MTU behavior, and socket-protection evidence.
6. Do not claim a kill switch or leak-free operation until those tests pass.

## Security behavior

- The app validates imported Xray JSON before starting native code; structural preflight is not a substitute for Xray's own native config parser.
- The TUN descriptor remains owned by the service and is closed only after `StopLoop` is confirmed.
- If Xray cannot confirm shutdown, the coordinator must keep the backend reserved and the service must retain the descriptor/protector rather than silently starting another engine.
- Socket protection failure is fatal for the affected dial. No unprotected connection fallback is permitted.
- Xray profiles are held in memory only by the current import screen. No server endpoint, credential, or provider account is fabricated by this client.

## Current release gate

**PARTIALLY PASSED.** CI passed on commit `53324a26da5771cf3027ffc62af75f68dea4cc53`, including Go tests after applying the socket-protection patch, Xray AAR generation, APK assembly, runtime/asset packaging checks, and Android unit tests. The release gate remains **NOT PASSED** until real-device traffic tests establish TCP/UDP/DNS forwarding, IP-family behavior, socket-protection coverage, network recovery and leak resistance. CI success alone cannot prove these properties.
