# RØH VPN control-plane scaffold

This Cloudflare Worker + D1 project is a deployable **control-plane scaffold**, not a VPN node or a live production service. It intentionally ships with an empty server directory. No endpoint, account, private key, or access token is fabricated.

## API
- GET /healthz: health probe.
- GET /v1/servers: enabled public metadata in the Android client schema.
- GET /v1/servers/{id}/profile: requires a short-lived HS256 JWT with aud=roohvpn-profile, a sub, and an expiry no more than one hour away. Calls a separately operated HTTPS provisioner and wraps the result with a 15-minute expiry.

The Worker does not generate WireGuard keys, allocate VPN addresses, configure Linux peers, or route traffic. The provisioner must create/revoke per-user/device profiles, apply quotas, and never log secrets. Do not connect it to another provider without explicit authorization.

## Required deployment steps
1. Create a Cloudflare D1 database named roohvpn-directory and replace database_id in wrangler.toml with its actual ID.
2. Apply migrations/0001_create_servers.sql. All rows are disabled by default.
3. Deploy a real VPN node and authenticated HTTPS provisioner. It must create/revoke per-client WireGuard peers and return JSON with serverId, engine, and configuration for the requested subject. Test routes, DNS, firewall, key rotation and abuse controls.
4. Configure PROVISIONER_URL, PROVISIONER_TOKEN, and a random 32+ character JWT_HS256_SECRET as Worker secrets/variables. Never commit values.
5. Configure a trusted app base URL and a runtime token issuer. Never bake a shared bearer token into the APK.
6. Add only operated/authorized server metadata to D1 and enable it after end-to-end tests.
7. Deploy with Wrangler, configure HTTPS/custom domain, rate limits/WAF, monitoring, privacy disclosures and incident response.

## Production blockers
- Account/device authentication and JWT issuance are external and not implemented here.
- The provisioner and VPN node are required external systems and are not included because no server, credentials, or deployment authority were supplied.
- Add per-user/device quotas, revocation, rate limiting and abuse controls at Cloudflare and the provisioner.
- Rotate secrets and ensure logs never include bearer tokens, private keys or profiles.
- Do not enable a directory row until the VPN node passes real traffic and DNS tests.

Run npm test with Node.js 20 or newer. Tests use Node's built-in runner and no third-party package.
