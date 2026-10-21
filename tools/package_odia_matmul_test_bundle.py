#!/usr/bin/env python3
"""Create a local test bundle swapping only Odia model.onnx for a MatMul-INT8 candidate.

All other language entries are copied byte-for-byte from the existing ZIP.
This creates a separate ignored ZIP and never overwrites the standard bundle.
"""
from __future__ import annotations

import argparse
import json
import sys
import zipfile
from pathlib import Path

ROOT = ".itantra-tts-onnx"
CODE = "or"
SOURCE_MODEL_NAME = "model.int8.mixed.onnx"
TARGET_MODEL_NAME = "model.matmul.int8.onnx"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-zip", type=Path, default=Path("app/src/main/assets/models/itantra-tts.zip"))
    parser.add_argument("--candidate", type=Path, default=Path("build/tts/odia_matmul_only/model.matmul.int8.onnx"))
    parser.add_argument("--output", type=Path, default=Path("app/src/main/assets/models/itantra-tts-odia-test.zip"))
    args = parser.parse_args()

    if not args.source_zip.is_file():
        parser.error(f"Source ZIP not found: {args.source_zip}")
    if not args.candidate.is_file():
        parser.error(f"Candidate ONNX not found: {args.candidate}")
    if args.output.exists():
        parser.error(f"Refusing to overwrite existing test ZIP: {args.output}")

    source_model_entry = f"{ROOT}/{CODE}/{SOURCE_MODEL_NAME}"
    model_entry = f"{ROOT}/{CODE}/{TARGET_MODEL_NAME}"
    required = {
        f"{ROOT}/{CODE}/config.json",
        f"{ROOT}/{CODE}/tokens.txt",
        source_model_entry,
    }
    required_output = {
        f"{ROOT}/{CODE}/config.json",
        f"{ROOT}/{CODE}/tokens.txt",
        model_entry,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)

    try:
        with zipfile.ZipFile(args.source_zip, "r") as source:
            names = [info.filename for info in source.infolist()]
            if not required.issubset(set(names)):
                raise RuntimeError(f"Source bundle missing required Odia entries: {required - set(names)}")
            with zipfile.ZipFile(args.output, "x", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as target:
                for info in source.infolist():
                    if info.is_dir():
                        target.writestr(info, b"")
                        continue
                    normalized = info.filename.replace("\\", "/")
                    if normalized == source_model_entry:
                        target.writestr(model_entry, args.candidate.read_bytes())
                        print(f"REPLACED for test: {source_model_entry} -> {model_entry}")
                    else:
                        target.writestr(info, source.read(info.filename))
        with zipfile.ZipFile(args.output, "r") as result:
            bad = result.testzip()
            if bad is not None:
                raise RuntimeError(f"ZIP CRC validation failed at {bad}")
            out_names = set(result.namelist())
            if not required_output.issubset(out_names):
                raise RuntimeError(f"Test ZIP missing entries: {required_output - out_names}")
            if source_model_entry in out_names:
                raise RuntimeError(f"Original Odia model entry was not replaced: {source_model_entry}")
            config = json.loads(result.read(f"{ROOT}/{CODE}/config.json").decode("utf-8"))
            if not isinstance(config, dict):
                raise RuntimeError("Odia config.json is not a JSON object")
            replaced_size = result.getinfo(model_entry).file_size
            if replaced_size != args.candidate.stat().st_size:
                raise RuntimeError("Odia model size in ZIP differs from candidate")
        print("\nCreated verified Odia test bundle:")
        print(f"  {args.output}")
        print(f"  size: {args.output.stat().st_size:,} bytes")
        print(f"  candidate: {args.candidate} ({args.candidate.stat().st_size:,} bytes)")
        print("All non-Odia file payloads were copied unchanged from the source ZIP.")
        return 0
    except Exception:
        if args.output.exists():
            args.output.unlink()
        raise


if __name__ == "__main__":
    raise SystemExit(main())
