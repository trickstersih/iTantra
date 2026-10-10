#!/usr/bin/env python3
"""Print config.json and token sidecars for the local ten-language TTS ZIP.

Does not extract or modify the archive and does not load the ONNX models.
Run:
  python tools/inspect_itantra_tts_sidecars.py "app/src/main/assets/models/itantra-tts.zip"
"""
from __future__ import annotations

import argparse
import json
import sys
import zipfile

ROOT = ".itantra-tts-onnx"
LANGUAGES = {
    "bn": "Bengali",
    "en": "English",
    "gu": "Gujarati",
    "hi": "Hindi",
    "kn": "Kannada",
    "ml": "Malayalam",
    "mr": "Marathi",
    "or": "Odia",
    "ta": "Tamil",
    "te": "Telugu",
}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("zip_path", help="Path to itantra-tts.zip")
    args = parser.parse_args()

    try:
        with zipfile.ZipFile(args.zip_path) as archive:
            names = set(archive.namelist())
            for code, language in LANGUAGES.items():
                print("=" * 72)
                print(f"{language} ({code})")
                print("- config.json")
                config_path = f"{ROOT}/{code}/config.json"
                if config_path not in names:
                    print(f"MISSING: {config_path}")
                else:
                    raw = archive.read(config_path).decode("utf-8-sig")
                    try:
                        config = json.loads(raw)
                        print(json.dumps(config, ensure_ascii=False, indent=2, sort_keys=True))
                    except json.JSONDecodeError as exc:
                        print(f"Invalid JSON: {exc}\n{raw}")

                print("- tokens.txt")
                tokens_path = f"{ROOT}/{code}/tokens.txt"
                if tokens_path not in names:
                    print(f"MISSING: {tokens_path}")
                else:
                    raw_tokens = archive.read(tokens_path).decode("utf-8-sig")
                    lines = raw_tokens.splitlines()
                    print(f"bytes={len(raw_tokens.encode('utf-8'))}; lines={len(lines)}")
                    print("----- tokens.txt BEGIN -----")
                    print(raw_tokens, end="" if raw_tokens.endswith("\n") else "\n")
                    print("----- tokens.txt END -----")
                print()
    except (OSError, zipfile.BadZipFile, UnicodeDecodeError) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
