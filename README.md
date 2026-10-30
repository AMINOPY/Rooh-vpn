# RØH VPN

RØH VPN is an Android VPN client project focused on a reliable, testable networking core and a clear, consent-driven user interface. The current feature branch includes a dark dashboard and dedicated Home, Profiles, Tools, Engines, and Settings sections.

## Project direction

- **Engine priority:** one shared app contains Xray-core, sing-box, and WireGuard adapters where packaged. PG CORE's Android lifecycle/API module and flow-selection primitives are included, but it remains disabled for traffic until a real TUN-to-engine packet-forwarding backend and cooperating path endpoints are integrated and tested. Aether remains integration work.
- **Android tunnel:** Android `VpnService` owns the TUN interface and OS-level routing. An engine process or local proxy alone does not route device traffic.
- **Simple connection:** one-tap connection requires a legitimate server operator or provider-authorized API. This repo intentionally has no fabricated server addresses or embedded credentials.
- **Security:** no hard-coded server credentials, private keys, API tokens, or signing secrets. Local saved profile data uses Android Keystore-backed AES-GCM encryption. Logs must redact sensitive values.
- **Languages:** Kotlin/Java for Android integration; Go/Rust/C/C++ only where an upstream engine or native tunnel requires them; Python for development and CI tooling rather than the privileged runtime without a clear need.

## Implemented in the current feature branch

- Android application module and core lifecycle foundation.
- Common engine contract, capability models, registry, serialized connection coordinator, and a fail-closed RØH engine-fusion role resolver that keeps Xray as parent and prevents competing TUN ownership.
- WireGuard adapter using the official Android tunnel library (pinned dependency), including bounded profile parsing and safe error mapping.
- Encrypted local profile storage backed by Android Keystore AES-GCM.
- Provider-backed server-directory interface, hardened HTTPS discovery/profile clients, bounded concurrent reachability probes, deterministic selection, and auto-connect orchestration boundary.
- Explicit Android VPN-consent flow and profile import/connect/disconnect controls; imports are bounded to 1 MiB, unsupported formats are rejected, and active profile bytes are cleared on disconnect/activity teardown.
- Dedicated bottom-navigation sections for Home, Profiles, Tools, Engines, and Settings; profile/engine summaries refresh after state changes, and Android Back returns to Home from secondary sections.
- Local profile preflight in Tools: reports whether a profile is loaded, detects its format, checks engine mapping and backend registration, and explicitly states that no remote tunnel or leak test was performed.
- Xray Android TUN integration work on the feature branch, including a pinned Android library, TUN descriptor handoff, lifecycle handling, and fail-closed socket-protection wiring; this remains subject to the physical-device release gates below.
- Separate Xray and sing-box flavors remain available for isolated runtime validation. An experimental `multi` flavor attempts to package both Xray and sing-box runtimes alongside WireGuard in one APK; CI checks build success and native library presence. This is an integration experiment, not proof of simultaneous runtime compatibility or verified tunnel traffic.
- Optional SNI/CDN endpoint overrides for Xray and sing-box, with hostname/IP validation and TLS verification preserved. sing-box Host-header override is limited to WebSocket transport; unsupported profile layouts fail closed.
- Coordinator-level SOCKS5 chaining for Xray and sing-box: validates a loopback no-auth SOCKS5 listener, applies the engine-specific profile adapter, and fails closed on unsupported or malformed profiles. The upstream runtime itself must already be running and lifecycle-supervised; this does not start Psiphon or Aether.
- Aether's pinned Rust/WARP engine can be packaged with the in-process HEV TUN bridge by CI; it is registered only when the ABI-specific native engine, HEV library, and RØH JNI bridge are present. The first backend exposes built-in MASQUE/WARP only; optional Psiphon/Tor/custom modes and physical-device traffic/leak validation remain separate release gates. See [Aether runtime integration](docs/AETHER_RUNTIME_INTEGRATION.md).
- Encrypted saved-profile management with duplicate fingerprint checks, safe load validation, and explicit delete confirmation.
- Cloudflare Worker + D1 control-plane scaffold with JWT-gated profile requests, HTTPS provisioner handoff, 15-minute maximum profile lifetime, empty-by-default server directory, and Node tests.
- JVM unit tests and CI workflow that builds a debug APK and runs tests.
- Core integration, server-discovery, third-party licensing, engine capability, and Aether security review documents.

**Important:** This is not yet a verified production VPN. The control plane is a deployment scaffold only: no Cloudflare D1 ID, deployed Worker, real VPN node, provisioner, authentication issuer, or enabled server is configured. Xray integration exists on this feature branch but has not passed the required physical-device traffic, DNS, IPv4/IPv6, and leak tests. Xray and sing-box are being packaged into one shared gomobile AAR so the app has a single APK and one Go mobile runtime; CI and physical-device coexistence tests must still pass before release. Aether is not integrated as an operational backend. PG CORE's Android module is included, but its default packet-forwarding backend deliberately fails closed and is not registered as an operational engine. A successful build, backend registration, or RUNNING status alone is not proof that device traffic traverses the tunnel. The Tools preflight is local-only and does not test network connectivity.

