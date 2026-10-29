# sing-box integration status

The Android app includes libbox and now has both bounded structural preflight and a native configuration-validation adapter (`SingBoxNativeConfigValidator`). The adapter requires the application to initialize libbox with `Libbox.setup(...)` first. Neither validator starts a tunnel.

## Remaining before sing-box can be registered as available

- A foreground Android `VpnService` implementing the exact `PlatformInterface` required by the pinned libbox API.
- Command-server creation, service start/reload, status/error propagation and deterministic stop/descriptor cleanup.
- Protect engine-owned sockets before establishing the TUN.
- Explicit VPN consent handling and safe service lifecycle/revocation behavior.
- Runtime initialization of libbox and calling native validation before every start.
- Device tests for TCP, UDP, DNS, IPv4/IPv6, network handoff, socket protection and leak prevention.

Do not mark `EngineId.SING_BOX` as available in `EngineRegistry` until these steps and tests pass. A successful config check is not evidence that traffic is being forwarded.
