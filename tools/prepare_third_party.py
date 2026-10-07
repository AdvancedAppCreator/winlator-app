#!/usr/bin/env python3

import argparse
import bz2
import gzip
import hashlib
import io
import json
import lzma
import os
from pathlib import Path, PurePosixPath
import shutil
import subprocess
import tarfile
import tempfile
import urllib.request
import zipfile


def sha256_file(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def verify_file(path, expected_size, expected_sha256, description):
    actual_size = path.stat().st_size
    if actual_size != expected_size:
        raise RuntimeError(
            f"{description} has size {actual_size}, expected {expected_size}"
        )
    actual_sha256 = sha256_file(path)
    if actual_sha256.lower() != expected_sha256.lower():
        raise RuntimeError(
            f"{description} has SHA-256 {actual_sha256}, expected {expected_sha256}"
        )


def safe_destination(root, relative):
    parsed = PurePosixPath(relative)
    if parsed.is_absolute() or ".." in parsed.parts or not parsed.parts:
        raise RuntimeError(f"Unsafe dependency destination: {relative}")
    destination = root.joinpath(*parsed.parts)
    resolved_root = root.resolve()
    resolved_destination = destination.resolve()
    if resolved_root != resolved_destination and resolved_root not in resolved_destination.parents:
        raise RuntimeError(f"Dependency destination escapes output directory: {relative}")
    return destination


def download(artifact, cache_path, offline):
    if cache_path.is_file():
        try:
            verify_file(
                cache_path,
                artifact["size"],
                artifact["sha256"],
                artifact["id"],
            )
            return
        except RuntimeError:
            cache_path.unlink()
    if offline:
        raise RuntimeError(f"Verified cache entry is unavailable for {artifact['id']}")
    cache_path.parent.mkdir(parents=True, exist_ok=True)
    temporary = cache_path.with_suffix(cache_path.suffix + ".part")
    temporary.unlink(missing_ok=True)
    request = urllib.request.Request(
        artifact["url"],
        headers={"User-Agent": "WinlatorSecure-build/1"},
    )
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            final_url = response.geturl()
            if not final_url.startswith("https://"):
                raise RuntimeError(f"Dependency redirected outside HTTPS: {final_url}")
            with temporary.open("wb") as output:
                shutil.copyfileobj(response, output, length=1024 * 1024)
        verify_file(
            temporary,
            artifact["size"],
            artifact["sha256"],
            artifact["id"],
        )
        os.replace(temporary, cache_path)
    finally:
        temporary.unlink(missing_ok=True)


def read_deb_data(path):
    data = path.read_bytes()
    if not data.startswith(b"!<arch>\n"):
        raise RuntimeError(f"Invalid Debian package: {path}")
    position = 8
    members = {}
    while position < len(data):
        header = data[position:position + 60]
        if len(header) != 60 or header[58:60] != b"`\n":
            raise RuntimeError(f"Invalid ar member in {path}")
        name = header[:16].decode("ascii").strip().rstrip("/")
        size = int(header[48:58].decode("ascii").strip())
        start = position + 60
        members[name] = data[start:start + size]
        position = start + size + (size % 2)
    names = [name for name in members if name.startswith("data.tar")]
    if len(names) != 1:
        raise RuntimeError(f"Expected one data archive in {path}, found {names}")
    name = names[0]
    payload = members[name]
    if name.endswith(".zst"):
        result = subprocess.run(
            ["zstd", "--decompress", "--stdout"],
            input=payload,
            stdout=subprocess.PIPE,
            check=True,
        )
        return result.stdout
    if name.endswith(".xz"):
        return lzma.decompress(payload)
    if name.endswith(".gz"):
        return gzip.decompress(payload)
    if name.endswith(".bz2"):
        return bz2.decompress(payload)
    if name == "data.tar":
        return payload
    raise RuntimeError(f"Unsupported Debian data archive: {name}")


def extract_entries(archive, artifact, staging, seen_destinations):
    archive_names = {member.name for member in archive.getmembers()}
    for entry in artifact["entries"]:
        archive_path = entry["archivePath"]
        if archive_path not in archive_names:
            raise RuntimeError(
                f"{artifact['id']} does not contain {archive_path}"
            )
        destination_name = entry["destination"]
        if destination_name in seen_destinations:
            raise RuntimeError(
                f"Duplicate dependency destination: {destination_name}"
            )
        seen_destinations.add(destination_name)
        destination = safe_destination(staging, destination_name)
        destination.parent.mkdir(parents=True, exist_ok=True)
        source = archive.extractfile(archive_path)
        if source is None:
            raise RuntimeError(f"{archive_path} is not a regular file")
        digest = hashlib.sha256()
        size = 0
        with source, destination.open("wb") as output:
            for chunk in iter(lambda: source.read(1024 * 1024), b""):
                size += len(chunk)
                digest.update(chunk)
                output.write(chunk)
        if size != entry["size"]:
            raise RuntimeError(
                f"{archive_path} has size {size}, expected {entry['size']}"
            )
        actual_sha256 = digest.hexdigest()
        if actual_sha256.lower() != entry["sha256"].lower():
            raise RuntimeError(
                f"{archive_path} has SHA-256 {actual_sha256}, "
                f"expected {entry['sha256']}"
            )


def prepare(lock_path, output_root, cache_root, offline):
    lock = json.loads(lock_path.read_text(encoding="utf-8"))
    if lock.get("schemaVersion") != 1:
        raise RuntimeError("Unsupported dependency lock schema")

    staging = Path(tempfile.mkdtemp(prefix="third-party-", dir=output_root.parent))
    try:
        seen_destinations = set()
        for artifact in lock["artifacts"]:
            artifact_format = artifact.get("format", "zip")
            extensions = {"zip": "zip", "file": "bin", "deb": "deb"}
            if artifact_format not in extensions:
                raise RuntimeError(
                    f"Unsupported dependency format for {artifact['id']}: "
                    f"{artifact_format}"
                )
            cache_path = cache_root / f"{artifact['id']}.{extensions[artifact_format]}"
            download(artifact, cache_path, offline)
            if artifact_format == "file":
                destination_name = artifact["destination"]
                if destination_name in seen_destinations:
                    raise RuntimeError(
                        f"Duplicate dependency destination: {destination_name}"
                    )
                seen_destinations.add(destination_name)
                destination = safe_destination(staging, destination_name)
                destination.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(cache_path, destination)
                continue
            if artifact_format == "deb":
                with tarfile.open(
                    fileobj=io.BytesIO(read_deb_data(cache_path)),
                    mode="r:",
                ) as archive:
                    extract_entries(archive, artifact, staging, seen_destinations)
                continue
            with zipfile.ZipFile(cache_path) as archive:
                archive_names = set(archive.namelist())
                for entry in artifact["entries"]:
                    archive_path = entry["archivePath"]
                    if archive_path not in archive_names:
                        raise RuntimeError(
                            f"{artifact['id']} does not contain {archive_path}"
                        )
                    destination_name = entry["destination"]
                    if destination_name in seen_destinations:
                        raise RuntimeError(
                            f"Duplicate dependency destination: {destination_name}"
                        )
                    seen_destinations.add(destination_name)
                    destination = safe_destination(staging, destination_name)
                    destination.parent.mkdir(parents=True, exist_ok=True)
                    digest = hashlib.sha256()
                    size = 0
                    with archive.open(archive_path) as source, destination.open("wb") as output:
                        for chunk in iter(lambda: source.read(1024 * 1024), b""):
                            size += len(chunk)
                            digest.update(chunk)
                            output.write(chunk)
                    if size != entry["size"]:
                        raise RuntimeError(
                            f"{archive_path} has size {size}, expected {entry['size']}"
                        )
                    actual_sha256 = digest.hexdigest()
                    if actual_sha256.lower() != entry["sha256"].lower():
                        raise RuntimeError(
                            f"{archive_path} has SHA-256 {actual_sha256}, "
                            f"expected {entry['sha256']}"
                        )
                for prefix in artifact.get("prefixes", []):
                    archive_prefix = prefix["archivePrefix"]
                    destination_prefix = prefix["destinationPrefix"]
                    matches = sorted(
                        name
                        for name in archive_names
                        if name.startswith(archive_prefix) and not name.endswith("/")
                    )
                    if len(matches) != prefix["expectedFiles"]:
                        raise RuntimeError(
                            f"{artifact['id']} has {len(matches)} files under "
                            f"{archive_prefix}, expected {prefix['expectedFiles']}"
                        )
                    for archive_path in matches:
                        suffix = archive_path[len(archive_prefix):]
                        destination_name = destination_prefix + suffix
                        if destination_name in seen_destinations:
                            raise RuntimeError(
                                f"Duplicate dependency destination: {destination_name}"
                            )
                        seen_destinations.add(destination_name)
                        destination = safe_destination(staging, destination_name)
                        destination.parent.mkdir(parents=True, exist_ok=True)
                        with archive.open(archive_path) as source, destination.open("wb") as output:
                            shutil.copyfileobj(source, output, length=1024 * 1024)

        stamp = {
            "schemaVersion": lock["schemaVersion"],
            "lockSha256": hashlib.sha256(lock_path.read_bytes()).hexdigest(),
            "files": sorted(seen_destinations),
        }
        (staging / "dependency-lock.json").write_text(
            json.dumps(stamp, indent=2) + "\n",
            encoding="utf-8",
        )
        if output_root.exists():
            shutil.rmtree(output_root)
        os.replace(staging, output_root)
    except Exception:
        shutil.rmtree(staging, ignore_errors=True)
        raise


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--lock", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--cache", type=Path)
    parser.add_argument("--offline", action="store_true")
    args = parser.parse_args()

    cache = args.cache
    if cache is None:
        configured_cache = os.environ.get("WINLATOR_DEPENDENCY_CACHE")
        cache = (
            Path(configured_cache)
            if configured_cache
            else Path.home() / ".cache" / "winlator-secure"
        )
    args.output.parent.mkdir(parents=True, exist_ok=True)
    prepare(args.lock.resolve(), args.output.resolve(), cache.resolve(), args.offline)


if __name__ == "__main__":
    main()
