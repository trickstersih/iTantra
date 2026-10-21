#!/usr/bin/env python3
"""Create a compact, local-only Odia test ZIP containing the MatMul-INT8 candidate.

The ZIP has only Odia's config, token sidecar, and experimental ONNX model.
The original ten-language bundle remains unchanged and supplies every other
language as before. This creates a separate ignored ZIP and never overwrites it.
"""
from __future__ import annotations

import argparse
import json
import zipfile
from pathlib import Path

ROOT = ".itantra-tts-onnx"
CODE = "or"
SOURCE_MODEL_NAME = "model.int8.mixed.onnx"
TARGET_MODEL_NAME = "model.matmul.int8.onnx"
SIDECAR_NAMES = ("config.json", "tokens.txt")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--source-zip",
        type=Path,
        default=Path("app/src/main/assets/models/itantra-tts.zip"),
    )
    parser.add_argument(
        "--candidate",
        type=Path,
        default=Path("build/tts/odia_matmul_only/model.matmul.int8.onnx"),
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=Path("app/src/main/assets/models/itantra-tts-odia-test.zip"),
    )
    args = parser.parse_args()

    if not args.source_zip.is_file():
        parser.error(f"Source ZIP not found: {args.source_zip}")
    if not args.candidate.is_file():
        parser.error(f"Candidate ONNX not found: {args.candidate}")
    if args.output.exists():
        parser.error(f"Refusing to overwrite existing test ZIP: {args.output}")

    sidecar_entries = {name: f"{ROOT}/{CODE}/{name}" for name in SIDECAR_NAMES}
    source_model_entry = f"{ROOT}/{CODE}/{SOURCE_MODEL_NAME}"
    target_model_entry = f"{ROOT}/{CODE}/{TARGET_MODEL_NAME}"
    expected = set(sidecar_entries.values()) | {target_model_entry}

    args.output.parent.mkdir(parents=True, exist_ok=True)

    try:
        with zipfile.ZipFile(args.source_zip, "r") as source:
            names = set(source.namelist())
            source_required = set(sidecar_entries.values()) | {source_model_entry}
            if not source_required.issubset(names):
                raise RuntimeError(
                    f"Source ZIP missing Odia entries: {source_required - names}"
                )

            payloads = {
                entry: source.read(entry)
                for entry in sidecar_entries.values()
            }
            config = json.loads(payloads[sidecar_entries["config.json"]].decode("utf-8"))
            if not isinstance(config, dict):
                raise RuntimeError("Odia config.json is not a JSON object")

        candidate_bytes = args.candidate.read_bytes()
        if not candidate_bytes:
            raise RuntimeError(f"Candidate ONNX is empty: {args.candidate}")

        with zipfile.ZipFile(
            args.output,
            "x",
            compression=zipfile.ZIP_DEFLATED,
            compresslevel=6,
        ) as target:
            for entry, data in payloads.items():
                target.writestr(entry, data)
            target.writestr(target_model_entry, candidate_bytes)

        with zipfile.ZipFile(args.output, "r") as result:
            bad = result.testzip()
            if bad is not None:
                raise RuntimeError(f"ZIP CRC validation failed at {bad}")
            out_names = set(result.namelist())
            if out_names != expected:
                raise RuntimeError(
                    f"Test ZIP entries differ from expected. "
                    f"Missing={expected - out_names}, extra={out_names - expected}"
                )
            archived_config = json.loads(
                result.read(sidecar_entries["config.json"]).decode("utf-8")
            )
            if not isinstance(archived_config, dict):
                raise RuntimeError("Archived Odia config.json is invalid")
            if result.getinfo(target_model_entry).file_size != len(candidate_bytes):
                raise RuntimeError("Archived model size differs from candidate")

        print("\nCreated verified compact Odia test bundle:")
        print(f"  {args.output}")
        print(f"  ZIP size: {args.output.stat().st_size:,} bytes")
        print(f"  candidate model: {args.candidate}")
        print(f"  candidate size: {len(candidate_bytes):,} bytes")
        print("  entries:")
        for entry in sorted(expected):
            print(f"    {entry}")
        print("  Contains only Odia files; the main ten-language ZIP is untouched.")
        return 0
    except Exception:
        if args.output.exists():
            args.output.unlink()
        raise


if __name__ == "__main__":
    raise SystemExit(main())
