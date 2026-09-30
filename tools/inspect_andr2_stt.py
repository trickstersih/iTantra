#!/usr/bin/env python3
"""Inspect app/src/main/assets/models/andr2.zip before running STT tests."""

from __future__ import annotations

import argparse
import zipfile
from pathlib import Path


DEFAULT_ZIP = Path("app/src/main/assets/models/andr2.zip")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "zip_path",
        nargs="?",
        type=Path,
        default=DEFAULT_ZIP,
        help=f"Model ZIP (default: {DEFAULT_ZIP})",
    )
    args = parser.parse_args()

    if not args.zip_path.is_file():
        raise FileNotFoundError(args.zip_path)

    with zipfile.ZipFile(args.zip_path) as archive:
        entries = [
            name.replace("\\\\", "/")
            for name in archive.namelist()
            if not name.endswith("/")
        ]

    print("ZIP:", args.zip_path)
    print("Size: %.2f MiB" % (args.zip_path.stat().st_size / (1024 * 1024)))
    print("Entries:", len(entries))
    print()

    print("ONNX files:")
    onnx = [x for x in entries if x.lower().endswith(".onnx")]
    for name in onnx:
        print("  ", name)
    if not onnx:
        print("   <none>")

    print()
    print("Tokenizer / vocabulary files:")
    vocab_names = [
        x for x in entries
        if any(
            marker in Path(x).name.lower()
            for marker in (
                "tokens",
                "vocab",
                "tokenizer",
                "sentencepiece",
                "spiece",
            )
        )
    ]
    for name in vocab_names:
        print("  ", name)
    if not vocab_names:
        print("   <none>")

    print()
    print("Archive entries:")
    for name in entries:
        print("  ", name)

    print()
    if any("encoder" in Path(x).name.lower() for x in onnx) and any(
        "decoder" in Path(x).name.lower() for x in onnx
    ):
        print("Detected architecture: split encoder/decoder Whisper-style ONNX")
    elif len(onnx) == 1:
        print("Detected architecture: single ONNX model")
    elif onnx:
        print("Detected architecture: multiple ONNX files; test runner needs inspection")
    else:
        print("Detected architecture: no ONNX files")

    print("INSPECTION COMPLETE")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
