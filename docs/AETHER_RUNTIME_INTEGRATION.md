# Aether native runtime integration

## Runtime layout

RØH builds the Aether Rust engine and HEV's in-process TUN-to-SOCKS bridge
from the pinned upstream build pipeline. Psiphon is an optional native
artifact and is not required for the built-in WARP mode:

```text
Android apps
  -> RØH AetherVpnService (the only TUN owner)
  -> HEV native pthread / TUN fd
  -> 127.0.0.1:1819 (Aether SOCKS5)
  -> Aether Rust engine
  -> WARP / internet
```

The application package is excluded from its own VPN interface. This prevents
Aether's own upstream sockets from looping back into the TUN. The engine SOCKS listener is required to pass a SOCKS5 greeting before the forwarding bridge is started.
The TUN then routes IPv4 and IPv6 defaults and advertises the configured DNS
resolvers. If the engine process exits, the forwarding bridge is stopped and
the VPN interface is not replaced with a direct route.

## Pinned native inputs

- Aether Mobile upstream: `QW-AI-Code/Aether`, commit
  `b3f7a501cb2e2106b5fc43a6ee15310042c520e6`.
- HEV tunnel source: `heiher/hev-socks5-tunnel`, tag `2.19.3`.
- Native build script: `scripts/build-aether-runtime.sh`.
- JNI bridge: built from the pinned Aether bridge source, with the RØH
  running-state JNI extension tracked in `native/aether/aethertun-running-jni.c.inc`.
- ABIs: `arm64-v8a`, `armeabi-v7a`, and `x86_64`.

The CI job uses the upstream Rust/NDK build recipe, packages the engine and
required runtime dependencies, and verifies the executable, HEV shared library,
and `libaethertun.so` JNI bridge are present in the APK. Psiphon is built and
staged opportunistically; a missing Psiphon binary does not disable WARP. The upstream Aether application's
separate UI/service code is not imported; RØH owns its own Android VPN service.

## Identity handling

Aether's WARP identity files can contain the device identity and WireGuard
private key. RØH seals matching `aether*.toml` files with an Android Keystore
AES-GCM key after the engine is stopped and restores them immediately before
the engine starts. This is device-bound storage; restoring app data without
the original Keystore key may require Aether to provision a new identity.

## Deliberate limitations

- The first RØH backend starts the built-in MASQUE/WARP mode. The native Psiphon and Tor executables are packaged, but their modes are not exposed by the RØH UI yet; the Psiphon UDP/DNS front, Tor configuration, and their lifecycle supervision still need integration and validation.
- A successful native build or open local SOCKS port is not proof of end-to-end
  forwarding. Real-device TCP, UDP, DNS, IPv4/IPv6, socket-leak, network-handoff,
  crash-recovery, and long-duration tests remain release gates.
- The service is registered only when Aether, the HEV bridge, and the TUN JNI
  library exist for the installed ABI. Psiphon is optional and is exposed to
  the process only when its executable is present.
- Keep the PR in draft until Android CI passes and physical-device evidence is
  attached. Do not merge this branch into main on the basis of compilation alone.

## Licensing

The Aether application and native engine are AGPL-licensed (the native Cargo
manifest declares AGPL-3.0-only); HEV retains its Apache-2.0/MIT notices. Any
redistributed build containing these components must meet the applicable
corresponding-source and notice obligations. Review the exact upstream
`LICENSE` and `THIRD_PARTY_NOTICES.md` before publishing binaries.
