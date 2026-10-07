# Public build

## Prerequisites

- JDK 11
- Android SDK platform 34 and build tools
- Android NDK `24.0.8215888`
- Android NDK `27.2.12479018` for FluidSynth's matching C++ runtime
- CMake 3.22.1
- Python 3
- Python packages from `requirements-build.txt`

Set `ANDROID_HOME` (or create an untracked `local.properties` containing
`sdk.dir=...`). Build without release credentials:

```text
gradlew.bat clean assembleDebug
gradlew.bat assembleRelease
```

Install the hash-pinned Python build dependency with
`python -m pip install --require-hashes -r requirements-build.txt`. For an
offline build, download it to a trusted cache first and add `--no-index
--find-links <cache>`.

The build downloads only artifacts pinned by URL, byte count, and SHA-256 in
`third_party/dependencies.lock.json`. Selected entries are extracted into
`app/build/generated/thirdParty` and verified again before Gradle packages
them. Set `WINLATOR_PYTHON` when `python` is not on `PATH`. After the cache has
been populated, pass `--offline -PofflineThirdParty` to require a network-free,
fail-closed Gradle and third-party build. This also covers the pinned Box64
source, compiler toolchain, and upstream rootfs used to build its loader.
`WINLATOR_DEPENDENCY_CACHE` may point to an external
dependency cache; the default is `~/.cache/winlator-secure`.

Gradle's wrapper distribution is SHA-256 pinned, dependency locking fixes the
resolved module graph, and strict dependency-verification metadata authenticates
downloaded Gradle artifacts. Regenerate `SBOM.cdx.json` only after refreshing
`app/gradle.lockfile`; the generator consumes that resolved graph.

`assembleRelease` produces an unsigned APK unless signing is explicitly
enabled. Signing credentials are never part of this repository. To sign during
the Gradle build, set
`WINLATOR_SIGNING_PROPERTIES` to an external properties file:

```text
storeFile=C:\external\release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

Do not commit that file, the keystore, generated APKs, or passwords. An
operator may instead sign the unsigned artifact externally with Android SDK
`apksigner`. See `RELEASE.md`.

Optional deployment-specific Gradle properties, including
`curatedDriverFeedUrl`, must be supplied out of tree. A curated feed is usable
only when every requested path also has pinned size and SHA-256 metadata in
`app/src/main/assets/download-integrity.json`; otherwise download fails closed.

The base APK no longer bundles upstream `rootfs.tzst`,
`container_pattern.tzst`, or `rootfs_patches.tzst`. On first run, with explicit user consent, it downloads
only the pinned official Winlator 11.1 APK from its canonical GitHub release,
verifies the APK's exact size and SHA-256, and atomically imports those three
verified entries into app-private storage. This requires roughly 250 MB of
temporary private storage in addition to the eventual installed runtime.

Third-party JNI libraries, PulseAudio, graphics drivers, wrappers, fonts,
soundfont, wallpapers, and input profiles are downloaded or generated from
the pinned lock inputs rather than tracked as source-tree blobs. Box64 is
built from its pinned source revision, patch, and compiler archive.
