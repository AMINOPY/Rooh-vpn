# Third-party code and licensing policy

RØH VPN may study other projects' architecture, but code reuse and binary redistribution require a license and provenance review.

## Before importing any upstream project

Record:
- canonical upstream URL;
- exact tag or immutable commit SHA;
- SPDX license identifier and full license text;
- source files and patches changed by RØH VPN;
- transitive dependencies and their licenses;
- native build commands, toolchain versions, and target ABIs;
- artifact hashes and reproducibility notes;
- required attribution and notices.

## Current dependency

- **WireGuard Android tunnel library:** Maven artifact `com.wireguard.android:tunnel:1.0.20260102`, declared license Apache-2.0, upstream https://git.zx2c4.com/wireguard-android (GitHub mirror https://github.com/WireGuard/wireguard-android). The artifact includes native userspace components and contributes the `GoBackend.VpnService` declaration through its Android library manifest. Preserve the upstream license and notices in distributed builds; verify the resolved dependency graph and packaged ABIs before release.

## Engine wrapper dependencies under evaluation

- **singbox-android/libbox 1.14.0:** Android binding for sing-box, declared GPL-3.0 in its upstream repository (https://github.com/singbox-android/libbox). The dependency is declared on the feature branch, but no RØH sing-box backend is registered yet. Upstream distributes its AAR as a large release asset. Before release, review GPL obligations, the exact transitive dependency graph, native ABIs, and notices. Dependency resolution alone is not functional engine support.
- **XTLS/libXray:** official Xray-core wrapper (https://github.com/XTLS/libXray), MIT-licensed wrapper repository. It documents an Android build from source and warns that its API is not guaranteed stable. Xray is not yet included in the app build: a pinned source build, generated Android artifact, adapter, lifecycle/TUN integration, and tests are still required. Its upstream explicitly warns against loading libXray and another independently built Go runtime in the same process. Review the exact Xray-core license and all transitive dependencies separately.

## Important upstream distinctions

- **Aether (QW-AI-Code/Aether):** its public README identifies the app as AGPL-3.0-or-later and credits Aether engine, hev-socks5-tunnel, Arti, Psiphon, and Xray-core. Review the exact checked-out revision and each bundled dependency separately. Importing app code may impose source-disclosure and corresponding-license obligations.
- **Xray-core:** review the exact upstream license and any bundled dependencies at the pinned revision before redistribution.
- **sing-box:** review its current license, dependencies, and mobile integration requirements at the pinned revision.
- **WireGuard:** review the exact Android app, userspace implementation, kernel/userspace components, and licenses separately.
- **Proton VPN:** treat provider infrastructure, server lists, credentials, app code, and brand as separate from generic protocol support. Do not embed provider credentials or imply official affiliation.

This document is a checklist, not legal advice. Do not ship an engine until a version-specific review has been recorded.
