# Third-party VPN provider integration boundary

Status: **not integrated** (2026-10-27). This document applies to Proton VPN or any other third-party VPN operator.

## What the current app can do

RØH has an HTTPS server-directory client and an authenticated profile-provider client. They expect the RØH API schema documented in `SERVER_DISCOVERY_API.md` and a short-lived WireGuard profile response. They are client-side contracts only; they do not discover credentials, provision accounts, or grant access to a provider's VPN fleet.

## Conditions for a real provider integration

Before implementing a provider-specific adapter, the project needs all of the following from the provider/operator—not only permission to reuse its open-source client code:

1. Written authorization covering third-party client use of the provider's actual VPN infrastructure and profile-provisioning mechanism.
2. Official documented API endpoints and supported authentication/authorization flow, including allowed client identity and rate limits.
3. A test account or sandbox credentials supplied through a secure channel, with revocation/expiry semantics.
4. Confirmation that the relevant plan/account permits the requested protocol and use; the adapter must not bypass subscriptions, account limits, or app authentication.
5. Protocol-compatible configuration that the RØH build can actually run. At present, only the WireGuard backend is registered; a Proton protocol/profile cannot be assumed to be WireGuard-compatible.
6. Provider-specific privacy, telemetry, terms, license, and branding review. Third-party servers must be labeled as third-party and never represented as RØH-operated.

A maintainer's permission to use source code is useful for code licensing, but it is not by itself authorization to use server infrastructure, internal APIs, user credentials, or private configuration endpoints. No credentials, internal endpoints, or provider-approved integration contract are present in this repository session.

## Safe implementation boundary

- Do not scrape mobile-app traffic, extract private API tokens, reuse another user's subscription/configuration, or hard-code provider credentials.
- Do not infer an API from undocumented endpoints or make a best-effort request to guessed URLs.
- Keep provider integration behind an explicit opt-in adapter and a separate provider identity; do not replace the RØH directory silently.
- Provision short-lived, account-bound profiles only through the provider-approved flow; never store secrets in the public directory or source control.
- Keep the provider disabled until authorization, protocol compatibility, and real end-to-end tests are complete.

## Exact next integration inputs

Supply the official provider API documentation URL or integration specification, the written authorization scope, and a sandbox/test credential mechanism (not the credential itself in chat). With those, implement the provider-specific adapter against the published contract and add mock tests. Production credentials should be configured in the deployment secret manager, not committed to Git or bundled in the APK.
