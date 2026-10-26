# RØH VPN

RØH VPN is an Android VPN client project focused first on a reliable, testable networking core. The initial milestone is **core integration and connection correctness**; visual redesign is explicitly out of scope until the networking foundation is sound.

## Project direction

- **Primary reference/initial engine:** Xray-core adapter already described in project history; verify and implement only what exists in this repository.
- **Additional engine candidates:** Aether, sing-box, and WireGuard. They are separate backends, not interchangeable libraries. Each must have its own adapter, lifecycle, capability declaration, native packaging, and tests.
- **Android tunnel:** Android `VpnService` owns the TUN interface and OS-level routing. An engine process alone does not route device traffic.
- **Simple connection:** a backend can provide a one-tap experience only when there is a legitimate, reachable endpoint or service API. RØH VPN must not claim free Proton VPN access or bundle Proton servers without an authorized integration.
- **Security:** no hard-coded server credentials, private keys, API tokens, or signing secrets. Protect local profiles, redact logs, validate imported configurations, and fail closed where leak protection is claimed.
- **Languages:** Kotlin/Java for Android integration, Go for compatible engines/adapters where appropriate, Rust/C/C++ only where required by upstream native components, and Python for development/CI tooling—not as a privileged runtime dependency without a clear need.

## Integration status

This repository started with a minimal README. A feature branch is being used to establish the architecture and license/supply-chain plan before importing large upstream engines. Documentation is not evidence that an engine has already been integrated or that a working tunnel has been tested.

See:
- [Core integration plan](docs/CORE_INTEGRATION_PLAN.md)
- [Third-party and licensing policy](docs/THIRD_PARTY_POLICY.md)

## Engineering rules

1. Keep each engine behind a common backend contract.
2. Implement and verify one end-to-end tunnel before adding more engines.
3. Do not merge vendored code or native binaries without pinned upstream revisions, license notices, checksums, and reproducible build steps.
4. Report test results accurately; never mark an engine as connected until the service and traffic path are verified.
5. Do not copy proprietary app features, branding, credentials, or server infrastructure. Learn from public architecture and use compatible open-source code according to its license.

## Status

**Planning/foundation stage — not a release-ready VPN.** No working APK, multi-engine support, Proton integration, or verified end-to-end traffic path is claimed by this document.
