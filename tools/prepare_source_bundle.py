#!/usr/bin/env python3

import argparse
import hashlib
import json
import os
import shutil
import urllib.request
import uuid
from pathlib import Path


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def verify_bundle(lock, output):
    expected = {"sources.lock.json"}
    for name, _url, size, expected_sha256 in lock["sources"]:
        if Path(name).name != name:
            raise RuntimeError(f"Unsafe source archive name: {name}")
        if name in expected:
            raise RuntimeError(f"Duplicate source archive name: {name}")
        expected.add(name)
        path = output / name
        if not path.is_file() or path.stat().st_size != size or sha256(path) != expected_sha256:
            raise RuntimeError(f"Source bundle verification failed: {name}")
    actual = {path.name for path in output.iterdir() if path.is_file()}
    if actual != expected:
        raise RuntimeError(
            f"Source bundle file set mismatch: extra={sorted(actual - expected)}, "
            f"missing={sorted(expected - actual)}"
        )


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--lock", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--cache", type=Path)
    parser.add_argument("--offline", action="store_true")
    parser.add_argument("--verify-only", action="store_true")
    args = parser.parse_args()

    lock = json.loads(args.lock.read_text(encoding="utf-8"))
    if lock.get("schemaVersion") != 1:
        raise RuntimeError("Unsupported source lock schema")
    if args.verify_only:
        verify_bundle(lock, args.output)
        print(f"Verified {len(lock['sources'])} source archives in {args.output}")
        return
    cache = args.cache or Path.home() / ".cache" / "winlator-secure-sources"
    cache.mkdir(parents=True, exist_ok=True)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    staging = args.output.parent / f".source-components-{uuid.uuid4().hex}"
    staging.mkdir()

    try:
        for name, url, size, expected_sha256 in lock["sources"]:
            if Path(name).name != name:
                raise RuntimeError(f"Unsafe source archive name: {name}")
            cached = cache / name
            if not cached.is_file() or cached.stat().st_size != size or sha256(cached) != expected_sha256:
                cached.unlink(missing_ok=True)
                if args.offline:
                    raise RuntimeError(f"Verified source archive is unavailable: {name}")
                temporary = cached.with_suffix(cached.suffix + ".part")
                request = urllib.request.Request(
                    url,
                    headers={"User-Agent": "WinlatorSecure-release/1"},
                )
                try:
                    with urllib.request.urlopen(request, timeout=180) as response:
                        if not response.geturl().startswith("https://"):
                            raise RuntimeError(f"Source redirected outside HTTPS: {name}")
                        with temporary.open("wb") as output:
                            shutil.copyfileobj(response, output, length=1024 * 1024)
                    if temporary.stat().st_size != size or sha256(temporary) != expected_sha256:
                        raise RuntimeError(f"Source archive integrity mismatch: {name}")
                    temporary.replace(cached)
                finally:
                    temporary.unlink(missing_ok=True)
            shutil.copyfile(cached, staging / name)

        shutil.copyfile(args.lock, staging / "sources.lock.json")
        verify_bundle(lock, staging)
        if args.output.exists():
            shutil.rmtree(args.output)
        os.replace(staging, args.output)
    except Exception:
        shutil.rmtree(staging, ignore_errors=True)
        raise


if __name__ == "__main__":
    main()
