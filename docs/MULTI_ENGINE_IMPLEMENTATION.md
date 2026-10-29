# Multi-engine implementation gates

Priority order: Xray-core, sing-box, Aether, WireGuard.

This order is product priority, not a claim that every backend is integrated. The currently verified implementation boundary is WireGuard. A successful CI build does not establish end-to-end VPN traffic correctness.

## Shared contract

Each engine adapter must validate its own format, expose truthful capabilities, start and stop deterministically, clean up partial starts, protect engine sockets from routing recursion, honor cancellation and finite timeouts, redact diagnostics, and pass the same Android traffic test suite.

## Per-engine acceptance gates

- **Xray-core (priority 1):** pinned upstream Android-compatible binding; reproducible native build; validated VLESS/VMess/Trojan/Shadowsocks formats supported by that exact version; real TUN/bridge path; protected sockets; DNS and IPv4/IPv6 tests; ABI and license review.
- **sing-box (priority 2):** pinned libbox API; actual Android service lifecycle; validated sing-box JSON; TUN, routes and DNS; protected sockets; native ABI packaging; traffic tests.
- **Aether (priority 3):** integrate only as a reviewed upstream stack, including its Rust engine and required tunnel bridge; review license and signing/update trust boundaries; validate JNI lifecycle, cleanup and ABIs.
- **WireGuard (priority 4):** retain the official Android tunnel library; validate profile import, permission revocation, start/stop, route/DNS behavior and real traffic on devices.

## Cross-engine release gates

1. Do not register a backend until its adapter and packet path are implemented.
2. Do not display Connected until a bounded real traffic probe succeeds.
3. Test malformed and oversized configuration, start failure, timeout, stop failure, cancellation and repeated connect/disconnect.
4. Test DNS, IPv4/IPv6, Wi-Fi/mobile handoff, airplane mode, process death and VPN permission revocation on supported Android devices.
5. Publish supported protocol/capability information only for the exact tested engine build.
6. Keep provider endpoints empty until authorized server provisioning and operational infrastructure exist.
