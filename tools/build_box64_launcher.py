import hashlib
import os
import subprocess
from pathlib import Path

from box64_build_config import GENERATED_ROOT

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "tools/box64_launcher.c"
OUTPUT = GENERATED_ROOT / "jniLibs/arm64-v8a/libbox64launcher.so"
NDK_VERSION = "24.0.8215888"


def find_compiler() -> Path:
    android_home = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not android_home:
        raise RuntimeError("ANDROID_HOME or ANDROID_SDK_ROOT is required")

    compiler = (
        Path(android_home)
        / "ndk"
        / NDK_VERSION
        / "toolchains/llvm/prebuilt/windows-x86_64/bin"
        / "aarch64-linux-android26-clang.cmd"
    )
    if compiler.is_file():
        return compiler
    raise RuntimeError(f"Required Android NDK compiler is missing: {compiler}")


def main() -> None:
    compiler = find_compiler()
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run(
        [
            str(compiler),
            "-O2",
            "-Wall",
            "-Wextra",
            "-Werror",
            "-fPIE",
            "-pie",
            str(SOURCE),
            "-o",
            str(OUTPUT),
        ],
        check=True,
    )
    data = OUTPUT.read_bytes()
    print(f"Wrote {OUTPUT} ({len(data)} bytes, sha256={hashlib.sha256(data).hexdigest()})")


if __name__ == "__main__":
    main()