## Project documents

- [RØH engine fusion architecture and conflict rules](docs/ROH_ENGINE_FUSION_ARCHITECTURE.md)
- [Core integration plan](docs/CORE_INTEGRATION_PLAN.md)
- [Server discovery API contract](docs/SERVER_DISCOVERY_API.md)
- [Third-party and licensing policy](docs/THIRD_PARTY_POLICY.md)
- [Aether integration and signing-key risk review](docs/AETHER_INTEGRATION_REVIEW.md)
- [Engine capability matrix](docs/ENGINE_CAPABILITY_MATRIX.md)
- [Multi-engine implementation and acceptance gates](docs/MULTI_ENGINE_IMPLEMENTATION.md)
- [Hiddify/Proton-inspired capability backlog](docs/FEATURE_PARITY_BACKLOG.md)

## Integration gates

1. Verify WireGuard and Xray profile import/connect/disconnect on a device or emulator using authorized test servers.
2. Configure an authorized server API and secure profile provisioning.
3. Verify TCP, UDP, DNS, IPv4/IPv6, socket protection, lifecycle, network changes, MTU, and leak resistance on real Android devices; record evidence per `docs/DEVICE_VALIDATION.md`.
4. Prove the unified Xray + sing-box AAR build on CI and verify simultaneous native startup and real traffic on devices before advertising it as operational; integrate Aether and PG CORE only after independent review and real data-plane tests.
5. Keep release signing keys private and include all required upstream notices.

Proton VPN's free service is not a generic public server pool. RØH VPN will not scrape, hard-code, or redistribute Proton endpoints or credentials; an integration requires an explicitly supported and authorized mechanism.

## Current status

The RØH fusion role resolver is now implemented as a tested policy boundary. It is not yet a live packet bridge: PG CORE remains unverified as an active helper until its real native TUN-to-engine data path is connected and tested. The build pipeline now attempts to bind Xray and sing-box into one shared AAR (one `go.Seq` runtime) and produce one APK. This packaging change is not considered verified until the combined binding, APK build, and native runtime checks pass.

**Development stage — not a release-ready VPN.** The app is configured as one APK containing Xray, sing-box, and WireGuard. Aether and PG CORE are not operational data planes, and no physical-device traffic/leak validation has been recorded. CI verifies compilation, tests, and packaged library presence only—not live tunnel routing or leak resistance. The control plane is not deployed and its server directory is empty by default. Keep the pull request in draft until device-validation gates are completed.

### Optional TLS record fragmentation

The Android app includes an opt-in `TLS record fragmentation (sing-box only)` setting. When enabled, it adds `tls.record_fragment: true` only to outbound entries whose `tls.enabled` is already `true`; it does not change the imported profile bytes held by the UI. It fails closed if no enabled TLS outbound is found. The option is off by default and may reduce performance or be incompatible with some profiles/transports. This config transform is not proof that a connection works or that filtering behavior changes; validate against an authorized test endpoint and disable it if connections become slower or fail.

### Optional CDN/SNI overrides

The profile screen exposes separate SNI, CDN edge IP, and transport Host fields. Xray supports its validated TLS/REALITY SNI and compatible CDN transports through its adapter. sing-box supports TLS SNI and CDN edge IP for a single TLS-enabled proxy outbound with supported transports; the explicit Host override is currently limited to WebSocket. Values must be supplied by the authorized profile/provider: a CDN IP alone does not make an origin compatible, and SNI/Host must match the remote server's configuration. TLS certificate verification is not disabled by these options. Unsupported or ambiguous profile layouts are rejected.

### PG CORE status

The `:pg-core-android` module is included in the app build, and the app includes the PG CORE flow-selection primitives. However, the PG module's default backend is `UnavailableVpnBackend`; no tested TUN-to-engine packet forwarder or cooperating multi-path server is bundled. The UI therefore reports PG CORE as included but disabled for traffic. It must not be marked operational until a real backend passes the TCP/UDP/DNS, IPv4/IPv6, socket-protection, lifecycle, and device leak-test gates.


## One-app packaging requirement

RØH VPN is built as one Android application, not separate Xray and sing-box apps. CI attempts to generate `app/libs/roohvpn-core.aar` from both Go packages in one `gomobile bind` invocation, sharing one Go Java runtime, then builds `app-debug.apk`. The CI ZIP bundle includes the project source tree (including checked-out engine submodules), the unified AAR, the APK, documentation, and SHA-256 metadata. A green build does not replace real-device TCP/UDP/DNS, IPv4/IPv6, socket-protection, or leak testing. PG CORE remains fail-closed for traffic until its real forwarding path is verified; Aether is not yet an operational backend.
