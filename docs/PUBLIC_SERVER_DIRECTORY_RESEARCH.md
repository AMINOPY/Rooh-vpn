# Public community server-directory research

Research date: 2026-10-26

## Scope

The request is for **server directory metadata**, not a subscription/configuration feed. A directory entry may describe an operator, host, port, protocol, region, and current health. It is not itself a usable VPN connection: the matching protocol engine and authorized connection material are still required.

## Candidate found

- **VPN Gate list mirror:** https://github.com/GeorgeXie2333/vpngate-list-mirror
  - The project describes itself as a public HTTPS mirror of the VPN Gate server directory/API and exposes server inventory metadata (including public endpoint information).
  - The upstream servers are **OpenVPN** servers, not WireGuard peers. The current RØH build registers only its WireGuard backend, so these entries cannot currently be connected by RØH.
  - Its availability/latency metadata is not a guarantee that a server is reachable from a given device or that a VPN session will succeed.
  - Treat this as a research candidate only. Do not display these entries as RØH-operated servers, do not automatically connect to them, and do not store their credentials/configurations in the app. Integration would require an OpenVPN engine, protocol/license review, live endpoint validation, and a clear third-party operator/trust disclosure.

## WireGuard-specific findings

The search also surfaced projects such as [nullGuard](https://github.com/nullata/nullGuard), [wireadmin](https://github.com/wireadmin/wireadmin), and [claimward-vpn-server](https://github.com/claimward/claimward-vpn-server). These are **self-hosted server-management/control-plane software**, not public pools of free servers offered by their maintainers. They require an operator to deploy and administer a gateway; they do not provide RØH with a ready-to-use, publicly authorized WireGuard endpoint. They are not integrated as endpoints.

No verified, compatible, community-operated free WireGuard server directory was identified in this search. That is not evidence that none exists; it means the current evidence is insufficient to add one safely.

## Excluded from this request

Repositories that primarily publish subscription URLs, proxy share links, or complete client configurations are not server-directory sources for this feature and should not be mistaken for a server inventory.

## Acceptance rules for any community directory

1. Keep the source URL explicit and HTTPS-only; do not silently replace the trusted RØH directory.
2. Label each item as a third-party/community server and identify the source/operator when known.
3. Keep server metadata separate from credentials, keys, and profile payloads.
4. Never claim a server is live based only on a repository entry or TCP probe. Confirm protocol handshake and real DNS + HTTPS traffic.
5. Filter out protocols without a registered backend. In this build, only WireGuard is registered; OpenVPN, Aether, Xray, and sing-box must remain unavailable until their complete data path is integrated and tested.
6. Do not redistribute private keys, credentials, or configuration bundles. Respect the source project's terms and the server operator's rules.

## Conclusion

A public community directory was found, but the reviewed candidate is OpenVPN-only and therefore is **not yet compatible with the current WireGuard-only RØH client**. It is not appropriate to insert its endpoints into the default RØH server list or describe them as ready-to-connect RØH servers.