<p align="center">
	<img src="logo.png" width="376" height="128" alt="Winlator Secure logo" />
</p>

# Winlator Secure

Winlator Secure is an unofficial community fork of
[Winlator](https://github.com/brunodev85/winlator), an Android application for
running Windows (x86_64) applications with Wine and Box86/Box64.

This fork is not affiliated with, sponsored by, or endorsed by the upstream
Winlator project or its maintainers. Do not request support for this fork from
the upstream maintainers. The Android package name is `com.winlator.secure`, so
it can be installed separately from the official application.

## Distribution status

The repository-level terms in [LICENSE](LICENSE) do not override the licenses
of bundled third-party components. The release inventory, license references,
source obligations, and artifact hashes are documented in
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

The APK does not contain Microsoft runtime DLLs or Microsoft/Monotype fonts.
It also does not contain upstream `rootfs.tzst` or `container_pattern.tzst`.
Before system-file installation, the app asks permission to download the
official Winlator 11.1 APK (about 150 MB) directly from its canonical GitHub
release, verifies its pinned size and SHA-256, and imports only the three
runtime entries into app-private storage.

When the user approves first-launch setup, Winlator downloads hash-pinned,
unchanged prerequisite installers directly from Microsoft and runs them only
inside the user's local `agm.default` Wine container. Microsoft installers are
not mirrored through the Winlator update feed.

The release replaces the previous SONiVOX soundfont with GPL-2.0-only
TimGM6mb, replaces proprietary core fonts with OFL-licensed Liberation fonts,
and includes their license and provenance files.

See [PRIVACY.md](PRIVACY.md) for ML Kit metrics, model downloads, and local
prerequisite-storage disclosures.

See [SOURCE_OFFER.md](SOURCE_OFFER.md) for corresponding-source availability.
See [PROVENANCE.md](PROVENANCE.md) for the exact upstream revision and fork
modification statement, [BUILD.md](BUILD.md) for public builds, and
[RELEASE.md](RELEASE.md) for external signing and artifact verification.

## Credits and third-party software

- GLIBC Patches by [Termux Pacman](https://github.com/termux-pacman/glibc-packages)
- Wine ([winehq.org](https://www.winehq.org/))
- Box86/Box64 by [ptitseb](https://github.com/ptitSeb)
- Mesa (Turnip/Zink/VirGL) ([mesa3d.org](https://www.mesa3d.org))
- DXVK ([github.com/doitsujin/dxvk](https://github.com/doitsujin/dxvk))
- VKD3D ([gitlab.winehq.org/wine/vkd3d](https://gitlab.winehq.org/wine/vkd3d))
- CNC DDraw ([github.com/FunkyFr3sh/cnc-ddraw](https://github.com/FunkyFr3sh/cnc-ddraw))
- GTK3 ARM64 rootfs runtime: Ubuntu 24.10 (Oracular) `main` arm64 packages, including
  `libgtk-3-0t64` 3.24.43-3ubuntu2 and its runtime dependency closure, obtained from
  [Ubuntu old-releases](https://old-releases.ubuntu.com/ubuntu/) and verified against
  its `dists/oracular/main/binary-arm64/Packages.gz` SHA-256 metadata. GTK is
  LGPL-2.1-or-later; GStreamer is LGPL-2.1-or-later; the bundled dependency files
  remain under their respective upstream package licenses. The rootfs patch also
  exposes the existing GStreamer plugin directory through the Wine/Box64 lookup
  paths.

Special thanks to all the developers involved in these projects.<br>
Thank you to all the people who believe in this project.