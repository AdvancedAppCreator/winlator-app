import argparse
import hashlib
import shutil
import subprocess
from pathlib import Path

from box64_build_config import ARCHIVE, LOADER_OUTPUT, OUTPUT, ROOT


ROOTFS_SIZE = 65251198
ROOTFS_SHA256 = (
    "8b5110f248e84f2aee4df37dab8bac4c4bf2bdc7b400c0643a0778ca8e7e40c2"
)

REPLACEMENTS = (
    (
        b"/data/data/com.winlator/files/rootfs/lib/ld-linux-aarch64.so.1",
        b"/proc/self/cwd/lib/ld-linux-aarch64.so.1",
    ),
    (
        b"/data/data/com.winlator/files/rootfs/lib",
        b"/proc/self/cwd/lib",
    ),
)


def replace_fixed(data: bytes, source: bytes, destination: bytes) -> tuple[bytes, bool]:
    count = data.count(source)
    if count == 0:
        return data, False
    if count != 1:
        raise RuntimeError(f"Expected one occurrence of {source!r}, found {count}")
    if len(destination) > len(source):
        raise RuntimeError(f"Replacement is longer than source: {destination!r}")
    replacement = destination + (b"\0" * (len(source) - len(destination)))
    return data.replace(source, replacement, 1), True


def load_upstream_rootfs(inputs: Path) -> bytes:
    path = inputs / "rootfs.tzst"
    if not path.is_file():
        raise RuntimeError(f"Verified upstream rootfs input is missing: {path}")
    rootfs = path.read_bytes()
    if len(rootfs) != ROOTFS_SIZE:
        raise RuntimeError("Pinned upstream rootfs size mismatch")
    if hashlib.sha256(rootfs).hexdigest() != ROOTFS_SHA256:
        raise RuntimeError("Pinned upstream rootfs SHA-256 mismatch")
    return rootfs


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--inputs", required=True, type=Path)
    args = parser.parse_args()
    data = subprocess.run(
        ["tar", "--zstd", "-xOf", str(ARCHIVE), "usr/local/bin/box64"],
        check=True,
        stdout=subprocess.PIPE,
    ).stdout

    substitutions = 0
    for old, new in REPLACEMENTS:
        data, replaced = replace_fixed(data, old, new)
        substitutions += replaced

    if b"/data/data/com.winlator" in data:
        raise RuntimeError("Owner-profile Winlator path remains in Box64")

    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    temporary = OUTPUT.with_suffix(".tmp")
    temporary.write_bytes(data)
    if OUTPUT.is_file() and OUTPUT.read_bytes() == data:
        temporary.unlink()
    else:
        shutil.move(temporary, OUTPUT)

    digest = hashlib.sha256(data).hexdigest()
    print(f"Wrote {OUTPUT} ({len(data)} bytes, sha256={digest}, profile-path substitutions={substitutions})")

    loader = subprocess.run(
        [
            "tar",
            "--zstd",
            "-xOf",
            "-",
            "./usr/lib/ld-linux-aarch64.so.1",
        ],
        check=True,
        input=load_upstream_rootfs(args.inputs.resolve()),
        stdout=subprocess.PIPE,
    ).stdout
    LOADER_OUTPUT.write_bytes(loader)
    loader_digest = hashlib.sha256(loader).hexdigest()
    print(f"Wrote {LOADER_OUTPUT} ({len(loader)} bytes, sha256={loader_digest})")


if __name__ == "__main__":
    main()
