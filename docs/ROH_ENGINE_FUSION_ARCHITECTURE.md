# RØH engine fusion architecture

## Product decision

- **Xray is the parent engine** and the default owner of Android's single TUN interface.
- **PG CORE is a helper/fusion policy**, not a second full-device VPN service. Its flow planner may choose a path for a new flow only when it has fresh, real measurements and a live transport adapter.
- **RØH Engine Fusion** is the independent orchestration layer. It resolves engine roles, capability requirements, availability, and TUN ownership before native startup. It does not depend on PG CORE to make all decisions.
- sing-box, WireGuard, and Aether can be registered as transports or alternative backends only after their own real data paths, socket protection, lifecycle, and device tests pass.

## Conflict-resolution rules

1. One Android TUN descriptor and one service own it. Never start multiple full-device VPN services concurrently.
2. Xray remains the configured parent. A requested different parent is rejected rather than silently changing product policy.
3. An engine must be available and have a verified data plane before it can participate in an active fusion plan.
4. Optional helpers/transports that are absent, unhealthy, unverified, declared with the wrong role, or attempting to own a second TUN are skipped with a safe warning. They are never silently treated as active.
5. The primary must meet required IPv4, IPv6, and DNS capability requirements.
6. PG CORE flow selection is a scheduling primitive: it is not packet forwarding, multipath bonding, authenticated framing, or proof that traffic traverses multiple engines.
7. Existing profiles keep their engine-specific configuration. Do not merge raw Xray, sing-box, or WireGuard configs into one shared config; use validated adapters and explicit capabilities.
8. Native startup must be serialized, partial starts cleaned up, secrets redacted, and the connection must fail closed if cleanup or native status cannot be confirmed.

## Current implementation boundary

EngineFusionCoordinator implements deterministic role resolution and fail-closed conflict checks. Its unit tests cover the Xray parent, an unverified PG helper, duplicate registration, required capabilities, and competing TUN ownership. It is an orchestration policy layer only; it does not yet start helpers, route packets, or bridge data between engines.

PG CORE's existing allocator/selector remains responsible for capacity reservations and fresh-measurement path selection. The future integration point is a real flow adapter that atomically reserves a path, hands a new flow to that transport, and closes the lease on flow teardown. Existing flows must remain pinned unless a protocol-safe migration mechanism is implemented.

## Single-APK native runtime blocker

The current Xray and prebuilt sing-box gomobile bindings export duplicate Java runtime classes such as go.Seq. Adding both AARs to one Android application fails duplicate-class validation. Do not disable the check, exclude arbitrary classes, or claim that separate Gradle flavors create a combined APK.

A valid single-APK implementation must rebuild the sing-box binding with a compatible isolated Java package/runtime and resolve native-library loading/registration collisions, or use a tested process-isolation design with a real authenticated IPC packet bridge. The final APK must prove that both runtimes load simultaneously and pass the same traffic tests. Until that exists, the combined package is blocked; isolated Xray and sing-box builds do not satisfy the one-APK requirement.

## Release gates still required

- Implement and register the real TUN-to-engine data plane; ensure every upstream socket is protected before dialing.
- End-to-end TCP, UDP, DNS, IPv4, IPv6, MTU, and HTTP(S) egress tests on physical Android devices.
- Wi-Fi/mobile handoff, permission revocation, process death, repeated connect/disconnect, and partial-start cleanup.
- DNS/IP leak checks and controlled RTT/throughput measurements with recorded test endpoints and results.
- PG CORE helper activation only after the native bridge and its transport are available and verified.
- sing-box combined-runtime compatibility, Aether integration/licensing review, and WireGuard end-to-end validation.
- A green CI build and unit tests are necessary, but do not substitute for device evidence.
