# Third-party component inventory and release status

Audit date: 2026-10-06

This document inventories third-party code, binaries, fonts, media, and runtime
archives tracked by this repository. It is a release-engineering audit, not
legal advice and not a substitute for the actual license texts or a review by
qualified counsel.

## Public repository and APK release gate: CONDITIONAL

The source tree no longer tracks third-party `.so`, `.tzst`, font, soundfont,
wallpaper, or input-profile payloads. `third_party/dependencies.lock.json`
pins generated build inputs by immutable URL, exact byte count, and SHA-256.
The build fails closed, reconstructs custom compatibility fonts from pinned VL
Gothic inputs, and builds Box64 from a pinned revision, patch, and compiler.
FontTools 4.62.1 is a hash-pinned build-only dependency; its source archive is
included in the corresponding-source bundle. The Arm GNU compiler is a
hash-pinned build tool and is not redistributed in the APK.

Publish an APK only with the corresponding-source bundle generated from
`third_party/sources.lock.json`, notices and SBOM from the same commit, and the
signed checksums described in `RELEASE.md`. The upstream rootfs, container
template, and rootfs patch are not in this repository or base APK; the user
explicitly downloads them from the pinned official Winlator release.

This inventory is release-engineering evidence, not legal advice. It does not
grant patent rights or replace review by qualified counsel.

Resolved payload removals and replacements:

- Upstream `rootfs.tzst` and `container_pattern.tzst` were removed from the
  repository and base APK. First run requires explicit consent to download the
  official Winlator 11.1 APK directly from its canonical GitHub release. The
  app verifies the complete APK's pinned size and SHA-256, validates its ZIP
  structure, verifies both extracted entries, and atomically commits them to
  app-private storage.
- Microsoft `wincomponents/*.tzst` archives were removed. Visual C++ and legacy
  DirectX setup now use user-confirmed, hash-pinned downloads from Microsoft;
  the files are cached only on the user's device.
- Microsoft/Monotype core fonts were removed from `rootfs_patches.tzst` and
  replaced by Liberation Fonts, Wine fonts, Noto Sans CJK, and VL Gothic.
- SONiVOX was replaced by `TimGM6mb.sf2` under GPL-2.0-only with source revision,
  hash, and full license text.
- `GPUInfo.exe`, `TestD3D.exe`, and bundled 7-Zip binaries were removed.

## Branding and upstream attribution

