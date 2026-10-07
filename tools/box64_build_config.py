from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
GENERATED_ROOT = ROOT / "app/build/generated/builtThirdParty"
UPSTREAM_URL = "https://github.com/ptitSeb/box64.git"
UPSTREAM_REVISION = "e2345d735af36f7915ab861a55b489d69f2350c7"
UPSTREAM_TAG = "v0.4.3-3"
PACKAGE_VERSION = "0.4.3-3"
TOOLCHAIN_URL = (
    "https://armkeil.blob.core.windows.net/developer/Files/downloads/gnu/14.2.rel1/binrel/"
    "arm-gnu-toolchain-14.2.rel1-mingw-w64-i686-aarch64-none-linux-gnu.zip"
)
TOOLCHAIN_SHA256 = "bd5f4808995af2ec647bd0fe8f62815e2c65abcf0558f38f183188d05328d0a0"

PATCH = ROOT / "tools/patches/box64-android-egl-fallback.patch"
ARCHIVE = GENERATED_ROOT / f"assets/box64/box64-{PACKAGE_VERSION}.tzst"
PROVENANCE = ROOT / f"app/src/main/assets/box64/box64-{PACKAGE_VERSION}.provenance.json"
OUTPUT = GENERATED_ROOT / "jniLibs/arm64-v8a/libbox64.so"
LOADER_OUTPUT = GENERATED_ROOT / "jniLibs/arm64-v8a/libglibcloader.so"
RETIRED_ARCHIVES = (
    ROOT / "app/src/main/assets/box64/box64-0.4.0.tzst",
)
