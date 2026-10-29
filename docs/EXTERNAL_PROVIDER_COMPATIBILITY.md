# Authorized external VPN provider compatibility

Status: design and import path only; no third-party server is operated by RØH.

## What can work today

The Android app's existing profile-import path accepts valid WireGuard wg-quick configuration. A user may import a configuration exported for their own account/device by a VPN provider, including Proton VPN, **only when that provider officially permits that use**. The profile remains the user's credential; RØH must not copy it into the RØH server directory, log it, or send it to RØH provisioning.

This is BYO-profile compatibility, not a Proton integration and not a source of free RØH servers. The app can only connect if the profile is valid for the provider, the account has the required entitlement, and the current WireGuard backend can establish the tunnel. Current UI messaging must not imply that RØH supplies or operates the third-party endpoint.

## What must not be done

- Do not scrape or hard-code Proton, Hiddify, or another VPN app's private/server inventory.
- Do not reuse another provider's private keys, account tokens, subscriptions, API credentials, or generated profiles.
- Do not route RØH users through a third party's infrastructure without an explicit, documented provider agreement and an authorized API.
- Do not represent third-party endpoints as RØH-operated servers.
- Do not put a shared provider token in the APK or ship server private keys in the repository.

Public endpoint metadata is not permission to use a VPN service. A TCP probe or a reachable port is not proof of authorization, a valid VPN handshake, or successful packet forwarding.

## Supported integration paths

1. **User-imported profile (current limited path):** user obtains an officially supported WireGuard profile from their provider and imports it locally. The profile must stay local and secrets must be cleared from transient buffers. Provider subscription, export policy, and server access remain the user's/provider's responsibility.
2. **Provider-authorized API (future):** add a dedicated provider adapter only after the provider documents and authorizes the API, authentication, entitlement checks, profile lifetime, rate limits, and revocation behavior. Store user tokens in Android Keystore-backed storage; never bundle shared credentials.
3. **RØH-operated servers (production requirement):** operate nodes and an HTTPS provisioner under RØH control, issue short-lived per-user/device profiles, and add only those verified endpoints to the RØH directory.

## Release gates shared with the three core tracks

External-provider import does not replace the following release requirements:
- Track A: a real handshake, tunneled HTTP(S), DNS, IPv4/IPv6 behavior, leak checks, and recovery across network changes.
- Track B: Aether, Xray, and sing-box remain unavailable until their actual native adapters and TUN paths are built and tested. WireGuard profile import does not make those engines operational.
- Track C: at least one authorized, operated RØH server with real profile provisioning is required before RØH can claim it supplies VPN servers.

Until those checks pass, describe this project as a development build with WireGuard profile import—not as a production multi-engine VPN service.
