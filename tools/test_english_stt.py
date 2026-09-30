#!/usr/bin/env python3
"""
Desktop test runner for the English-only NeMo Conformer CTC STT model.

Default model:
  app/src/main/assets/models/english_stt.zip

The ZIP contains:
  - model.int8.onnx
  - model.onnx
  - config.json
  - vocab.txt

The model is tested through onnx-asr's local
"nemo-conformer-ctc" adapter.

Examples:
  python tools/test_english_stt.py --audio .\\english.wav
  python tools/test_english_stt.py --audio .\\english.wav --variant fp32
  python tools/test_english_stt.py --audio .\\english.wav --compare

Dependencies:
  python -m pip install -r tools/requirements-stt-english.txt
"""

from __future__ import annotations

import argparse
import json
import shutil
import time
import zipfile
from pathlib import Path

import numpy as np
import onnx_asr
import soundfile as sf


DEFAULT_ZIP = Path("app/src/main/assets/models/english_stt.zip")
DEFAULT_AUDIO = Path("english.wav")
DEFAULT_WORK_DIR = Path("build/stt/english-conformer-ctc")


def _find(files: list[str], basename: str) -> str | None:
    return next(
        (name for name in files if Path(name).name == basename),
        None,
    )


def extract_model(zip_path: Path, work_dir: Path) -> Path:
    work_dir.mkdir(parents=True, exist_ok=True)
    model_dir = work_dir / "model"
    model_dir.mkdir(parents=True, exist_ok=True)

    with zipfile.ZipFile(zip_path) as archive:
        files = [
            name for name in archive.namelist()
            if not name.endswith("/")
        ]

        required = ["config.json", "model.int8.onnx", "model.onnx", "vocab.txt"]
        missing = [name for name in required if _find(files, name) is None]
        if missing:
            raise RuntimeError(
                "Model ZIP is missing required files:\n  " +
                "\n  ".join(missing)
            )

        # Re-extract these owned files every run so the test never uses
        # stale model data from a previous archive.
        for name in required + ["README.md", "gitattributes"]:
            for old in model_dir.glob(Path(name).name):
                old.unlink()

        for name in required + ["README.md", "gitattributes"]:
            archive_name = _find(files, name)
            if archive_name is None:
                continue

            target = model_dir / name
            with archive.open(archive_name) as src, target.open("wb") as dst:
                shutil.copyfileobj(src, dst, length=1024 * 1024)

    return model_dir


def validate_config(model_dir: Path) -> None:
    config = json.loads(
        (model_dir / "config.json").read_text(encoding="utf-8")
    )

    print()
    print("=== MODEL CONFIG ===")
    print(json.dumps(config, indent=2))

    if config.get("model_type") != "nemo-conformer-ctc":
        raise RuntimeError(
            "Expected NeMo Conformer CTC model, got "
            + repr(config.get("model_type"))
        )

    if int(config.get("features_size", 0)) != 80:
        raise RuntimeError(
            f"Expected 80 input features, got {config.get('features_size')}"
        )


def load_audio(audio_path: Path) -> tuple[np.ndarray, int]:
    audio, sample_rate = sf.read(
        str(audio_path),
        dtype="float32",
        always_2d=False,
    )

    if audio.size == 0:
        raise RuntimeError(f"Audio file is empty: {audio_path}")

    audio = np.asarray(audio, dtype=np.float32)

    if audio.ndim == 2:
        audio = audio.mean(axis=1)

    audio = np.nan_to_num(
        audio,
        nan=0.0,
        posinf=0.0,
        neginf=0.0,
    )

    peak = float(np.max(np.abs(audio)))
    if peak > 1.0:
        audio = audio / peak

    print()
    print("=== AUDIO ===")
    print("Path:", audio_path)
    print("Sample rate:", sample_rate)
    print("Samples:", audio.size)
    print("Duration: %.3f s" % (audio.size / sample_rate))

    return audio, sample_rate


def result_text(result: object) -> str:
    if isinstance(result, str):
        return result.strip()

    text = getattr(result, "text", None)
    if text is not None:
        return str(text).strip()

    if isinstance(result, list):
        parts = []
        for item in result:
            item_text = getattr(item, "text", None)
            parts.append(str(item_text if item_text is not None else item))
        return "\n".join(parts).strip()

    return str(result).strip()


def run_variant(
    model_dir: Path,
    audio: np.ndarray,
    sample_rate: int,
    variant: str,
) -> str:
    quantization = "int8" if variant == "int8" else None

    print()
    print("=" * 72)
    print(f"TESTING {variant.upper()}")
    print("=" * 72)

    load_start = time.perf_counter()
    model = onnx_asr.load_model(
        "nemo-conformer-ctc",
        str(model_dir),
        quantization=quantization,
        providers=["CPUExecutionProvider"],
    )
    load_seconds = time.perf_counter() - load_start

    print("Model loaded in %.3f s" % load_seconds)

    inference_start = time.perf_counter()
    result = model.recognize(
        audio,
        sample_rate=sample_rate,
        channel="mean",
    )
    inference_seconds = time.perf_counter() - inference_start

    transcript = result_text(result)

    duration = audio.size / sample_rate
    rtf = inference_seconds / duration if duration else float("inf")
    rtf_x = duration / inference_seconds if inference_seconds else float("inf")

    print()
    print("TRANSCRIPTION:")
    print(transcript or "<empty>")
    print()
    print("METRICS:")
    print("  Variant:", variant)
    print("  Load time: %.3f s" % load_seconds)
    print("  Inference time: %.3f s" % inference_seconds)
    print("  Audio duration: %.3f s" % duration)
    print("  RTF: %.4f" % rtf)
    print("  RTFx: %.2f" % rtf_x)

    del model
    return transcript


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--zip",
        dest="zip_path",
        type=Path,
        default=DEFAULT_ZIP,
        help=f"Model ZIP (default: {DEFAULT_ZIP})",
    )
    parser.add_argument(
        "--audio",
        type=Path,
        default=DEFAULT_AUDIO,
        help=f"English audio file (default: {DEFAULT_AUDIO})",
    )
    parser.add_argument(
        "--variant",
        choices=("int8", "fp32"),
        default="int8",
        help="Weight variant to test (default: int8)",
    )
    parser.add_argument(
        "--compare",
        action="store_true",
        help="Run both INT8 and FP32 and compare their transcripts.",
    )
    parser.add_argument(
        "--work-dir",
        type=Path,
        default=DEFAULT_WORK_DIR,
        help=f"Extraction directory (default: {DEFAULT_WORK_DIR})",
    )
    args = parser.parse_args()

    if not args.zip_path.is_file():
        raise FileNotFoundError(
            f"Model ZIP not found: {args.zip_path}"
        )

    if not args.audio.is_file():
        raise FileNotFoundError(
            f"Audio file not found: {args.audio}"
        )

    model_dir = extract_model(args.zip_path, args.work_dir)
    validate_config(model_dir)
    audio, sample_rate = load_audio(args.audio)

    if args.compare:
        int8_transcript = run_variant(
            model_dir, audio, sample_rate, "int8"
        )
        fp32_transcript = run_variant(
            model_dir, audio, sample_rate, "fp32"
        )

        print()
        print("=" * 72)
        print("COMPARISON")
        print("=" * 72)
        print("INT8:", int8_transcript or "<empty>")
        print("FP32:", fp32_transcript or "<empty>")
    else:
        run_variant(
            model_dir,
            audio,
            sample_rate,
            args.variant,
        )

    print()
    print("TEST COMPLETE")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
