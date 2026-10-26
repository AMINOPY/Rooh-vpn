# RØH VPN capability backlog (Hiddify / Proton-inspired)

This checklist is a product-engineering plan based on publicly described user-facing capabilities. It does not copy proprietary implementation, branding, server infrastructure, or credentials. UI redesign is intentionally deferred while the core is being built.

## Connection and server experience

- [x] Typed engine identity and capability contract.
- [x] Provider-backed server directory interface.
- [x] HTTPS discovery client with strict HTTPS requirement, timeouts, response-size bound, schema version, and candidate cap.
- [x] TCP reachability probe and deterministic latency/priority selector.
- [x] Auto-connect orchestration boundary that fetches a short-lived profile and refuses unsupported engines.
- [ ] Operated RØH server-directory endpoint.
- [ ] Authenticated profile provisioning and short-lived credentials.
- [ ] Real engine handshake + HTTP/DNS traffic test before reporting connected.
- [ ] Bounded retry/backoff, cancellation, and automatic recovery after network changes.
- [ ] Favorite/last-known-good endpoint persistence and health history.

## Engine and tunnel

- [x] Common backend lifecycle contract and failure handling.
- [x] Official WireGuard Android tunnel dependency and adapter.
- [ ] Wire the adapter into user-consented app connection controls.
- [ ] Verify complete TUN packet path, DNS, IPv4/IPv6 and MTU on device/emulator.
- [ ] Per-app inclusion/exclusion with Android VPN Builder and engine routing kept consistent.
- [ ] Process-death recovery, VPN revoke handling, airplane mode and sleep/resume tests.
- [ ] Kill-switch claims only after leak tests and documented Android system limitations.
- [ ] Integrate Xray, Aether and sing-box independently after the first end-to-end engine is stable.
- [ ] Traffic counters measured from actual tunnel/network interfaces; no synthetic stats.

## Privacy and security

- [x] Android Keystore AES-GCM encryption for locally saved profiles.
- [x] Secret configuration buffers cleared by the connection coordinator.
- [x] HTTPS-only directory client with default TLS certificate validation.
- [ ] Complete per-profile CRUD and migration tests on supported Android devices.
- [ ] Redacted diagnostics with export consent and bounded retention.
- [ ] Security review for native binaries, ABI packaging, and supply-chain checksums.
- [ ] Release signing key stored outside the repository and only exposed to protected release CI.
- [ ] Privacy policy, server operator disclosure, telemetry opt-in, retention, and account deletion semantics.

## Provider and service integrations

- [ ] RØH-operated infrastructure and abuse/availability monitoring, or a provider-authorized integration.
- [ ] Server inventory, capacity, region, health, and maintenance metadata.
- [ ] Secure account or anonymous entitlement model with rate limiting and abuse prevention.
- [ ] Do not scrape, embed, or redistribute Proton VPN free server credentials or endpoints.
- [ ] Do not claim that Hiddify/Aether service modes are generic free servers; document who operates each endpoint and what trust applies.

## Quality gates

- [x] Android debug build and JVM test workflow.
- [ ] Latest CI green after the latest commit.
- [ ] Unit tests for every backend and provisioning error path.
- [ ] Android instrumentation tests for permission/service lifecycle.
- [ ] Device/emulator integration test with real HTTPS traffic through the tunnel.
- [ ] DNS and IPv6 leak tests.
- [ ] Battery, reconnect time, throughput, latency and thermal measurements on representative devices.
- [ ] Reproducible native builds, dependency/license inventory, SBOM, notices and release signing validation.

A feature is complete only when its tests pass and it is verified on the actual Android packet path. Documentation or a UI toggle alone does not count.
