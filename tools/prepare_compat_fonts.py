#!/usr/bin/env python3

import argparse
import hashlib
from pathlib import Path

try:
    from fontTools.ttLib import TTFont
except ImportError as error:
    raise RuntimeError(
        "fonttools 4.62.1 is required; install requirements-build.txt"
    ) from error


FONTS = (
    (
        "VL-Gothic-Regular.ttf",
        "MS-Gothic-Compatible.ttf",
        "MS Gothic",
        "\uff2d\uff33 \u30b4\u30b7\u30c3\u30af",
        "MSGothic-Compatible",
        "e2312f8407796bc2ad8a27081818c7367931e0efb432cc6fe850a48d21aa07d4",
    ),
    (
        "VL-PGothic-Regular.ttf",
        "MS-PGothic-Compatible.ttf",
        "MS PGothic",
        "\uff2d\uff33 \uff30\u30b4\u30b7\u30c3\u30af",
        "MSPGothic-Compatible",
        "c424dbd14e10330b0aa1f68573786455558ded773eb334ff66ff7219f3173637",
    ),
    (
        "VL-PGothic-Regular.ttf",
        "MS-UIGothic-Compatible.ttf",
        "MS UI Gothic",
        "MS UI Gothic",
        "MSUIGothic-Compatible",
        "775bb7cf85176afdc75092b97ea0ae4928b93fce2c9710aa761317085f7a08c1",
    ),
)


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def build_font(source, destination, english_name, japanese_name, postscript_name):
    font = TTFont(source, recalcTimestamp=False)
    names = {
        1: (english_name, japanese_name),
        2: ("Regular", "Regular"),
        3: (
            f"Winlator Secure:{english_name}:2026",
            f"Winlator Secure:{english_name}:2026",
        ),
        4: (english_name, japanese_name),
        6: (postscript_name, postscript_name),
        16: (english_name, japanese_name),
        17: ("Regular", "Regular"),
    }
    for name_id, (english_value, japanese_value) in names.items():
        font["name"].setName(english_value, name_id, 1, 0, 0)
        font["name"].setName(english_value, name_id, 3, 1, 1033)
        font["name"].setName(japanese_value, name_id, 3, 1, 1041)
    destination.parent.mkdir(parents=True, exist_ok=True)
    font.save(destination, reorderTables=False)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()

    for source_name, output_name, english, japanese, postscript, expected in FONTS:
        source = args.input / source_name
        destination = args.output / output_name
        build_font(source, destination, english, japanese, postscript)
        actual = sha256(destination)
        if actual != expected:
            destination.unlink(missing_ok=True)
            raise RuntimeError(
                f"{output_name} has SHA-256 {actual}, expected {expected}"
            )


if __name__ == "__main__":
    main()
