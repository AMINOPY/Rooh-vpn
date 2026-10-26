# RØH VPN — Core Integration Plan

## Goal and scope

Build a reliable Android VPN client named **RØH VPN**. The current scope is the networking/core foundation only. A visual redesign and animation work are deferred.

This is an implementation plan, not a claim that all components are production-ready.

## 1. Target architecture

```text
Android UI / explicit VPN consent
          |
AutoConnectCoordinator (profile discovery/provisioning)
          |
ConnectionCoordinator (serialized lifecycle)
          |
EngineBackend contract
  |         |          |          |
 Xray     Aether     sing-box   WireGuard
  |         |          |          |
 engine-specific adapter / native bridge
          |
Android VpnService + TUN + DNS/routes
          |
Protected device traffic
```

Not every engine exposes the same interface. Some can provide a TUN implementation; others may expose a local SOCKS/HTTP proxy and require a separate TUN-to-proxy bridge. Never assume a proxy automatically routes all device traffic.

## Current implementation snapshot (2026-10-26)

The feature branch contains the Kotlin engine contract and coordinator, encrypted local profile storage, a provider-backed server-directory interface, an HTTPS directory client with bounded response parsing, a TCP reachability probe, a deterministic endpoint selector, and an auto-connect orchestration boundary. A WireGuard adapter now uses the official `com.wireguard.android:tunnel` library at a pinned version and parses bounded wg-quick profiles.

**Still not production-complete:** the UI does not yet drive the permission/connection flow; no authorized server-directory URL or profile provider is configured; no real device/emulator traffic and DNS test has verified the complete packet path; Xray, Aether, and sing-box have no registered production adapters. Do not report a successful tunnel until these gates pass. Keep the feature branch unmerged until the owner approves.

## 2. Integration order

### Phase A — repository and security baseline
- Establish Android Gradle project structure and supported SDK/ABIs.
- Add CI checks for formatting, unit tests, dependency/license inventory, and native-artifact presence.
- Pin each upstream dependency to a reviewed release or immutable commit.
- Document required licenses, notices, modifications, and build provenance.
- Keep secrets out of source control and release artifacts.

### Phase B — common backend contract
Define a small engine abstraction with:
- capability discovery (supported protocols, TUN/proxy mode, per-app routing support, IPv6 support);
- configuration validation before launch;
- start, health/status, stop, and cleanup operations;
- structured errors and bounded/redacted diagnostics;
- cancellation, timeout, and process-death handling.

The coordinator must serialize start/stop transitions and guarantee cleanup after partial startup failures. Do not let engine-specific details leak into the Android service.

### Phase C — first engine and Android tunnel
- [x] Add the official WireGuard Android tunnel library and a backend adapter.
- [x] Parse WireGuard profiles with a size limit and safe failure messages.
- [x] Add explicit Android VPN consent and profile import/connect/disconnect controls.
- [ ] Verify consent, start/stop, revocation and process-death behavior on supported devices/emulators.
- [ ] Establish and verify TUN addresses, routes, DNS, MTU, and IPv4/IPv6 behavior from the profile.
- [ ] Verify recursive routing protection for engine sockets.
- [ ] Test connect, disconnect, process crash, network change, airplane mode, sleep/resume, and permission revocation.
- [ ] Test actual traffic and DNS; status text alone is not proof of a working tunnel.
- [ ] Do not advertise a kill switch unless implementation and Android system settings support the stated guarantee.

### Phase D — no-manual-config connection
A true one-tap experience requires a legitimate source of profiles/endpoints:
- an RØH-operated service API with authentication, abuse controls, privacy policy, and monitored infrastructure; or
- a documented provider-authorized API/integration; or
- user-imported profile/subscription as a fallback.

The client has a consent-driven manual WireGuard profile fallback. A Cloudflare Worker + D1 control-plane scaffold now defines directory/profile endpoints and delegates profile creation to a separately operated HTTPS provisioner. It is not deployed or configured: a real trusted service URL, account/device JWT issuer, VPN node, and provisioner still need to be operated before no-manual-config connection can work. The client must not fabricate server availability or silently collect credentials.

