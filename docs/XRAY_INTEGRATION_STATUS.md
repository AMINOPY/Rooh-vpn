# Xray-core integration status

The repository pins AndroidLibXrayLite in the `xray-core` submodule. Its `CoreController.StartLoop(configContent, tunFd)` accepts an Android TUN file descriptor, and the pinned Xray core includes an Android TUN implementation that duplicates (rather than owns) the supplied descriptor. CI builds the Xray AAR before Gradle and the app now declares that AAR as the primary engine runtime. The sing-box libbox binding is compile-only because the two gomobile runtimes expose duplicate `go.Seq` classes; the CI runtime check rejects packaging libbox alongside Xray. This removes the immediate packaging conflict but does not by itself make Xray operational.

The Android app now has `XrayProfilePreflight`, a bounded structural check for imported Xray JSON. It rejects oversized or invalid UTF-8 input, malformed JSON, absent/empty outbounds, outbounds without a protocol, invalid settings types, non-object inbound entries, and malformed routing-rule arrays. Tests also check that errors do not echo profile secrets. This is not native Xray validation and does not start a core or establish a VPN.

## Remaining before Xray can be registered as available

- Implement an Android `VpnService` owner that establishes addresses/routes/DNS/MTU, hands the TUN descriptor to `CoreController.StartLoop`, and deterministically closes both the Xray controller and the app-owned descriptor.
- Add a supported socket-protection path for Xray outbound sockets. The current pinned binding does not expose a `VpnService.protect(fd)` callback; do not enable the TUN path until Xray sockets are guaranteed not to loop back into the VPN.
- Integrate the actual `libv2ray.CoreController` lifecycle and callbacks through a narrow Android adapter, including startup/stop confirmation and rollback.
- Handle VPN consent, revocation, network changes, startup failures and deterministic shutdown.
- Verify the exact Xray TUN inbound configuration schema against the pinned core and reject unsupported profiles before starting.
- Add native Xray configuration validation after the binding is safely packaged.
- Test TCP, UDP, DNS, IPv4/IPv6, network handoff, socket protection and leak prevention on emulator/device.

Do not register `EngineId.XRAY` as available until packaging, lifecycle, real traffic forwarding and tests pass. A structural preflight or successful AAR build is not evidence that a working VPN tunnel exists.
