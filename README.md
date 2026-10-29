# RØH VPN

RØH VPN is an Android VPN client project focused on a reliable, testable networking core and a clear, consent-driven user interface. The current feature branch includes a dark dashboard and dedicated Home, Profiles, Tools, Engines, and Settings sections.

## Project direction

- **Engine priority:** Xray-core first, then sing-box, Aether, and WireGuard. These are distinct backends, not interchangeable libraries. Each needs its own adapter, lifecycle, capability declaration, native packaging, license review, and tests.
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
- Encrypted saved-profile management with duplicate fingerprint checks, safe load validation, and explicit delete confirmation.
- Cloudflare Worker + D1 control-plane scaffold with JWT-gated profile requests, HTTPS provisioner handoff, 15-minute maximum profile lifetime, empty-by-default server directory, and Node tests.
- JVM unit tests and CI workflow that builds a debug APK and runs tests.
- Core integration, server-discovery, third-party licensing, engine capability, and Aether security review documents.

**Important:** The app now has a consent-driven manual WireGuard profile flow, but it is not yet a verified production VPN. The control plane is a deployment scaffold only: no Cloudflare D1 ID, deployed Worker, real VPN node, provisioner, authentication issuer, or enabled server is configured. End-to-end traffic/DNS and device lifecycle tests remain required. Xray, Aether, and sing-box are not operational backends in this build. A successful build or a RUNNING engine status alone is not proof that device traffic traverses the tunnel.

## Project documents

- [Core integration plan](docs/CORE_INTEGRATION_PLAN.md)
- [Server discovery API contract](docs/SERVER_DISCOVERY_API.md)
- [Third-party and licensing policy](docs/THIRD_PARTY_POLICY.md)
- [Aether integration and signing-key risk review](docs/AETHER_INTEGRATION_REVIEW.md)
- [Engine capability matrix](docs/ENGINE_CAPABILITY_MATRIX.md)
- [Multi-engine implementation and acceptance gates](docs/MULTI_ENGINE_IMPLEMENTATION.md)
- [Hiddify/Proton-inspired capability backlog](docs/FEATURE_PARITY_BACKLOG.md)

## Integration gates

1. Verify the new user-consented WireGuard profile import/connect/disconnect flow on a device/emulator.
2. Configure an authorized server API and secure profile provisioning.
3. Verify DNS, IPv4/IPv6, lifecycle, network changes, and no orphaned processes on real Android devices.
4. Integrate remaining engines independently behind the same adapter contract.
5. Keep release signing keys private and include all required upstream notices.

Proton VPN's free service is not a generic public server pool. RØH VPN will not scrape, hard-code, or redistribute Proton endpoints or credentials; an integration requires an explicitly supported and authorized mechanism.

## Current status

**Development stage — not a release-ready VPN.** At commit `80801c693cc52ccd863869795220db9922ad745c`, GitHub Actions passed the Android debug build/unit tests and control-plane tests ([run](https://github.com/AMINOPY/Rooh-vpn/actions/runs/40446414284)). This verifies build and automated tests only—not physical-device packet flow, DNS correctness, leak resistance, or production service availability. WireGuard is the only registered engine adapter; Xray, sing-box, and Aether are not operational backends. The control plane is not deployed and its server directory is empty by default. Do not advertise this branch as a fully working or release-ready VPN until the remaining integration and device-test gates are completed.