Winlator Secure is an unofficial fork of
[Winlator](https://github.com/brunodev85/winlator). It is not affiliated with,
sponsored by, or endorsed by the upstream project or its maintainers. The
upstream maintainers do not provide support for this fork.

The repository-level [LGPL-2.1 license](LICENSE) does not override separate
licenses or trademark rights attached to bundled third-party material.

## Application provenance and modifications

The application baseline is Winlator 11.1 commit
`7e2699213a1e6282263706392ba6b644a2178e3b`. This public snapshot has separate
Git ancestry; the commit is an exact comparison anchor, not a claimed parent.
`PROVENANCE.md` contains the modification statement and comparison guidance.

## Runtime archives and first-run imports

| Payload | Observed contents | License/provenance status |
|---|---|---|
| First-run `assets/rootfs.tzst` | Wine 10.10 and Linux runtime | Not distributed by this project. Imported after consent from the pinned official APK; size `65251198`, SHA-256 `8b5110f248e84f2aee4df37dab8bac4c4bf2bdc7b400c0643a0778ca8e7e40c2`. |
| First-run `assets/rootfs_patches.tzst` | Upstream compatibility overlay | Not distributed by this project. Imported from the same verified APK; size `4173700`, SHA-256 `44b73e37587ea827a12a34753632feb6e2a9c127089e342774167dd91aba8210`. |
| First-run `assets/container_pattern.tzst` | Prebuilt Wine prefix | Not distributed by this project. Imported from the same verified APK; size `7399363`, SHA-256 `8ae3a4fee33e86da26826395650bb07c6f49ce94629ea4b9442bc633b6b8ca33`. |
| PulseAudio, graphics drivers, DX wrappers and cnc-ddraw | Exact official Winlator APK entries | Binary entries are locked in `dependencies.lock.json`; source revisions and archives are locked in `sources.lock.json`. |
| Generated `box64-0.4.3-3.tzst` | Box64 plus the tracked Android EGL fallback patch | Built from revision `e2345d735af36f7915ab861a55b489d69f2350c7`, the tracked patch, and hash-pinned Arm GNU 14.2 toolchain. Input identity is fail-closed; output hashes in the provenance file identify the audited reference build because compiler output can differ across Windows runner images. |
| `app/src/main/assets/dxwrapper/cnc-ddraw-6.6/Shaders/**` | Filtering and scaling shaders | The previously untraced `nearest-neighbor.glsl` and `interpolation/bilinear.glsl` are text-exact copies of the corresponding files in the official 6.6.0.0 release asset after normalizing CRLF to upstream LF; the provenance JSON records raw and normalized hashes. Other shader notices remain embedded in their files. Covered by the packaged cnc-ddraw MIT notice. |
| Runtime-downloaded Microsoft prerequisites | Visual C++ 2005, 2008, 2010, 2012, 2013, current 2015-2026 runtimes, and DirectX June 2010 | Not distributed by this repository. The app stores official Microsoft HTTPS URLs, expected sizes, SHA-256 values, and license links; downloads remain local and original installers run inside the user's Wine prefix. |

The runtime source APK is
`https://github.com/brunodev85/winlator/releases/download/v11.1.0/Winlator_11.1.apk`,
size `156943882`, SHA-256
`80bdea17d8497a2ae0ff637e68d82a884ccc5ca4406880950b96fd2483e50970`.
No independently published upstream signing-certificate fingerprint was found
or is claimed. The official APK is treated strictly as a verified ZIP source;
it is not installed or executed by this app.

The component and input-profile catalogs are pinned to upstream commit
`b6b2259158cf38d06067c34430d840d56b46d220`. Each permitted path has an exact
size and SHA-256 in `app/src/main/assets/download-integrity.json`; missing or
mismatched entries fail closed. A configured curated driver URL is subject to
the same manifest and cannot introduce an unlisted executable.

Wine's Gecko/Mono menu actions use Wine's own downloader rather than
`HttpUtils`. Wine 10.10's
[`dlls/appwiz.cpl/addons.c`](https://github.com/wine-mirror/wine/blob/wine-10.10/dlls/appwiz.cpl/addons.c)
compiles in the expected Gecko 2.47.4 and Mono 10.1.0 SHA-256 values and calls
`sha_check()` both for cached files and after a download, before installation.
The two bundled `appwiz.cpl` binaries contain the corresponding architecture
filenames and hashes: i386 SHA-256
`c0f9c6092f6132975a2dc72e495c3931d1a3a4f218e6665b9740b8c4551760bd`
and x86_64 SHA-256
`1e7c3b5d663b22bd139b06e96052a1d8610c76c0a2121498a9484cd52661e34e`.
`WineUtils` pins `AddonsURL` to the immutable catalog revision. The manifest
therefore records only the three versions selected by these binaries; the
unused Wine Mono 9.0.0 entry was removed.

## Fonts, sound, and visual assets

| Payload | Identified origin/license | Status |
|---|---|---|
| `fonts/NotoSansCJKjp-Regular.otf` | Noto Sans CJK JP 2.004; SIL Open Font License 1.1 | OFL text and provenance are included. |
| `fonts/Liberation*.ttf` | Liberation Fonts 2.1.5-3 from the official Ubuntu package; SIL Open Font License 1.1 | Package hash, source URL, and complete Debian copyright/license text are included. |
| `fonts/VL-Gothic-Regular.ttf`, `fonts/VL-PGothic-Regular.ttf` | VL Gothic 2.121 | VL, M+, and Sazanami license documents are included. |
| `fonts/MS-*-Compatible.ttf` | Modified VL Gothic/VL PGothic with compatibility family names; `LiveMaker-compat-fonts.txt` documents the modification | These files do not contain Microsoft font data and retain the complete underlying license set. |
| First-run rootfs `/opt/wine/share/wine/fonts/*` | Sampled Tahoma and Wingdings-compatible files identify Wine authors and LGPL-2.1-or-later in embedded metadata | Include Wine's corresponding source and LGPL notice/source offer. |
| `soundfont/TimGM6mb.sf2` | TimGM6mb at revision `d6ad4ed72dce1fd3d67f17b74e08cd7ae7941a96`; GPL-2.0-only | Full license, source revision, and SHA-256 are included. |
| `logo.png`, launcher icons, wallpapers, input-control icons/profiles | Carried from the upstream application; no separate asset provenance or license declaration was found | Confirm upstream's right to sublicense these assets under the repository license or replace them with original assets. |

## Packaged JNI shared libraries

These files are dynamically packaged in the APK. Dynamic packaging avoids some
static-linking concerns but does not remove notice, corresponding-source, or
modification obligations.

| Libraries | Expected upstream license family | Status |
|---|---|---|
| `libbox64.so`, `libbox64launcher.so`, `libglibcloader.so` | MIT, repository terms, and glibc LGPL | Generated from pinned Box64 source/toolchain, tracked launcher source, and the verified upstream rootfs loader. |
| FluidSynth Android library set, including GLib, libinstpatch, libsndfile, Xiph codecs, PCRE and Oboe | LGPL, BSD and Apache families | Exact official FluidSynth 2.4.5 Android bundle, CI recipe, dependency versions, binary hashes and source archives are locked. |
| PulseAudio Android library set and `libltdl.so` | LGPL-family | Exact official Winlator APK entries; producer repository, build script, PulseAudio revision and sources are locked. |
| `libc++_shared.so` | Apache-2.0 with LLVM exception | Produced by pinned Android NDK `27.2.12479018`, matching FluidSynth's official Android build; not tracked as a blob. |

Publish the generated APK hashes and both lock files with each release.

## Java and Android dependencies

The versions below come from `app/build.gradle`. Transitive dependencies must
also be captured from the final Gradle dependency graph and release APK.

| Dependency | Version | License/terms to preserve or review |
|---|---:|---|
| AndroidX AppCompat | 1.4.0 | Apache-2.0 |
| AndroidX Preference | 1.2.1 | Apache-2.0 |
| Material Components for Android | 1.4.0 | Apache-2.0 |
| Google ML Kit text recognition modules | 16.0.1 | Google ML Kit and Google APIs terms plus transitive open-source notices; not distributed under the repository's LGPL. Review restrictions and required metrics/privacy disclosures. |
| Google ML Kit Language ID | 17.0.6 | Google ML Kit and Google APIs terms plus transitive notices and disclosures |
| Google ML Kit Translate | 17.0.3 | Google ML Kit and Google APIs terms, model-distribution/privacy requirements, and transitive notices; do not extract or redistribute downloaded models separately |
| zstd-jni | 1.5.2-3 | BSD-2-Clause wrapper plus the bundled zstd BSD/GPL dual-license materials |
| XZ for Java | 1.7 | Public-domain dedication; preserve available notices |
| Apache Commons Compress | 1.20 | Apache-2.0 and NOTICE |

Test-only dependencies are not expected in the release APK, but their licenses
still apply to source/build environments: JUnit 4.13.2, AndroidX Test Core
1.5.0, JSON 20231013, and Robolectric 4.10.3.

## Vendored native source

| Source area | Identified licensing |
|---|---|
| `app/src/main/cpp/virglrenderer` | MIT-style notices are embedded in source files; retain all copyright headers and add the upstream license file/provenance. |
| `app/src/main/cpp/libadrenotools` and `lib/linkernsbypass` | BSD-2-Clause license files are present. Some imported Android/Linux headers carry Apache-2.0 or Linux syscall-note identifiers. |
| `app/src/main/cpp/midihandler/fluidsynth/include` | FluidSynth LGPL-2.1 license file and headers are present. |
| `app/src/main/cpp/vortekrenderer/include/vulkan*` | Khronos Vulkan headers use Apache-2.0. |
| `app/src/main/cpp/gladiorenderer/include/stb_dxt.h` | Dual MIT/public-domain terms are embedded in the file. |
| Remaining Winlator, Vortek, and Gladio source | Covered by the repository license unless a file carries separate terms; exact upstream/original-work provenance should be recorded for release maintenance. |

## Authoritative license and terms references

These links identify upstream terms used to classify the inventory. Exact
binary and source relationships are recorded in the two lock files.

- Winlator:
  <https://github.com/brunodev85/winlator/blob/main/LICENSE>
- Wine:
  <https://gitlab.winehq.org/wine/wine/-/blob/HEAD/LICENSE>
- Wine 10.10 Gecko/Mono downloader and compiled-in SHA-256 enforcement:
  <https://github.com/wine-mirror/wine/blob/wine-10.10/dlls/appwiz.cpl/addons.c>
- cnc-ddraw 6.6.0.0 source, release asset, and MIT license:
  <https://github.com/FunkyFr3sh/cnc-ddraw/tree/v6.6.0.0>,
  <https://github.com/FunkyFr3sh/cnc-ddraw/releases/download/v6.6.0.0/cnc-ddraw.zip>,
  and <https://raw.githubusercontent.com/FunkyFr3sh/cnc-ddraw/v6.6.0.0/LICENSE>
- Box64 v0.4.3-3:
  <https://raw.githubusercontent.com/ptitSeb/box64/v0.4.3-3/LICENSE>
- Mesa:
  <https://docs.mesa3d.org/license.html>
- virglrenderer:
  <https://gitlab.freedesktop.org/virgl/virglrenderer/-/raw/main/COPYING>
- DXVK v1.10.3 and v2.4.1:
  <https://raw.githubusercontent.com/doitsujin/dxvk/v1.10.3/LICENSE> and
  <https://raw.githubusercontent.com/doitsujin/dxvk/v2.4.1/LICENSE>
- VKD3D-Proton v2.14.1:
  <https://raw.githubusercontent.com/HansKristian-Work/vkd3d-proton/v2.14.1/LICENSE>
- D7VK v1.11:
  <https://raw.githubusercontent.com/WinterSnowfall/d7vk/v1.11/LICENSE>
- D8VK v1.0:
  <https://raw.githubusercontent.com/AlpyneDreams/d8vk/v1.0/LICENSE>
- PulseAudio v13:
  <https://raw.githubusercontent.com/pulseaudio/pulseaudio/v13.0/LICENSE>
- FluidSynth:
  <https://raw.githubusercontent.com/FluidSynth/fluidsynth/master/LICENSE>
- libinstpatch:
  <https://raw.githubusercontent.com/swami/libinstpatch/master/COPYING>
- libsndfile:
  <https://raw.githubusercontent.com/libsndfile/libsndfile/master/COPYING>
- FLAC, Ogg, Vorbis, and Opus:
  <https://raw.githubusercontent.com/xiph/flac/master/COPYING.Xiph>,
  <https://raw.githubusercontent.com/xiph/ogg/master/COPYING>,
  <https://raw.githubusercontent.com/xiph/vorbis/master/COPYING>, and
  <https://raw.githubusercontent.com/xiph/opus/main/COPYING>
- GLib:
  <https://github.com/GNOME/glib/blob/main/LICENSES/LGPL-2.1-or-later.txt>
- PCRE1:
  <https://pcre.org/original/license.txt>
- Oboe:
  <https://raw.githubusercontent.com/google/oboe/main/LICENSE>
- Android NDK libc++:
  <https://developer.android.com/ndk/guides/cpp-support>
- Vulkan headers:
  <https://github.com/KhronosGroup/Vulkan-Headers/blob/main/LICENSE.md>
- Ubuntu package and branding policies:
  <https://ubuntu.com/project/docs/how-ubuntu-is-made/concepts/package-archive>
  and <https://canonical.com/legal/intellectual-property-policy>
- Noto CJK:
  <https://raw.githubusercontent.com/notofonts/noto-cjk/main/Sans/LICENSE>
- Liberation Fonts:
  <https://github.com/liberationfonts/liberation-fonts>
- VL Gothic:
  <https://raw.githubusercontent.com/daisukesuzuki/VLGothic/main/LICENSE.en>
  and
  <https://raw.githubusercontent.com/daisukesuzuki/VLGothic/main/LICENSE_E.mplus>
- TimGM6mb:
  <https://github.com/arbruijn/TimGM6mb>
- Microsoft Visual C++ and legacy DirectX redistribution guidance:
  <https://learn.microsoft.com/en-us/cpp/windows/redistributing-visual-cpp-files>
  and <https://www.microsoft.com/en-us/download/details.aspx?id=8109>
- Google ML Kit and Google APIs terms:
  <https://developers.google.com/ml-kit/terms> and
  <https://developers.google.com/terms>
- zstd-jni and zstd:
  <https://raw.githubusercontent.com/luben/zstd-jni/master/LICENSE> and
  <https://github.com/facebook/zstd/blob/dev/LICENSE>
- XZ for Java v1.7:
  <https://raw.githubusercontent.com/tukaani-project/xz-java/v1.7/COPYING>
- Apache Commons Compress:
  <https://github.com/apache/commons-compress/blob/master/LICENSE.txt> and
  <https://github.com/apache/commons-compress/blob/master/NOTICE.txt>

## Integrity manifests

`third_party/dependencies.lock.json` is the authoritative binary-input
manifest. `third_party/sources.lock.json` is the authoritative accompanying
source manifest.

## Release checklist

1. Build with both dependency locks verified and `-PofflineThirdParty`.
2. Publish the corresponding-source bundle generated from
   `third_party/sources.lock.json`.
3. Preserve license/NOTICE files for native libraries, shaders, Gradle
   dependencies, and transitive APK contents.
4. Preserve Google ML Kit notices and the user disclosure in
   `PRIVACY.md`.
5. Regenerate the SBOM from the exact final locks.
6. Publish the source commit/tree, APK hash, signing-certificate fingerprint,
   SBOM, notices, source bundle, and lock files together.