**Proton VPN note:** Proton's free service is a provider service, not a public pool of generic server credentials. Do not hard-code, scrape, or redistribute Proton endpoints/credentials. Only integrate through a provider-supported, authorized mechanism; otherwise keep Proton as a reference for user experience, not as an embedded free-server backend.

### Phase E — additional engines
Integrate engines independently, only after the first complete path works:
1. **Xray-core** — official `XTLS/libXray` uses gomobile and embeds a Go runtime. Its upstream warns not to load independently built Go runtimes in the same process. A production integration needs a reproducible pinned Android build, API wrapper, socket protection, DNS handling, a validated TUN-to-proxy bridge or supported FD path, and explicit process isolation if paired with another Go-based engine.
2. **sing-box** — the Android `libbox` binding is declared as a dependency, but the backend is not implemented. It also needs its supported service/TUN lifecycle, config validation, protected sockets, DNS/routes, native ABI and license review. Do not register it until traffic tests pass.
3. **Aether** — reference-only. Evaluate its engine and Android integration as a whole; its upstream app bundles multiple components with separate licenses. Simply adding its app code does not make it a clean engine adapter.
4. **WireGuard** — adapter added; it remains a distinct protocol and does not replace arbitrary VLESS/VMess protocols.

Each backend needs a pinned version, license review, ABI matrix, native packaging validation, integration tests, and a clear capability matrix.

## 3. Common backend contract

The implementation represents:
- `EngineId`: xray, aether, sing-box, wireguard;
- `EngineCapabilities`: connection mode (TUN/proxy), IPv4/IPv6, DNS support, routing features, config formats;
- `EngineBackend`: validation, start, status, and stop boundary;
- `EngineStatus`: stopped, starting, running, stopping, failed;
- `EngineFailure`: stable code, safe user message, retryability.

Do not create one universal configuration schema that pretends all engines accept the same fields.

## 4. Native and language boundaries

- **Kotlin:** Android service lifecycle, permission flow, coordinator, state, persistence, integration tests.
- **Java:** only where Android/upstream APIs require it; avoid duplicating Kotlin abstractions.
- **Go/Rust/C/C++:** only for upstream engine/native tunnel components with pinned, reproducible builds.
- **Python:** CI/release helper scripts, repository checks, and test fixtures; not a substitute for the Android VPN service.

Avoid rewriting mature cryptographic/networking cores. Reuse upstream code only under its license and security model.

## 5. Security and privacy requirements

- Never log full profiles, UUIDs, passwords, private keys, subscription URLs, or tokens.
- Encrypt sensitive local configuration using Android Keystore-backed key material where appropriate.
- Use HTTPS with normal certificate validation; never use trust-all TLS.
- Bound logs and redact before writing or exporting them.
- Keep permissions minimal; request camera only if QR scanning is implemented.
- Make split tunneling and bypass behavior explicit.
- Test IPv6 and DNS leak behavior instead of assuming IPv4 routes protect IPv6.
- Avoid broad LAN proxy sharing by default.
- No analytics or AI data sharing unless explicitly designed, documented, and opt-in.
- Keep user consent and provider terms intact; do not bypass access controls.

## 6. Required test gates

- Kotlin unit tests for coordinator state transitions.
- Adapter tests for validation, start/stop, cleanup, timeouts, and redaction.
- Instrumentation tests for VPN permission and service lifecycle.
- Integration test that requests a known HTTPS endpoint through the tunnel.
- DNS/IPv6 routing tests on supported emulator/device configurations.
- Network-change, process-death, and reconnect tests.
- Native packaging checks for every shipped ABI.
- License and third-party-notice checks.
- CI build must fail when a declared native library is missing.

## 7. Definition of done

A backend is not “integrated” until:
- it builds from pinned source or a verified artifact;
- its license and notices are included;
- config validation and lifecycle tests pass;
- Android VPN consent and TUN packet path are connected;
- real traffic and DNS tests pass on a supported device/emulator;
- disconnect leaves no process, descriptor, or route behind;
- failure is visible and does not silently fall back to unprotected traffic.

Only then should the next backend be added.
