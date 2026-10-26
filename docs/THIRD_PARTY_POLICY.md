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

## Important upstream distinctions

- **Aether (QW-AI-Code/Aether):** its public README identifies the app as AGPL-3.0-or-later and credits Aether engine, hev-socks5-tunnel, Arti, Psiphon, and Xray-core. Review the exact checked-out revision and each bundled dependency separately. Importing app code may impose source-disclosure and corresponding-license obligations.
- **Xray-core:** review the exact upstream license and any bundled dependencies at the pinned revision before redistribution.
- **sing-box:** review its current license, dependencies, and mobile integration requirements at the pinned revision.
- **WireGuard:** review the exact Android app, userspace implementation, kernel/userspace components, and licenses separately.
- **Proton VPN:** treat provider infrastructure, server lists, credentials, app code, and brand as separate from generic protocol support. Do not embed provider credentials or imply official affiliation.

This document is a checklist, not legal advice. Do not ship an engine until a version-specific review has been recorded.
