# RØH VPN engine capability matrix

This is a design and verification matrix, not a claim that the listed engines are integrated. Actual support must be checked against the exact pinned upstream version and Android build target.

| Capability | Xray-core | Aether | sing-box | WireGuard | PG CORE |
|---|---|---|---|---|---|
| General role | Proxy/protocol engine | Native engine + app-defined modes | Multi-protocol proxy/VPN engine | WireGuard tunnel protocol | Project-specific engine under development |
| VLESS / VMess / Trojan / Shadowsocks | Depending on upstream protocol support | Via its bundled Xray path, if licensed and integrated | Depends on exact sing-box version/config | No | Depends on implemented adapters |
| WireGuard peer tunnel | Not a native replacement for WireGuard backend | Aether modes may use WireGuard internally; not equivalent to arbitrary peer profiles | Supported by relevant sing-box versions/config | Yes | Must be verified for the pinned PG implementation |
| Android full-device VPN | Needs a TUN path or maintained bridge | Upstream app provides a TUN-to-proxy path | Android integration/TUN path required | Android backend provides a native tunnel path | Native TUN-to-transport bridge still required |
| DNS routing | Engine and packet path dependent | Upstream mode-dependent | Engine and configuration dependent | Profile-driven DNS configuration; end-to-end behavior still needs device verification | End-to-end path not verified |
| Per-app routing | Android VPN builder / bridge and engine rules must align | Upstream app implements routing features | Depends on Android integration and config | **Disabled in RØH capability metadata** until RØH exposes and tests allow/deny app rules | Not enabled |
| Server auto-discovery | Requires a directory/service | Upstream app modes may use upstream services; RØH needs its own authorized integration | Requires a directory/service | Requires peer provisioning/service | Requires an authorized provisioning service |
| RØH status | Source pinned; Go tests and AAR generation pass in CI. AAR is temporarily not bundled because it duplicates `go.Seq` classes with sing-box. No EngineBackend/TUN path wired. | Reference review only; native components are not integrated into RØH | Dependency builds, but backend/TUN path is not integrated | Adapter and consent-driven profile flow added; end-to-end traffic not verified | Android library pinned and Go/Kotlin tests added to CI; native packet-forwarding backend remains fail-closed and is not wired into RØH runtime |

## PG CORE inclusion status

PG CORE is pinned as the `pg-core` Git submodule and its Go engine plus Android library module are built/tested by CI. This is source/build integration, not proof of a working VPN tunnel: its Android service intentionally fails closed unless a real packet-forwarding backend is installed. RØH does not register PG CORE as a connectable engine until the JNI/native bridge, TUN-to-engine data path, socket protection, DNS, IPv4/IPv6, and device traffic tests pass.

## Xray / sing-box packaging blocker

The generated Xray AAR and the sing-box AAR both package classes such as `go.Seq`, causing Android's duplicate-class check to fail when both are included in the same app. The Xray source tests and AAR generation succeed, but the Xray AAR is not added to the app runtime classpath until the shared Go runtime is handled safely. The build must not bypass duplicate-class validation or claim that Xray is usable in-app before this is resolved.

## Adapter contract requirements

Every backend must expose:
- unique engine ID and capability metadata;
- engine-specific config validation;
- start, status, stop, and cleanup;
- bounded timeouts and cancellation;
- redacted, stable error codes;
- native process/descriptor cleanup on partial startup;
- exact upstream revision, license notices, checksums, and ABI list.

Do not map a feature to “supported” merely because a core binary supports a protocol. Android routing, DNS, IPv6, profile provisioning, and the UI state must work end to end.
