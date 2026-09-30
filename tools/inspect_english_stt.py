#!/usr/bin/env python3
"""Inspect the English-only Conformer CTC STT model bundled with iTantra."""

from __future__ import annotations

import argparse
import json
import zipfile
from pathlib import Path


DEFAULT_ZIP = Path("app/src/main/assets/models/english_stt.zip")


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
        files = [
            name for name in archive.namelist()
            if not name.endswith("/")
        ]

        print("ZIP:", args.zip_path)
        print("Size: %.2f MiB" % (args.zip_path.stat().st_size / (1024 * 1024)))
        print("Entries:", len(files))
        print()

        print("ONNX models:")
        for name in files:
            if name.lower().endswith(".onnx"):
                info = archive.getinfo(name)
                print(
                    f"  {name}  "
                    f"({info.file_size / (1024 * 1024):.2f} MiB)"
                )

        print()
        print("Vocabulary:")
        vocab = next(
            (name for name in files if Path(name).name == "vocab.txt"),
            None,
        )
        if vocab:
            lines = archive.read(vocab).decode(
                "utf-8", errors="replace"
            ).splitlines()
            print("  File:", vocab)
            print("  Entries:", len(lines))
            print("  First 20 tokens:", lines[:20])
        else:
            print("  vocab.txt not found")

        print()
        print("Config:")
        config_name = next(
            (name for name in files if Path(name).name == "config.json"),
            None,
        )
        if config_name:
            config = json.loads(
                archive.read(config_name).decode("utf-8")
            )
            print(json.dumps(config, indent=2))
        else:
            print("  config.json not found")

        print()
        print("Architecture: NeMo Conformer CTC")
        print("Language: English")
        print("Expected runtime: onnx-asr")
        print("INSPECTION COMPLETE")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
