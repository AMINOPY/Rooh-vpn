# RØH VPN

RØH VPN is an Android VPN client project focused first on a reliable, testable networking core. The initial milestone is **core integration and connection correctness**; visual redesign is explicitly deferred.

## Project direction

- **Engine candidates:** Xray-core, Aether, sing-box, and WireGuard. These are distinct backends, not interchangeable libraries. Each needs its own adapter, lifecycle, capability declaration, native packaging, license review, and tests.
- **Android tunnel:** Android `VpnService` owns the TUN interface and OS-level routing. An engine process or local proxy alone does not route device traffic.
- **Simple connection:** one-tap connection requires a legitimate server operator or provider-authorized API. This repo intentionally has no fabricated server addresses or embedded credentials.
- **Security:** no hard-coded server credentials, private keys, API tokens, or signing secrets. Local saved profile data uses Android Keystore-backed AES-GCM encryption. Logs must redact sensitive values.
- **Languages:** Kotlin/Java for Android integration; Go/Rust/C/C++ only where an upstream engine or native tunnel requires them; Python for development and CI tooling rather than the privileged runtime without a clear need.

## Implemented in the current feature branch

- Android application module and core lifecycle foundation.
- Common engine contract, capability models, registry, and serialized connection coordinator.
- WireGuard adapter using the official Android tunnel library (pinned dependency), including bounded profile parsing and safe error mapping.
- Encrypted local profile storage backed by Android Keystore AES-GCM.
- Provider-backed server-directory interface, HTTPS client with bounded response parsing, TCP reachability probe, deterministic selector, and auto-connect orchestration boundary.
- JVM unit tests and CI workflow that builds a debug APK and runs tests.
- Core integration, server-discovery, third-party licensing, engine capability, and Aether security review documents.

**Important:** WireGuard adapter integration is not the same as a verified production VPN. The app still needs an authorized profile source, a user-consent connection flow, full lifecycle wiring, and device/emulator tests proving real traffic and DNS traverse the tunnel. Xray, Aether, and sing-box are not integrated backends in this build. A successful Android build is not proof that a VPN tunnel works.

## Project documents

- [Core integration plan](docs/CORE_INTEGRATION_PLAN.md)
- [Server discovery API contract](docs/SERVER_DISCOVERY_API.md)
- [Third-party and licensing policy](docs/THIRD_PARTY_POLICY.md)
- [Aether integration and signing-key risk review](docs/AETHER_INTEGRATION_REVIEW.md)
- [Engine capability matrix](docs/ENGINE_CAPABILITY_MATRIX.md)
- [Hiddify/Proton-inspired capability backlog](docs/FEATURE_PARITY_BACKLOG.md)

## Integration gates

1. Wire the WireGuard adapter into the user-consented app connection flow.
2. Configure an authorized server API and secure profile provisioning.
3. Verify DNS, IPv4/IPv6, lifecycle, network changes, and no orphaned processes on real Android devices.
4. Integrate remaining engines independently behind the same adapter contract.
5. Keep release signing keys private and include all required upstream notices.

Proton VPN's free service is not a generic public server pool. RØH VPN will not scrape, hard-code, or redistribute Proton endpoints or credentials; an integration requires an explicitly supported and authorized mechanism.

## Current status

**Development stage — not a release-ready VPN.** CI passed for the preceding foundation commit. The new WireGuard dependency and adapter must pass the latest CI run before this change is considered build-verified. There is no claim of a working end-to-end tunnel, integrated multi-engine support, or Proton server access.
