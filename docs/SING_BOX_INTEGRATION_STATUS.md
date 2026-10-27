# sing-box integration status

The Android app currently includes the libbox dependency, but this is not a connected VPN engine. SingBoxProfilePreflight performs bounded structural checks only; it is not the upstream libbox config validator and does not start a tunnel.

Before registering EngineId.SING_BOX, the app still needs:
- a foreground Android VpnService implementing the exact PlatformInterface required by the pinned libbox API;
- command-server creation, service start/reload, status/error propagation and deterministic stop/descriptor cleanup;
- protecting engine-owned sockets before establishing the TUN;
- explicit VPN consent handling and safe service lifecycle/revocation behavior;
- native libbox configuration validation for the pinned version;
- device tests for TCP, UDP, DNS, IPv4/IPv6, network handoff, socket protection and leak prevention.

Do not mark sing-box as available in EngineRegistry until these steps and tests pass. Do not interpret structural preflight success as runtime validation.
