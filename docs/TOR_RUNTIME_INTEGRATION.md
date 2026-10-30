# Tor runtime integration

RØH packages the Android Tor client and its supported pluggable transports from
the checksum-pinned `tor-r4` release of
[CluvexStudio/ZedSecure-Cores](https://github.com/CluvexStudio/ZedSecure-Cores/releases/tag/tor-r4).
The build script verifies each ABI archive against the SHA-256 values published
by that release before extracting native executables.

## Runtime

- `libtor.so`: Tor client process.
- `libobfs4proxy.so`: obfs4 and WebTunnel transport helper.
- `libsnowflake.so`: Snowflake transport helper.
- `libconjure.so`: Conjure transport helper.
- Supported Android ABIs: arm64-v8a, armeabi-v7a and x86_64.

The build also fetches the GeoIP and GeoIPv6 databases from the immutable
ZX-VPN source commit `41796b68fc171a351fa668d7991499cca395c0d0`, verifies
their Git blob IDs, and stores gzip-compressed assets. The Tor controller
expands them into its private data directory before launch.

The Tor controller writes a private torrc and data directory under the app's
internal files directory. Its SOCKS listener is bound only to
`127.0.0.1` (default port 9250). It reports bootstrap progress from Tor's
stdout and confirms that the SOCKS5 no-auth greeting works before reporting
the local proxy as ready.

## Settings

The app persists bridge mode and transport, custom bridge lines, entry/exit
and excluded node selectors, IPv4/IPv6 preferences, connection padding,
disk-write preference, circuit timing, and SOCKS isolation flags in a
dedicated SharedPreferences file. User-provided Torrc values are validated
before they are written. Bridge lines are emitted as one directive per line.

## Important routing boundary

This release exposes Tor as a loopback SOCKS proxy. It is **not** a second
Android VPN service and does not claim to route every device application
through Tor. The current Xray/sing-box chaining adapter must not be used with
Tor until Tor's native sockets can be protected from the active VPN TUN. Simply
starting Tor beside an existing VPN can create a routing loop; excluding the
whole app package would also bypass protection for unrelated app traffic.
Therefore Tor-to-Xray/sing-box chaining is intentionally not enabled yet.

Bridge reachability is not proof of a successful Tor circuit. The controller
reports local SOCKS readiness separately from Tor's bootstrap percentage.
Physical-device tests are still required for each ABI, bridge transport,
network handoff, and long-running operation.

## Build

CI runs:

```sh
bash scripts/get-tor-runtime.sh
```

The script downloads only the pinned `tor-r4` Android archives, checks their
SHA-256 digests, and stages the Tor and transport executables in
`app/src/main/jniLibs/<abi>/`.
