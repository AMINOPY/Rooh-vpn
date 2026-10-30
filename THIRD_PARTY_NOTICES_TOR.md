# Third-party notices for Tor

The Android native archives are fetched from the pinned
[ZedSecure-Cores tor-r4 release](https://github.com/CluvexStudio/ZedSecure-Cores/releases/tag/tor-r4).
The release identifies its Tor build source as commit `ff593ec`. The RØH
build script pins the release tag and verifies each Android ABI archive with
the SHA-256 digest published by the release metadata.

The release contains these upstream components:

- Tor: https://gitlab.torproject.org/tpo/core/tor
- Lyrebird / obfs4 and WebTunnel transport: https://gitlab.torproject.org/tpo/anti-censorship/pluggable-transports/lyrebird
- Snowflake transport: https://gitlab.torproject.org/tpo/anti-censorship/pluggable-transports/snowflake
- Conjure transport: https://gitlab.torproject.org/tpo/anti-censorship/pluggable-transports/conjure

The Android archive is a prebuilt binary distribution from the ZedSecure-Cores
release, not a binary downloaded directly from the Tor Project. Its build
pipeline applies a Tor patch that adds a custom `--fake-hosts` option and
changes TLS-related source files. RØH does not enable or invoke that option.
The build/source provenance and corresponding licenses must be reviewed
before public distribution; this notice index does not replace the complete
license texts or corresponding-source obligations of the individual
components.

Tor is distributed under a BSD-style license. The pluggable transports retain
their respective upstream licenses. Include the complete license and notice
files from the exact source revisions used to produce `tor-r4` in any
redistribution. Do not treat the release archive's checksum as a substitute
for source verification or license compliance.
