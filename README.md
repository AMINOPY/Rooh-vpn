# RØH VPN

RØH VPN is an Android VPN client project focused on a reliable, testable networking core and a clear, consent-driven user interface. The current feature branch includes a dark dashboard and dedicated Home, Profiles, Tools, Engines, and Settings sections.

## Project direction

- **Engine priority:** one shared app supports engine-specific adapters for Xray-core, sing-box, and WireGuard where packaged. Aether and PG CORE remain integration work, not selectable operational backends. Each engine needs its own adapter, lifecycle, capability declaration, native packaging, license review, and tests.
- **Android tunnel:** Android `VpnService` owns the TUN interface and OS-level routing. An engine process or local proxy alone does not route device traffic.
- **Simple connection:** one-tap connection requires a legitimate server operator or provider-authorized API. This repo intentionally has no fabricated server addresses or embedded credentials.
- **Security:** no hard-coded server credentials, private keys, API tokens, or signing secrets. Local saved profile data uses Android Keystore-backed AES-GCM encryption. Logs must redact sensitive values.
- **Languages:** Kotlin/Java for Android integration; Go/Rust/C/C++ only where an upstream engine or native tunnel requires them; Python for development and CI tooling rather than the privileged runtime without a clear need.

## Implemented in the current feature branch

- Android application module and core lifecycle foundation.
- Common engine contract, capability models, registry, and serialized connection coordinator.
- WireGuard adapter using the official Android tunnel library (pinned dependency), including bounded profile parsing and safe error mapping.
- Encrypted local profile storage backed by Android Keystore AES-GCM.
- Provider-backed server-directory interface, hardened HTTPS discovery/profile clients, bounded concurrent reachability probes, deterministic selection, and auto-connect orchestration boundary.
- Explicit Android VPN-consent flow and profile import/connect/disconnect controls; imports are bounded to 1 MiB, unsupported formats are rejected, and active profile bytes are cleared on disconnect/activity teardown.
- Dedicated bottom-navigation sections for Home, Profiles, Tools, Engines, and Settings; profile/engine summaries refresh after state changes, and Android Back returns to Home from secondary sections.
- Local profile preflight in Tools: reports whether a profile is loaded, detects its format, checks engine mapping and backend registration, and explicitly states that no remote tunnel or leak test was performed.
- Xray Android TUN integration work on the feature branch, including a pinned Android library, TUN descriptor handoff, lifecycle handling, and fail-closed socket-protection wiring; this remains subject to the physical-device release gates below.
- Separate Xray and sing-box flavors remain available for isolated runtime validation. An experimental `multi` flavor now attempts to package both Xray and sing-box runtimes alongside WireGuard in one APK; CI checks build success and native library presence. This is an integration experiment, not proof of simultaneous runtime compatibility or verified tunnel traffic.
- Encrypted saved-profile management with duplicate fingerprint checks, safe load validation, and explicit delete confirmation.
- Cloudflare Worker + D1 control-plane scaffold with JWT-gated profile requests, HTTPS provisioner handoff, 15-minute maximum profile lifetime, empty-by-default server directory, and Node tests.
- JVM unit tests and CI workflow that builds a debug APK and runs tests.
- Core integration, server-discovery, third-party licensing, engine capability, and Aether security review documents.

**Important:** This is not yet a verified production VPN. The control plane is a deployment scaffold only: no Cloudflare D1 ID, deployed Worker, real VPN node, provisioner, authentication issuer, or enabled server is configured. Xray integration exists on this feature branch but has not passed the required physical-device traffic, DNS, IPv4/IPv6, and leak tests. sing-box is compile-only due to the runtime collision noted above; Aether is not integrated as an operational backend. A successful build, backend registration, or RUNNING status alone is not proof that device traffic traverses the tunnel. The Tools preflight is local-only and does not test network connectivity.

## Project documents

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
4. Prove the experimental combined Xray + sing-box runtime on CI and real devices before advertising it as operational; integrate Aether and PG CORE only after independent review and real data-plane tests.
5. Keep release signing keys private and include all required upstream notices.

Proton VPN's free service is not a generic public server pool. RØH VPN will not scrape, hard-code, or redistribute Proton endpoints or credentials; an integration requires an explicitly supported and authorized mechanism.

## Current status

**Development stage — not a release-ready VPN.** The `multi` flavor is an experimental attempt at one APK containing Xray, sing-box, and WireGuard. Aether and PG CORE are not operational data planes, and no physical-device traffic/leak validation has been recorded. CI verifies compilation, tests, and packaged library presence only—not live tunnel routing or leak resistance. The control plane is not deployed and its server directory is empty by default. Keep the pull request in draft until device-validation gates are completed.

### Optional TLS record fragmentation

The Android app includes an opt-in `TLS record fragmentation (sing-box only)` setting. When enabled, it adds `tls.record_fragment: true` only to outbound entries whose `tls.enabled` is already `true`; it does not change the imported profile bytes held by the UI. It fails closed if no enabled TLS outbound is found. The option is off by default and may reduce performance or be incompatible with some profiles/transports. This config transform is not proof that a connection works or that filtering behavior changes; validate against an authorized test endpoint and disable it if connections become slower or fail.
