#!/usr/bin/env python3
"""Inspect a local multilingual TTS ZIP and probe every ONNX model with ONNX Runtime.

This is an inspection tool only. It does not modify the archive or app assets.
Run:
  python tools/inspect_multilingual_tts_zip.py "app/src/main/assets/models/tts_piper(10).zip"
"""
from __future__ import annotations

import argparse
import collections
import importlib.util
import sys
import zipfile
from pathlib import PurePosixPath

LANGUAGE_ALIASES = {
    "Bengali": {"bengali", "ben", "bn"},
    "English": {"english", "eng", "en"},
    "Gujarati": {"gujarati", "guj", "gu"},
    "Hindi": {"hindi", "hin", "hi"},
    "Kannada": {"kannada", "kan", "kn"},
    "Malayalam": {"malayalam", "mal", "ml"},
    "Marathi": {"marathi", "mar", "mr"},
    "Odia": {"odia", "oriya", "ory", "or"},
    "Tamil": {"tamil", "tam", "ta"},
    "Telugu": {"telugu", "tel", "te"},
}


def human_size(value: int) -> str:
    amount = float(value)
    for unit in ("B", "KiB", "MiB", "GiB"):
        if amount < 1024 or unit == "GiB":
            return f"{amount:,.2f} {unit}"
        amount /= 1024
    return f"{amount:,.2f} GiB"


def unsafe_member(name: str) -> bool:
    path = PurePosixPath(name.replace("\\", "/"))
    return path.is_absolute() or ".." in path.parts


def language_for_path(name: str) -> str | None:
    aliases = {
        alias: language
        for language, names in LANGUAGE_ALIASES.items()
        for alias in names
    }
    for part in reversed(PurePosixPath(name.replace("\\", "/")).parts[:-1]):
        found = aliases.get(part.casefold())
        if found:
            return found
    return None


