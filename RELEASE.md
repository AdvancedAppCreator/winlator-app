# Release procedure

1. Start from a clean public source tree and record its commit ID. Confirm
   `PROVENANCE.md`, notices, source availability, dependency verification
   metadata, locks, and SBOM match that tree.
2. Populate the dependency cache and build with `--offline
   -PofflineThirdParty`. Confirm
   `rootfs.tzst`, `container_pattern.tzst`, and `rootfs_patches.tzst` are absent
   from the built base APK. Run `gradlew.bat testDebugUnitTest` and
   `gradlew.bat assembleRelease`. Test first-run provisioning against the
   pinned official GitHub release, including cancellation and a clean retry.
3. Generate `release/source-components` with
   `tools/prepare_source_bundle.py --offline`, archive it, and verify every file
   against `third_party/sources.lock.json` with
   `tools/prepare_source_bundle.py --verify-only`. Use the same audited source
   tree for the repository source archive; do not archive a different checkout.
4. Sign the APK outside the repository with the release key, for example:

   ```text
   apksigner sign --ks C:\external\release.jks --out WinlatorSecure.apk app\build\outputs\apk\release\app-release-unsigned.apk
   apksigner verify --verbose --print-certs WinlatorSecure.apk
   ```

   Never copy signing material into the repository.
5. Produce SHA-256 checksums for the signed APK, source archive,
   corresponding-source archive,
   `SBOM.cdx.json`, and `THIRD_PARTY_NOTICES.md`:

   ```text
   Get-FileHash -Algorithm SHA256 WinlatorSecure.apk
   ```

6. Sign the checksum file with a separately managed release-signing key
   (for example, a detached OpenPGP or minisign signature). Verify that
   signature before publication.
7. Publish together: signed APK, checksum file and detached signature, source
   archive for the exact source commit, SBOM, notices, provenance statement,
   Android signing-certificate SHA-256 fingerprint, and applicable
   corresponding-source materials.

Do not publish if either lock fails verification, if any generated dependency
uses an unverified fallback, or if the source bundle is missing.
