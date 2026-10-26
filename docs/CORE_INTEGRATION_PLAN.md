# RØH VPN — Core Integration Plan

## Goal and scope

Build a reliable Android VPN client named **RØH VPN**. The current scope is the networking/core foundation only. A visual redesign and animation work are deferred.

This is an implementation plan, not a claim that these components are already present or working.

## 1. Target architecture

```text
Android UI / app controls (later)
          |
ConnectionCoordinator (Kotlin)
          |
EngineBackend contract
  |         |          |          |
 Xray     Aether     sing-box   WireGuard
  |         |          |          |
 engine-specific adapter / native bridge
          |
Local proxy or native tunnel interface (engine-dependent)
          |
Android VpnService + TUN + DNS/routes
          |
Protected device traffic
```

Not every engine exposes the same interface. Some can provide a TUN implementation; others may expose a local SOCKS/HTTP proxy and require a separate TUN-to-proxy bridge. Never assume a proxy automatically routes all device traffic.

## 2. Integration order

### Phase A — repository and security baseline
- Establish Android Gradle project structure and supported SDK/ABIs.
- Add CI checks for formatting, unit tests, dependency/license inventory, and native-artifact presence.
- Pin each upstream dependency to a reviewed release or immutable commit.
- Document the required licenses, notices, modifications, and build provenance.
- Keep secrets out of source control and release artifacts.

### Phase B — common backend contract
Define a small engine abstraction with:
- capability discovery (supported protocols, TUN/proxy mode, per-app routing support, IPv6 support);
- configuration validation before launch;
- start, health/status, stop, and cleanup operations;
- structured errors and bounded/redacted diagnostics;
- cancellation, timeout, and process-death handling.

The coordinator must serialize start/stop transitions and guarantee cleanup after partial startup failures. Do not let engine-specific details leak into the Android service.

### Phase C — end-to-end Android tunnel
- Implement Android `VpnService` consent and foreground-service lifecycle.
- Establish TUN addresses, routes, DNS, MTU, and IPv4/IPv6 behavior from explicit capabilities.
- Choose exactly one tested packet path for the first engine: native TUN or local-proxy + maintained TUN bridge.
- Prevent recursive routing of the engine's own sockets where necessary.
- Verify connect, disconnect, process crash, network change, airplane mode, sleep/resume, and permission revocation.
- Test actual traffic and DNS; status text alone is not proof of a working tunnel.
- Do not advertise a kill switch unless the implementation and Android system settings support the stated guarantee.

### Phase D — first engine
Prioritize the already planned Xray adapter if it exists in the actual source tree. Validate its current implementation before reusing it. If it does not exist in this repository, port or implement it as a separate reviewed change.

Required tests:
- valid/invalid configuration cases;
- safe temporary-file permissions and cleanup;
- process start/exit and timeouts;
- no credentials in logs or crash messages;
- local health check and real HTTP request through the tunnel;
- stop/restart and no orphaned processes.

### Phase E — additional engines
Integrate engines independently, only after the first complete path works:
1. **Aether** — evaluate its engine and its existing Android integration as a whole. Its upstream repository describes a TUN-to-local-SOCKS bridge, Xray custom-config support, WARP-based modes, Tor/Psiphon chaining, routing, and other optional features. These depend on upstream components and licenses; do not assume that simply adding its engine library reproduces the app.
2. **sing-box** — use its supported Android/native integration path and capability-check TUN, DNS, routing, and supported protocol behavior. Do not run it concurrently with another engine over the same TUN unless the design explicitly supports chaining.
3. **WireGuard** — treat as a separate WireGuard backend requiring valid peer configuration and keys. It is not a general replacement for Xray/sing-box and does not provide arbitrary VLESS/VMess protocols.

Each backend needs a pinned version, license review, ABI matrix, native packaging validation, integration tests, and a clear capability matrix.

### Phase F — no-manual-config connection
A true one-tap experience requires a legitimate source of profiles/endpoints:
- an RØH-operated service API with authentication, abuse controls, privacy policy, and monitored infrastructure; or
- a documented provider-authorized API/integration; or
- user-imported profile/subscription as a fallback.

The client can discover endpoints, check reachability, rank eligible endpoints by measured latency/failure rate, and retry with bounded backoff. It must not fabricate server availability or silently collect credentials.

**Proton VPN note:** Proton's free service is a provider service, not a public pool of generic server credentials. Do not hard-code, scrape, or redistribute Proton endpoints/credentials. Only integrate through a provider-supported, authorized mechanism; otherwise keep Proton as a reference for user experience, not as an embedded free-server backend.

## 3. Common backend contract (conceptual)

The implementation should represent:
- `EngineId`: xray, aether, sing-box, wireguard;
- `EngineCapabilities`: connection mode (TUN/proxy), IPv4/IPv6, DNS support, routing features, config formats;
- `EngineConfig`: typed, validated, secret-bearing data kept out of logs;
- `EngineStatus`: stopped, starting, running, stopping, failed;
- `EngineError`: stable code, safe user message, redacted diagnostic details.

Use a sealed Kotlin interface or equivalent. Do not create one universal configuration schema that pretends all engines accept the same fields.

## 4. Native and language boundaries

- **Kotlin:** Android service lifecycle, permission flow, coordinator, state, persistence, integration tests.
- **Java:** only where Android/upstream APIs require it; avoid duplicating Kotlin abstractions.
- **Go:** Go-based cores or carefully scoped Go adapters when supported by upstream build tooling.
- **C/C++:** only for upstream native tunnel components or low-level bridging that requires it.
- **Rust:** required if an upstream engine (such as Aether's native engine) uses Rust; keep its build pinned and reproducible.
- **Python:** CI/release helper scripts, repository checks, and test fixtures; not a substitute for the Android VPN service.

Avoid rewriting mature cryptographic/networking cores in another language. Reuse upstream code only under its license and security model.

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
- Android VPN consent and TUN/proxy packet path are connected;
- real traffic and DNS tests pass on a supported device/emulator;
- disconnect leaves no process, descriptor, or route behind;
- failure is visible and does not silently fall back to unprotected traffic.

Only then should the next backend be added.
