# RØH VPN server discovery contract

RØH VPN must not ship fabricated server addresses or borrow credentials from another VPN provider. One-tap connection requires a real server operator or a provider-authorized API.

## Suggested API contract

- `GET /v1/servers` — authenticated or otherwise abuse-controlled discovery response.
- `GET /v1/servers/{id}/profile` — issue a short-lived profile only after policy and authorization checks.
- `POST /v1/telemetry/connection` — optional, privacy-reviewed aggregate outcome; off by default unless consent and policy are implemented.

The API must use HTTPS with standard certificate validation. Do not pin a certificate without a rotation strategy. Do not put long-lived credentials in the APK.

## Candidate response fields

Each server candidate should include a stable opaque ID, hostname, port, region label, supported engine IDs, protocol/capability metadata, and priority. Secrets should be returned separately and only when needed. Responses must be versioned and validated before use.

## Selection and fallback

1. Fetch candidates from the configured trusted directory.
2. Reject malformed entries, unsupported engines, invalid ports, and unsupported config versions.
3. Apply bounded reachability probes on a worker thread.
4. Rank reachable candidates by measured latency and recent authenticated handshake success.
5. Attempt a real engine handshake and a known traffic test before declaring connected.
6. Retry only a bounded number of times, with backoff; stop cleanly on cancellation.
7. Never silently route unprotected traffic while presenting the VPN as connected.

A TCP probe alone only proves a socket accepted a connection; it does not prove the VPN protocol works.

## Provider boundaries

- Proton VPN's free service is not a generic public server pool. Do not scrape, hard-code, or redistribute Proton credentials/endpoints. Use only an explicitly supported integration.
- Hiddify and Aether can inform client workflow and public architecture, but their server infrastructure, branding, and app code are not automatically reusable.
- The app must disclose who operates the server, what connection metadata is collected, retention periods, and how users can disconnect or delete stored profiles.