def print_model_metadata(model_name: str, model_bytes: bytes) -> bool:
    try:
        import onnxruntime as ort
    except ImportError:
        print("\nONNX Runtime is not installed; skipped ONNX session checks.")
        return False

    options = ort.SessionOptions()
    options.intra_op_num_threads = 1
    options.inter_op_num_threads = 1
    try:
        session = ort.InferenceSession(
            model_bytes,
            sess_options=options,
            providers=["CPUExecutionProvider"],
        )
        print(f"  ORT LOAD: PASS ({model_name})")
        for item in session.get_inputs():
            print(f"    input  {item.name}: type={item.type}, shape={item.shape}")
        for item in session.get_outputs():
            print(f"    output {item.name}: type={item.type}, shape={item.shape}")
        meta = session.get_modelmeta()
        if meta.producer_name or meta.graph_name or meta.domain:
            print(
                f"    metadata: producer={meta.producer_name!r}, "
                f"graph={meta.graph_name!r}, domain={meta.domain!r}"
            )
        del session
        return True
    except Exception as exc:  # print the per-model failure and continue inspecting the rest
        print(f"  ORT LOAD: FAIL ({model_name}): {type(exc).__name__}: {exc}")
        print("    Note: check whether this model uses external ONNX data files.")
        return False


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("zip_path", help="Path to the local multilingual TTS ZIP")
    parser.add_argument(
        "--skip-runtime",
        action="store_true",
        help="Inventory the ZIP without opening ONNX sessions",
    )
    args = parser.parse_args()

    try:
        with zipfile.ZipFile(args.zip_path, "r") as archive:
            infos = archive.infolist()
            files = [info for info in infos if not info.is_dir()]
            counts = collections.Counter(info.filename for info in files)
            duplicates = sorted(name for name, count in counts.items() if count > 1)
            bad_member = archive.testzip()
    except (OSError, zipfile.BadZipFile) as exc:
        print(f"ERROR: cannot open ZIP: {exc}", file=sys.stderr)
        return 2

    actual_size = __import__("os").path.getsize(args.zip_path)
    compressed_total = sum(info.compress_size for info in files)
    uncompressed_total = sum(info.file_size for info in files)
    onnx_files = [info for info in files if info.filename.casefold().endswith(".onnx")]

    print(f"Archive: {args.zip_path}")
    print(f"ZIP size on disk:       {human_size(actual_size)}")
    print(f"Entry compressed sum:   {human_size(compressed_total)}")
    print(f"Entry uncompressed sum: {human_size(uncompressed_total)}")
    ratio = 100.0 * compressed_total / uncompressed_total if uncompressed_total else 0.0
    print(f"Compressed/uncompressed: {ratio:.1f}%")
    print(f"Files: {len(files)} | ONNX models: {len(onnx_files)}")
    print(f"ZIP CRC test: {'PASS' if bad_member is None else 'FAIL at ' + bad_member}")
    print(f"Duplicate paths: {duplicates if duplicates else 'none'}")

    unsafe = [info.filename for info in files if unsafe_member(info.filename)]
    print(f"Unsafe archive paths: {unsafe if unsafe else 'none'}")

    by_language: dict[str, list[zipfile.ZipInfo]] = collections.defaultdict(list)
    unknown: list[zipfile.ZipInfo] = []
    for info in files:
        language = language_for_path(info.filename)
        if language:
            by_language[language].append(info)
        elif info.filename.casefold().endswith(".onnx"):
            unknown.append(info)

    print("\nLanguage inventory:")
    for language in LANGUAGE_ALIASES:
        items = by_language.get(language, [])
        models = [item for item in items if item.filename.casefold().endswith(".onnx")]
        model_size = sum(item.file_size for item in models)
        print(
            f"  {language:10} entries={len(items):3} "
            f"onnx={len(models):2} model_size={human_size(model_size)}"
        )
        for model in models:
            print(
                f"    {model.filename} "
                f"(stored={human_size(model.compress_size)}, "
                f"unpacked={human_size(model.file_size)})"
            )
        if models:
            parent_dirs = {str(PurePosixPath(model.filename).parent) for model in models}
            for parent in sorted(parent_dirs):
                sidecars = [
                    item for item in items
                    if str(PurePosixPath(item.filename).parent) == parent
                    and not item.filename.casefold().endswith(".onnx")
                ]
                if sidecars:
                    print("    sidecars:")
                    for item in sidecars:
                        print(f"      {PurePosixPath(item.filename).name} ({human_size(item.file_size)})")

    if unknown:
        print("\nONNX models whose paths do not identify one of the 10 expected languages:")
        for item in unknown:
            print(f"  {item.filename} ({human_size(item.file_size)})")

    expected = set(LANGUAGE_ALIASES)
    present = {
        language
        for language, items in by_language.items()
        if any(info.filename.casefold().endswith(".onnx") for info in items)
    }
    print("\nSummary:")
    print(f"  Languages with an ONNX model: {len(present)}/10")
    print(f"  Missing expected languages: {sorted(expected - present) if expected - present else 'none'}")
    print(f"  English model present: {'yes' if 'English' in present else 'no'}")
    print(f"  Hindi model present:   {'yes' if 'Hindi' in present else 'no'}")
    if bad_member or duplicates or unsafe:
        print("  ARCHIVE CHECK: FAIL")
    else:
        print("  ARCHIVE CHECK: PASS (integrity/path/duplicate checks only)")

    if not args.skip_runtime and onnx_files:
        if importlib.util.find_spec("onnxruntime") is None:
            print("\nInstall ONNX Runtime in the active Python environment to inspect model I/O:")
            print("  python -m pip install onnxruntime")
            return 3
        load_passes = 0
        load_failures = 0
        print("\nSequential ONNX Runtime load checks (metadata only; no audio synthesis):")
        with zipfile.ZipFile(args.zip_path, "r") as archive:
            for model in onnx_files:
                ok = print_model_metadata(model.filename, archive.read(model.filename))
                if ok:
                    load_passes += 1
                else:
                    load_failures += 1
        print(f"\nONNX session loads: {load_passes} passed, {load_failures} failed")
        if load_failures:
            return 4

    return 0 if len(onnx_files) == 10 and present == expected and not bad_member and not unsafe and not duplicates else 1


if __name__ == "__main__":
    raise SystemExit(main())
