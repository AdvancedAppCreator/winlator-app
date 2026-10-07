import argparse
import hashlib
import json
import os
import shutil
import stat
import subprocess
import tarfile
import zipfile
from pathlib import Path

from box64_build_config import (
    ARCHIVE,
    OUTPUT,
    PACKAGE_VERSION,
    PATCH,
    PROVENANCE,
    RETIRED_ARCHIVES,
    ROOT,
    UPSTREAM_REVISION,
    UPSTREAM_TAG,
    UPSTREAM_URL,
    TOOLCHAIN_SHA256,
    TOOLCHAIN_URL,
)
from build_box64_launcher import main as build_launcher


WORK_DIR = ROOT / ".box64-build"
SOURCE_DIR = WORK_DIR / "source"
BUILD_DIR = WORK_DIR / "build"
STAGING_DIR = WORK_DIR / "staging"


def run(command: list[str], cwd: Path | None = None) -> None:
    subprocess.run(command, cwd=cwd, check=True)


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def remove_tree(path: Path) -> None:
    def make_writable(function, failed_path, exception) -> None:
        os.chmod(failed_path, stat.S_IWRITE)
        function(failed_path)

    if path.exists():
        shutil.rmtree(path, onerror=make_writable)
    if path.exists():
        raise RuntimeError(f"Unable to remove stale build directory: {path}")


def find_sdk_tool(android_home: Path, package: str, name: str) -> Path:
    tool = android_home / package / name
    if not tool.is_file():
        raise RuntimeError(f"Required Android SDK tool is missing: {tool}")
    return tool


def extract_zip_safely(archive: Path, destination: Path) -> None:
    with zipfile.ZipFile(archive) as zip_file:
        root = destination.resolve()
        for member in zip_file.infolist():
            target = (destination / member.filename).resolve()
            if root != target and root not in target.parents:
                raise RuntimeError(f"Archive entry escapes extraction directory: {member.filename}")
        zip_file.extractall(destination)


def prepare_toolchain(inputs: Path) -> Path:
    archive = inputs / "arm-gnu-toolchain.zip"
    extraction_directory = WORK_DIR / "toolchain"
    compiler = extraction_directory / "bin/aarch64-none-linux-gnu-gcc.exe"
    if compiler.is_file():
        return compiler
    if not archive.is_file():
        raise RuntimeError(f"Verified toolchain input is missing: {archive}")
    if sha256(archive) != TOOLCHAIN_SHA256:
        raise RuntimeError(f"Toolchain checksum does not match {TOOLCHAIN_SHA256}")
    remove_tree(extraction_directory)
    extraction_directory.mkdir(parents=True, exist_ok=True)
    extract_zip_safely(archive, extraction_directory)
    if not compiler.is_file():
        raise RuntimeError(f"Toolchain did not provide {compiler}")
    return compiler


def checkout_source(inputs: Path) -> None:
    archive = inputs / "box64-e2345d73.zip"
    if not archive.is_file():
        raise RuntimeError(f"Verified Box64 source input is missing: {archive}")
    remove_tree(SOURCE_DIR)
    extraction_root = WORK_DIR / "source-extract"
    remove_tree(extraction_root)
    extraction_root.mkdir(parents=True)
    extract_zip_safely(archive, extraction_root)
    roots = [item for item in extraction_root.iterdir() if item.is_dir()]
    if len(roots) != 1:
        raise RuntimeError("Pinned Box64 source archive has an unexpected layout")
    shutil.move(str(roots[0]), SOURCE_DIR)
    remove_tree(extraction_root)
    normalized_patch = WORK_DIR / "box64.patch"
    normalized_patch.write_bytes(PATCH.read_bytes().replace(b"\r\n", b"\n"))
    run(["git", "apply", "--check", str(normalized_patch)], SOURCE_DIR)
    run(["git", "apply", str(normalized_patch)], SOURCE_DIR)


