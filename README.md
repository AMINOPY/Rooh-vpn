# RØH VPN

RØH VPN is an Android VPN client project focused first on a reliable, testable networking core. The initial milestone is **core integration and connection correctness**; visual redesign is explicitly deferred.

## Project direction

- **Engine candidates:** Xray-core, Aether, sing-box, and WireGuard. These are distinct backends, not interchangeable libraries. Each needs its own adapter, lifecycle, capability declaration, native packaging, license review, and tests.
- **Android tunnel:** Android `VpnService` owns the TUN interface and OS-level routing. An engine process or local proxy alone does not route device traffic.
- **Simple connection:** one-tap connection requires a legitimate server operator or provider-authorized API. This repo intentionally has no fabricated server addresses or embedded credentials.
- **Security:** no hard-coded server credentials, private keys, API tokens, or signing secrets. Local saved profile data uses Android Keystore-backed AES-GCM encryption. Logs must redact sensitive values.
- **Languages:** Kotlin/Java for Android integration; Go/Rust/C/C++ only where an upstream engine or native tunnel requires them; Python for development and CI tooling rather than the privileged runtime without a clear need.

## Implemented in the current feature branch

- Android application module and VPN service lifecycle shell.
- Common engine contract, capability models, registry, and serialized connection coordinator.
- Unit tests for coordinator lifecycle and deterministic server selection.
- Android Keystore-backed encrypted local profile storage.
- Provider-backed server-directory interface and bounded TCP reachability probe.
- CI workflow that builds a debug APK and runs JVM unit tests.
- Core integration, server-discovery, third-party licensing, and Aether security review documents.

**Important:** these are foundation components. No engine is registered as operational yet; the service deliberately does not establish a TUN without a real packet-forwarding backend. A successful Android build is not proof that a VPN tunnel works.

## Project documents

- [Core integration plan](docs/CORE_INTEGRATION_PLAN.md)
- [Server discovery API contract](docs/SERVER_DISCOVERY_API.md)
- [Third-party and licensing policy](docs/THIRD_PARTY_POLICY.md)
- [Aether integration and signing-key risk review](docs/AETHER_INTEGRATION_REVIEW.md)

## Integration gates

1. Integrate and test one complete engine-to-TUN traffic path.
2. Verify DNS, IPv4/IPv6, lifecycle, network changes, and no orphaned processes.
3. Add remaining engines independently behind the same adapter contract.
4. Implement an authorized server API and automatic endpoint selection.
5. Keep release signing keys private and include all required upstream notices.

Proton VPN's free service is not a generic public server pool. RØH VPN will not scrape, hard-code, or redistribute Proton endpoints or credentials; an integration requires an explicitly supported and authorized mechanism.

## Current status

**Foundation stage — not a release-ready VPN.** The debug build and unit-test workflow passed on the initial foundation commit. Later changes are being validated by CI. There is no claim of a working end-to-end tunnel, integrated multi-engine support, or Proton server access yet.
