# Xray-core integration status

The repository pins the AndroidLibXrayLite source in the `xray-core` submodule and CI builds an Android AAR. The AAR is intentionally not added to the app dependency graph yet: the current Xray and sing-box Go-mobile bindings expose duplicate `go.Seq` classes, so bundling both as-is fails Android class checks.

The Android app now has `XrayProfilePreflight`, a bounded structural check for imported Xray JSON. It rejects oversized or invalid UTF-8 input, malformed JSON, absent/empty outbounds, outbounds without a protocol, invalid settings types, non-object inbound entries, and malformed routing-rule arrays. Tests also check that errors do not echo profile secrets. This is not native Xray validation and does not start a core or establish a VPN.

## Remaining before Xray can be registered as available

- Resolve the Go-mobile runtime/class collision with sing-box without suppressing duplicate-class checks or shipping an unverified merged runtime.
- Integrate the actual `libv2ray.CoreController` lifecycle and callbacks through a narrow Android adapter.
- Connect Xray to the Android `VpnService` TUN descriptor using the pinned binding's documented contract; confirm descriptor ownership and closure.
- Protect engine-owned sockets and handle VPN consent, revocation, network changes, startup failures and deterministic shutdown.
- Add native Xray configuration validation after the binding is safely packaged.
- Test TCP, UDP, DNS, IPv4/IPv6, network handoff, socket protection and leak prevention on emulator/device.

Do not register `EngineId.XRAY` as available until packaging, lifecycle, real traffic forwarding and tests pass. A structural preflight or successful AAR build is not evidence that a working VPN tunnel exists.