def build(inputs: Path) -> Path:
    android_home_value = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not android_home_value:
        raise RuntimeError("ANDROID_HOME or ANDROID_SDK_ROOT is required")
    android_home = Path(android_home_value)
    cmake = find_sdk_tool(android_home, "cmake/3.22.1/bin", "cmake.exe")
    compiler = prepare_toolchain(inputs)
    git = shutil.which("git")
    if not git:
        raise RuntimeError("git is required to build the pinned Box64 source")
    git_shell = Path(git).parents[1] / "bin/sh.exe"
    if not git_shell.is_file():
        raise RuntimeError(f"Git shell is required by Box64's CMake build: {git_shell}")

    remove_tree(BUILD_DIR)
    build_environment = os.environ.copy()
    build_environment["SOURCE_DATE_EPOCH"] = "0"
    build_environment["PATH"] = (
        str(cmake.parent)
        + os.pathsep
        + str(git_shell.parent)
        + os.pathsep
        + build_environment.get("PATH", "")
    )
    subprocess.run(
        [
            str(cmake),
            "-S",
            str(SOURCE_DIR),
            "-B",
            str(BUILD_DIR),
            "-G",
            "Ninja",
            "-DCMAKE_SYSTEM_NAME=Linux",
            "-DCMAKE_SYSTEM_PROCESSOR=aarch64",
            f"-DCMAKE_C_COMPILER={compiler.as_posix()}",
            f"-DCMAKE_ASM_COMPILER={compiler.as_posix()}",
            "-DCMAKE_BUILD_TYPE=Release",
            (
                "-DCMAKE_C_FLAGS=-DWINLATOR_ANDROID_EGL_FALLBACK "
                f"-ffile-prefix-map={SOURCE_DIR.as_posix()}=/usr/src/box64 "
                f"-fdebug-prefix-map={SOURCE_DIR.as_posix()}=/usr/src/box64 "
                f"-ffile-prefix-map={BUILD_DIR.as_posix()}=/usr/build/box64 "
                f"-fdebug-prefix-map={BUILD_DIR.as_posix()}=/usr/build/box64"
            ),
            "-DWINLATOR_GLIBC=ON",
            "-DARM64=ON",
            "-DNO_CONF_INSTALL=ON",
            "-DNO_LIB_INSTALL=ON",
        ],
        check=True,
        env=build_environment,
    )
    (SOURCE_DIR / "src/git_head.h").write_text(
        f'#define GITREV "{UPSTREAM_REVISION[:7]}"\n',
        encoding="ascii",
    )
    subprocess.run([str(cmake), "--build", str(BUILD_DIR), "--target", "box64"], check=True, env=build_environment)
    binary = BUILD_DIR / "box64"
    if not binary.is_file():
        raise RuntimeError(f"Box64 build did not produce {binary}")
    return binary


def create_archive(binary: Path) -> None:
    archive_tar = WORK_DIR / "box64.tar"
    archive_tar.parent.mkdir(parents=True, exist_ok=True)
    with tarfile.open(archive_tar, "w", format=tarfile.GNU_FORMAT) as archive:
        info = tarfile.TarInfo("usr/local/bin/box64")
        info.size = binary.stat().st_size
        info.mode = 0o755
        info.uid = 0
        info.gid = 0
        info.uname = ""
        info.gname = ""
        info.mtime = 0
        with binary.open("rb") as source:
            archive.addfile(info, source)

    ARCHIVE.parent.mkdir(parents=True, exist_ok=True)
    run(["zstd", "--no-progress", "--threads=1", "-19", "-f", str(archive_tar), "-o", str(ARCHIVE)])


def write_provenance(binary: Path) -> None:
    patch_digest = hashlib.sha256(
        PATCH.read_bytes().replace(b"\r\n", b"\n")
    ).hexdigest()
    build_provenance = {
        "archive_sha256": sha256(ARCHIVE),
        "box64_sha256": sha256(binary),
        "package_version": PACKAGE_VERSION,
        "patch_sha256": patch_digest,
        "toolchain_sha256": TOOLCHAIN_SHA256,
        "toolchain_url": TOOLCHAIN_URL,
        "upstream_revision": UPSTREAM_REVISION,
        "upstream_tag": UPSTREAM_TAG,
        "upstream_url": UPSTREAM_URL,
    }
    expected = json.loads(PROVENANCE.read_text(encoding="utf-8"))
    expected_inputs = {
        key: value
        for key, value in expected.items()
        if not key.startswith("reference_")
    }
    actual_inputs = {
        key: value
        for key, value in build_provenance.items()
        if key not in {"archive_sha256", "box64_sha256"}
    }
    if actual_inputs != expected_inputs:
        raise RuntimeError(
            "Box64 inputs do not match the checked-in provenance: "
            f"actual={json.dumps(actual_inputs, sort_keys=True)}"
        )
    print(
        "Box64 build provenance: "
        f"{json.dumps(build_provenance, sort_keys=True)}"
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--inputs", required=True, type=Path)
    args = parser.parse_args()
    inputs = args.inputs.resolve()
    checkout_source(inputs)
    binary = build(inputs)
    create_archive(binary)
    write_provenance(binary)
    run(
        [
            os.environ.get("WINLATOR_PYTHON", "python"),
            str(ROOT / "tools/package_box64.py"),
            "--inputs",
            str(inputs),
        ],
        ROOT,
    )
    build_launcher()
    if sha256(binary) != sha256(OUTPUT):
        raise RuntimeError("Packaged libbox64.so does not match the Box64 build output")
    for retired_archive in RETIRED_ARCHIVES:
        retired_archive.unlink(missing_ok=True)
    print(f"Built Box64 {PACKAGE_VERSION} from {UPSTREAM_REVISION}")
    print(f"Wrote {ARCHIVE} (sha256={sha256(ARCHIVE)})")
    print(f"Wrote {OUTPUT} (sha256={sha256(OUTPUT)})")


if __name__ == "__main__":
    main()
