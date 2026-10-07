import argparse
import os
import subprocess
import tempfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
TEST_SOURCE = ROOT / "tools/box64_launcher_test.c"
LAUNCHER_SOURCE = ROOT / "tools/box64_launcher.c"


def find_android_tool(relative_path: str) -> Path:
    android_home = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not android_home:
        raise RuntimeError("ANDROID_HOME or ANDROID_SDK_ROOT is required")
    tool = Path(android_home) / relative_path
    if not tool.is_file():
        raise RuntimeError(f"Required Android tool is missing: {tool}")
    return tool


def find_compiler() -> Path:
    android_home = Path(
        os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or ""
    )
    ndk_root = android_home / "ndk"
    for ndk in sorted(
        (path for path in ndk_root.iterdir() if path.is_dir()),
        reverse=True,
    ):
        compiler = (
            ndk
            / "toolchains/llvm/prebuilt/windows-x86_64/bin"
            / "x86_64-linux-android26-clang.cmd"
        )
        if compiler.is_file():
            return compiler
    raise RuntimeError(f"No Android NDK compiler found under {ndk_root}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--adb",
        action="store_true",
        help="Push and run the x86_64 test binary on the connected Android device.",
    )
    arguments = parser.parse_args()

    with tempfile.TemporaryDirectory() as directory:
        compiler = find_compiler()
        binary = Path(directory) / "box64_launcher_test"
        launcher = Path(directory) / "box64_launcher_smoke"
        common_arguments = [
            str(compiler),
            "-O2",
            "-Wall",
            "-Wextra",
            "-Werror",
            "-fPIE",
            "-pie",
        ]
        subprocess.run(
            common_arguments + [str(TEST_SOURCE), "-o", str(binary)],
            check=True,
        )
        subprocess.run(
            common_arguments + [str(LAUNCHER_SOURCE), "-o", str(launcher)],
            check=True,
        )
        print(f"Compiled {binary} and {launcher}")
        if not arguments.adb:
            return

        adb = find_android_tool("platform-tools/adb.exe")
        destination = "/data/local/tmp/box64_launcher_test"
        launcher_destination = "/data/local/tmp/box64_launcher_smoke"
        loader_destination = "/data/local/tmp/libglibcloader.so"
        loader = Path(directory) / "libglibcloader.so"
        loader.write_text(
            "#!/system/bin/sh\n"
            "printf 'fake-loader argv:'\n"
            "printf ' <%s>' \"$@\"\n"
            "printf '\\n'\n",
            encoding="utf-8",
            newline="\n",
        )
        subprocess.run([str(adb), "push", str(binary), destination], check=True)
        subprocess.run(
            [str(adb), "push", str(launcher), launcher_destination],
            check=True,
        )
        subprocess.run([str(adb), "push", str(loader), loader_destination], check=True)
        subprocess.run(
            [
                str(adb),
                "shell",
                "chmod",
                "755",
                destination,
                launcher_destination,
                loader_destination,
            ],
            check=True,
        )
        subprocess.run([str(adb), "shell", destination], check=True)
        smoke = subprocess.run(
            [
                str(adb),
                "shell",
                "env",
                "WINLATOR_ROOTFS=/data/local/tmp",
                launcher_destination,
                "wine",
                "explorer",
                "C:/games/smoke-test.exe",
            ],
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
        )
        print(smoke.stdout, end="")
        if smoke.returncode != 0:
            raise RuntimeError(
                f"Launcher smoke test returned {smoke.returncode}, expected 0."
            )
        if "box64-launcher: created session" not in smoke.stdout and (
            "launcher already owns its process group" not in smoke.stdout
        ):
            raise RuntimeError("Launcher did not establish or retain process-group ownership.")
        if "setsid failed:" in smoke.stdout:
            raise RuntimeError("Launcher treated setsid as a fatal error.")
        expected_arguments = (
            "<wine>",
            "<explorer>",
            "<C:/games/smoke-test.exe>",
        )
        if not all(argument in smoke.stdout for argument in expected_arguments):
            raise RuntimeError(
                "Launcher did not forward Wine explorer and game executable arguments."
            )
        subprocess.run(
            [
                str(adb),
                "shell",
                "rm",
                "-f",
                destination,
                launcher_destination,
                loader_destination,
            ],
            check=True,
        )


if __name__ == "__main__":
    main()
