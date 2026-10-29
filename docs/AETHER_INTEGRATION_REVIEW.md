# Aether integration review

Review date: 2026-10-26  
Upstream: https://github.com/QW-AI-Code/Aether  
Reviewed branch: `main` (reviewed as a reference only; not copied into RØH VPN)

## What is useful

Aether Mobile documents a complete packet path of Android `VpnService/TUN → hev-socks5-tunnel → local SOCKS proxy → Aether engine`. Its README also describes engine modes, Xray custom configs, endpoint testing through the proxy, routing, DNS/IPv6 protections, and encrypted local settings. These are useful architectural references; every claimed feature still needs independent tests in RØH VPN.

## High-risk supply-chain finding

The reviewed upstream `app/build.gradle.kts` contains comments and build logic describing a CI release-signing keystore committed in `.github/ci-keystore.jks.b64`, with the password historically present in the build file. A public release-signing key can allow someone with access to that key material to sign an APK accepted as an update for packages signed with the same certificate. This is a serious reason **not to copy Aether's app build configuration, signing material, release APKs, or keystore** into RØH VPN.

RØH VPN must generate and protect its own release signing key, keep it out of source control, use CI secrets, and never reuse another project's signing identity. This review is a repository-source observation, not proof that a particular published build was exploited.

## License and source review

The upstream LICENSE identifies Aether Mobile as AGPL-3.0-or-later. Its third-party notices separately identify components under other licenses, including Xray-core (MPL-2.0), hev-socks5-tunnel (Apache-2.0/MIT), and other bundled components. Review each exact revision and dependency separately. A whole-app fork or reuse of app code can create AGPL source-distribution and notice obligations. This document is not legal advice.

## Integration decision

Do not wholesale-copy the Aether application. Instead:
1. Document the desired packet-path and capability requirements.
2. Evaluate whether the Aether engine exposes a maintainable, documented library/FFI boundary independent of the full app.
3. Pin an immutable upstream commit and build from source.
4. Inventory all native components, licenses, patches, and ABIs.
5. Build a small adapter behind `EngineBackend`.
6. Test VPN permission, TUN packet forwarding, DNS, IPv6, network changes, crash recovery, and real HTTPS traffic on an emulator/device.
7. Do not report Aether as available until all gates pass.

No Aether binaries, keys, server lists, branding, or app code are included in this repository by this review.

## Concrete build boundary confirmed

The reviewed upstream tree at commit `b3f7a501cb2e2106b5fc43a6ee15310042c520e6` is an Android application, not a published standalone Android library/AAR. Its native build script describes separate outputs: `libaether.so` (Rust engine), `libhev-socks5-tunnel.so` (packet-forwarding core), and `libaethertun.so` (the project's own JNI bridge). Its `AetherVpnService` supervises the engine's local SOCKS5 listener and the TUN bridge together. Therefore, adding a Maven coordinate or just registering `EngineId.AETHER` would not create a working tunnel.

## Current RØH implementation state

- `EngineId.AETHER` is recognized by server-candidate parsing, but the production registry must not advertise it yet.
- RØH currently has no bundled Aether/hev native libraries, no Aether JNI bridge, and no Aether-specific service lifecycle adapter.
- The next implementation slice must pin the upstream revision, fetch/build native source reproducibly for supported ABIs, port only the minimum licensed runtime boundary, and wire TUN descriptor ownership, local SOCKS readiness, stop/cleanup, and VPN permission lifecycle into RØH's service.
- CI must build/package the native libraries and test the adapter contract. A physical-device or emulator test must confirm real HTTPS traffic and DNS/IPv4/IPv6 behavior before the registry/UI exposes Aether as available.

Until those conditions pass, Aether support remains **reviewed but not integrated**. No success status or fake adapter is used to imply otherwise.
