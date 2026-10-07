# Source availability

This repository contains the application source represented by its Git tree.
`PROVENANCE.md` identifies the upstream baseline and summarizes modifications.
Every official release must include the repository source archive and the
corresponding-source bundle generated from `third_party/sources.lock.json`.
The lock records immutable URLs, sizes, and SHA-256 values for the source and
build recipes corresponding to packaged third-party binaries.

The repository and base APK do not contain upstream `rootfs.tzst` or
`container_pattern.tzst` or `rootfs_patches.tzst`. The app imports those files on first run from a
size- and hash-pinned official upstream APK after user consent. This changes
the redistribution boundary but is not a claim that downloading or installing
the runtime eliminates applicable notice, source, or relinking obligations.

Generate the accompanying source with:

```text
python tools/prepare_source_bundle.py --lock third_party/sources.lock.json --output release/source-components --offline
```

Publish that directory as an archive alongside the APK. Do not substitute a
link-only offer.

Recipients may build a modified APK and sign it with their own Android key; no
project release key is needed. No future support or source-request service is
promised: source accompanies each binary release at publication time.
