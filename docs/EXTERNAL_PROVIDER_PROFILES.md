# External VPN provider profiles (Proton VPN and similar services)

Status: compatibility path only; not a bundled server directory.

## What is supported today

RØH can classify and parse standard WireGuard wg-quick configurations in its WireGuard code path. A user may select a configuration they generated/downloaded through their own account with an external provider, where that provider permits third-party WireGuard clients. This is BYO-profile compatibility; it does not make the provider's nodes RØH infrastructure, and it does not imply the end-to-end RØH tunnel is release-ready.

For Proton VPN specifically, Proton's published instructions say users can sign in to their account and generate a WireGuard configuration from Downloads → WireGuard configuration, then use that profile with a compatible third-party WireGuard client. See:
- https://protonvpn.com/support/wireguard-configurations
- https://protonvpn.com/support/wireguard-manual-android

## Security and product rules

- Never scrape provider endpoints, copy private server keys, bundle account-bound configs, or automate provider account access.
- Never commit a user's .conf, private key, provider credentials, subscription URL, or token to Git, CI logs, analytics, or the APK.
- Treat each imported profile as user-owned secret material. Keep it encrypted at rest, clear transient byte buffers, and do not expose raw profile contents in diagnostics.
- The profile's provider and endpoint must be labeled accurately. Do not list external-provider endpoints as RØH-operated nodes or imply RØH controls their availability, privacy policy, routing, or logging.
- Only add a server to the RØH directory if RØH operates it or has explicit authorization from its operator, with a documented support and privacy arrangement.
- Do not use an external profile as proof that RØH's own directory, provisioning service, or multi-engine adapters are implemented.

## Remaining validation before advertising a working connection

1. Wire the imported profile into the actual Android VPN service and one real engine-owned TUN/packet-forwarding lifecycle.
2. Verify a WireGuard handshake, tunneled DNS, IPv4, IPv6 where supported, and an HTTPS request using a controlled endpoint; check for leaks.
3. Exercise network handoff, loss/recovery, permission revocation, service/process death, and repeated start/stop on emulator and physical devices.
4. Confirm the user-visible connection state is based on engine status plus a successful traffic/health check, not profile parsing or endpoint reachability alone.

## RØH-operated infrastructure

A real RØH server directory and short-lived profile provisioner require deployed, authorized VPN nodes and server-side secrets. Those cannot be manufactured by client-side code or replaced by another provider's account-bound configuration. Until those exist, the app must not advertise RØH-owned servers as available.
