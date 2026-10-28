# RØH VPN — physical-device validation and release gate

This checklist is deliberately separate from CI. A green build is not evidence that a VPN data plane works on a device.

## Test environment record

Record before testing:
- APK artifact URL and SHA-256
- Git commit SHA, Android version, device model
- Engine and profile protocol (redact credentials and endpoint secrets)
- Network type (Wi-Fi/mobile), carrier/router, timestamp
- Whether Android's VPN permission was granted and Always-on VPN / lockdown settings

Never attach raw profiles, private keys, subscription URLs, or unredacted logs.

## Required cases for each engine/profile format

- [ ] Fresh install; VPN consent granted; connect succeeds only after native engine and TUN are ready.
- [ ] Connect with malformed, empty, oversized, and unsupported profiles; reject safely without reporting Connected.
- [ ] HTTPS request to a controlled endpoint succeeds through the tunnel; record observed egress IP from the endpoint.
- [ ] DNS lookup succeeds; confirm the resolver path matches the configured policy and no unexpected resolver is observed.
- [ ] IPv4 connectivity succeeds when advertised by the profile.
- [ ] IPv6 connectivity succeeds when advertised; otherwise confirm IPv6 cannot bypass the tunnel.
- [ ] TCP transfer and UDP request/response work through a controlled test endpoint.
- [ ] Verify engine outbound sockets are protected from VPN routing recursion; test failure of protect() and require fail-closed behavior.
- [ ] Stop/disconnect; confirm traffic returns to the expected non-VPN route and no stale TUN remains.
- [ ] Repeated connect/disconnect (at least 20 cycles) without leaked descriptors, stuck notifications, or orphaned native processes.
- [ ] Revoke VPN permission while connected; tunnel state and UI recover safely.
- [ ] Toggle airplane mode; switch Wi-Fi/mobile; confirm recovery or explicit failure without falsely reporting Connected.
- [ ] Force-stop/restart the app and test process death; verify Android's actual VPN state matches the UI.
- [ ] Test MTU/path-MTU behavior against controlled HTTP and UDP endpoints.
- [ ] Run leak checks with IPv4, IPv6, and DNS while connected, including failure and reconnect paths.

## Engine-specific gates

### Xray
- [ ] Test every advertised protocol/transport combination against a controlled, authorized server.
- [ ] Confirm all TCP and UDP outbound paths use the Android socket-protection hook.
- [ ] Confirm StopLoop failure is surfaced and the TUN/protector are not prematurely released.

### sing-box
- [ ] Confirm the packaged libbox ABI matches the device ABI.
- [ ] Confirm Android TUN, routes, DNS, per-app rules (if advertised), and socket protection match the profile.
- [ ] Confirm service start/stop/revoke races leave no orphaned CommandServer or TUN.

### WireGuard
- [ ] Test official tunnel backend profile import, key handling, routes, DNS, permission revocation, and real peer handshake.
- [ ] Confirm traffic counters and controlled endpoint results change as expected.

### PG CORE
- [ ] Do not advertise as a user-facing VPN engine until a real packet-forwarding data plane is implemented and tested.
- [ ] Go tests and flow selection/capacity tests alone do not satisfy the VPN traffic gate.

### Aether
- [ ] Do not register as available until the reviewed upstream runtime, Android tunnel bridge, lifecycle, ABI packaging, and real traffic path are integrated.

## Release decision

A release candidate is eligible only when:
1. CI passes for the exact candidate commit.
2. Every supported engine passes its applicable device cases with recorded evidence.
3. No unexpected IPv4, IPv6, or DNS leak is observed.
4. Failed start/stop and permission/network changes never produce a false Connected state.
5. Release notes state only the protocols and capabilities actually tested.

Any unchecked required item means **device validation incomplete — do not call the build production-ready**.
