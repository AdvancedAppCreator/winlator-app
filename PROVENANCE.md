# Provenance and modifications

Winlator Secure is derived from Winlator 11.1 at upstream commit
`7e2699213a1e6282263706392ba6b644a2178e3b` (`app: Fix custom key handling
in input Controls`, 2026-06-14):

<https://github.com/brunodev85/winlator-app/commit/7e2699213a1e6282263706392ba6b644a2178e3b>

The public source snapshot does not share Git ancestry with that upstream
repository, so the revision above is the comparison anchor, not a claimed
merge base. Compare it by fetching that commit and diffing its tree against
this tree.

## Modification statement

This fork changes the application ID and branding; adds an authenticated API
and managed-game integration; adds diagnostics, recovery, text translation,
mod management, runtime dependency setup, and secondary-profile support;
changes container, renderer, input, font, audio, and Japanese-game
compatibility behavior; replaces or removes several bundled assets; and adds
release/privacy/provenance material. It also pins the upstream runtime download
catalog at commit `b6b2259158cf38d06067c34430d840d56b46d220`.
The fork does not package upstream `rootfs.tzst`, `container_pattern.tzst`, or
`rootfs_patches.tzst`.
After user consent it downloads the official `brunodev85/winlator` `v11.1.0`
release asset `Winlator_11.1.apk` directly from GitHub, verifies size
`156943882` and SHA-256
`80bdea17d8497a2ae0ff637e68d82a884ccc5ca4406880950b96fd2483e50970`,
then imports only those three runtime entries. Upstream publishes no independent
signing-certificate fingerprint for this asset, so none is asserted here.

This is a high-level statement, not a substitute for the source diff. Files
carried unchanged retain their original notices. Files changed by this fork
remain under their applicable existing licenses; no broader relicensing is
claimed.
