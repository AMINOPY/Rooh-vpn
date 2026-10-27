# Three-track completion gate

Status date: 2026-10-27  
Branch: `feat/rohvpn-engine-foundation`

This gate keeps the three remaining workstreams coordinated. They can be developed in parallel, but **the release gate is conjunctive**: a green result in one track cannot compensate for an incomplete result in another. A UI state, successful TUN creation, TCP probe, or native-library presence is not proof of a working VPN.

## Track A — real packet path and network lifecycle

### Required implementation
- Keep Android `VpnService` permission/revoke handling and engine lifecycle under one owner.
- Establish the TUN only as part of a backend that can consume and forward packets; close the TUN descriptor and stop native workers on every failure/stop/revoke path.
- Confirm route, DNS, MTU, IPv4, IPv6, and split/per-app-routing settings agree between Android's VPN builder and the selected engine.
- On network changes, validate that the active tunnel still works; recover or fail closed with a visible disconnected/error state. Do not report connected merely because the backend process exists.
- Protect engine-originated sockets from being routed back into the VPN. Do not claim a kill switch without leak tests.
- Handle service/process death, user revocation, airplane mode, Wi-Fi/mobile handoff, sleep/resume, and repeated connect/disconnect.

### Required tests before release
- Real HTTP(S) request through the tunnel and server-side confirmation of the observed egress.
- DNS success and leak checks; IPv4 and IPv6 reachability where available; MTU/fragmentation checks.
- Wi-Fi → mobile → Wi-Fi transitions, no-network → network recovery, revoke, force-stop/process death, and repeated lifecycle tests.
- Capture packet/byte counters from real interfaces; no synthetic traffic statistics.
- Emulator tests plus physical-device tests on the supported Android versions.

### Current gate
**NOT PASSED.** `NetworkLifecycleMonitor` observes default-network availability/validation, and `NetworkRecoveryController` now provides a worker-thread policy with callback coalescing, a real-traffic health-check port, bounded single-attempt recovery, and fail-closed status. **It is not wired to a concrete WireGuard health probe/reconnect owner yet.** `RoohVpnService` remains a lifecycle shell; WireGuard's library-managed service owns its own TUN. End-to-end device traffic/leak evidence is still missing. Do not mark the full RØH VPN traffic path complete.

## Track B — Aether, Xray, and sing-box engines

### Required implementation per engine
1. Select and pin an immutable upstream revision; review license and all transitive/native dependencies.
2. Build from source reproducibly for each supported Android ABI. Record artifact hashes and notices; never import another project's signing key or release identity.
3. Implement the real JNI/FFI or supported Android binding, engine-specific config validation, native start/status/stop, cancellation, partial-start cleanup, and descriptor ownership.
4. Integrate the engine with the same TUN packet path, DNS/routing configuration, and service lifecycle used by the app.
5. Add native packaging checks and adapter tests, then real traffic tests on emulator/device.
6. Register and display an engine only after all gates pass.

### Engine-specific blockers
- **Aether:** reviewed upstream is an Android application with a Rust engine, a hev SOCKS-to-TUN component, and an app-specific JNI/service lifecycle. RØH does not yet bundle and integrate that full native boundary. Do not fake this with a registry entry.
- **Xray:** no RØH Xray backend is integrated. Resolve native runtime/build strategy first. Do not load incompatible independent Go runtimes in one process; select a supported single-runtime build or process isolation and test it.
- **sing-box:** the Android binding dependency is present, but RØH's backend, service/TUN ownership, config validation, and end-to-end traffic path are not complete.

### Current gate
**NOT PASSED for all three.** Engine IDs/config classification are not evidence of operational support. No native Aether/Xray/sing-box adapter was added in this change; adding a placeholder would falsely advertise support. Only fully integrated and tested backends may be registered.

## Track C — operated RØH servers and secure profile provisioning

### Required infrastructure
- Deploy and operate actual VPN nodes with firewall/routing, DNS, IPv4/IPv6 policy, monitoring, abuse handling, capacity and incident response.
- Deploy the HTTPS directory and an authenticated provisioner. Provision short-lived, per-user/device credentials; support rotation, revocation, quotas and server-side authorization.
- Keep signing keys, JWT/provisioner secrets, and private server keys outside Git and the APK. Use managed secrets and least privilege.
- Populate the directory only with servers controlled by RØH or explicitly authorized for this integration. Never fabricate endpoints or borrow another provider's credentials.
- Publish operator/privacy/retention disclosures and an operational contact before public rollout.

### Required tests before release
- Live authenticated profile issuance for a real test account/device; expired, revoked, unauthorized and wrong-server profiles must be rejected.
- Provisioner timeout/error handling, directory outage, rate limits, secret rotation and no-secret-in-logs checks.
- A real WireGuard handshake plus tunneled DNS and HTTP(S) request against the deployed node, then network-recovery tests from Track A.

### Public community directory research
A candidate directory mirror was reviewed in [PUBLIC_SERVER_DIRECTORY_RESEARCH.md](PUBLIC_SERVER_DIRECTORY_RESEARCH.md). The candidate lists VPN Gate/OpenVPN servers, which the current WireGuard-only client cannot use. WireGuard management projects found during the search are self-hosted control planes, not public free-server pools. No compatible, verified public WireGuard server source was established, so no third-party endpoints are silently enabled or labeled as RØH servers.

### Current gate
**NOT PASSED.** The control-plane directory is a scaffold with disabled/empty server records. The user explicitly does not want Proton VPN servers; Proton endpoints, credentials, profiles, APIs, and infrastructure must not be used or added. No external provider is enabled by default. A future provider may be integrated only if the user separately chooses it and supplies documented authorization/API access. No provider endpoints are scraped, hard-coded, or represented as RØH-operated. A real RØH node, deployed provisioner, deployment environment, and runtime credentials were not supplied to this repository session. Code changes alone cannot create or authorize that infrastructure.

## Shared release decision

Release is blocked until all three tracks pass together:

- [ ] Track A's traffic, DNS, IP-family, lifecycle and leak tests are recorded.
- [ ] Aether, Xray and sing-box each have a reproducible native build, real adapter, packaging checks and end-to-end test evidence (or are explicitly hidden/marked unavailable).
- [ ] At least one authorized RØH-operated server has secure provisioning and passes a real handshake + traffic test.
- [ ] CI is green on the exact candidate commit; device test results and dependency/license inventory are attached to the release review.

Until then, the accurate product state is a development foundation with a WireGuard adapter and control-plane scaffold—not a fully operational multi-engine VPN service.
