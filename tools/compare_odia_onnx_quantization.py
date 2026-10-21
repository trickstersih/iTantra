#!/usr/bin/env python3
"""Compare the original full-precision iTantra Odia ONNX model with bundled mixed-INT8.

This is a diagnostic only. It does not edit the app or replace any model.
Both ONNX models receive the exact same Hugging Face MMS tokenizer IDs.
"""
from __future__ import annotations

import argparse
import json
import sys
import wave
import zipfile
from pathlib import Path

import numpy as np

ONNX_REPO = "sriram09764/itantra-tts-onnx"
ZIP_ROOT = ".itantra-tts-onnx"
LANGUAGE = "or"
REFERENCE_REPO_CODE = "ory"
HF_TTS_REPO = "facebook/mms-tts-ory"
DEFAULT_TEXT = "ନମସ୍କାର, ଏହା ଓଡ଼ିଆ ଧ୍ୱନି ପରୀକ୍ଷା।"


def locate_zip_entry(archive: zipfile.ZipFile, code: str, filename: str) -> str:
    wanted = f"{ZIP_ROOT}/{code}/{filename}"
    matches = [
        item.filename.replace("\\", "/")
        for item in archive.infolist()
        if not item.is_dir()
        and item.filename.replace("\\", "/").endswith(wanted)
        and ZIP_ROOT in Path(item.filename.replace("\\", "/")).parts
    ]
    if len(matches) != 1:
        raise FileNotFoundError(
            f"Expected one ZIP entry {wanted}; found {len(matches)}: {matches}"
        )
    return matches[0]


def summarize(samples: np.ndarray, sample_rate: int) -> dict:
    x = np.asarray(samples, dtype=np.float64).reshape(-1)
    if x.size == 0:
        raise ValueError("Model returned an empty waveform")
    if not np.isfinite(x).all():
        raise ValueError("Waveform contains NaN or infinity")
    centered = x - x.mean()
    spectrum = np.abs(np.fft.rfft(centered))
    frequencies = np.fft.rfftfreq(x.size, d=1.0 / sample_rate)
    total_energy = float(np.sum(spectrum ** 2)) + 1e-12
    high_band = float(
        np.sum(spectrum[frequencies >= min(4000.0, sample_rate * 0.25)] ** 2)
    )
    return {
        "samples": int(x.size),
        "duration_seconds": round(x.size / sample_rate, 3),
        "sample_rate": int(sample_rate),
        "peak": round(float(np.max(np.abs(x))), 6),
        "rms": round(float(np.sqrt(np.mean(centered ** 2))), 6),
        "mean": round(float(x.mean()), 8),
        "clipped_sample_percent": round(float(np.mean(np.abs(x) >= 0.999) * 100), 5),
        "energy_above_4khz_percent": round(100.0 * high_band / total_energy, 3),
    }


def save_pcm16(path: Path, samples: np.ndarray, sample_rate: int) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    safe = np.nan_to_num(
        np.asarray(samples, dtype=np.float32), nan=0.0, posinf=0.0, neginf=0.0
    )
    pcm = (np.clip(safe, -1.0, 1.0) * 32767.0).astype("<i2", copy=False)
    with wave.open(str(path), "wb") as out:
        out.setnchannels(1)
        out.setsampwidth(2)
        out.setframerate(sample_rate)
        out.writeframes(pcm.tobytes())


def run_onnx(model_source: str | bytes, ids: np.ndarray, label: str):
    import onnxruntime as ort

    options = ort.SessionOptions()
    options.intra_op_num_threads = 1
    options.inter_op_num_threads = 1
    session = ort.InferenceSession(
        model_source,
        sess_options=options,
        providers=["CPUExecutionProvider"],
    )
    inputs = session.get_inputs()
    if len(inputs) != 1 or inputs[0].type != "tensor(int64)":
        raise RuntimeError(
            f"{label}: expected one int64 token input, but got "
            f"{[(item.name, item.type, item.shape) for item in inputs]}"
        )
    input_meta = inputs[0]
    outputs = session.get_outputs()
    output_meta = next((item for item in outputs if item.name == "audio"), outputs[0])
    print(
        f"{label} ONNX input: {input_meta.name} {input_meta.type} {input_meta.shape}; "
        f"output: {output_meta.name} {output_meta.shape}"
    )
    result = session.run([output_meta.name], {input_meta.name: ids})[0]
    audio = np.asarray(result, dtype=np.float32).reshape(-1)
    del session
    return audio


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "zip_path",
        nargs="?",
        type=Path,
        default=Path("app/src/main/assets/models/itantra-tts.zip"),
        help="Local bundled iTantra TTS ZIP",
    )
    parser.add_argument(
        "--text",
        default=DEFAULT_TEXT,
        help="Odia test sentence (default: the same sentence as the earlier comparison)",
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=Path("build/tts/odia_onnx_quantization"),
    )
    parser.add_argument(
        "--cache-dir",
        type=Path,
        default=Path("build/tts/odia_onnx_cache"),
    )
    args = parser.parse_args()

    if not args.zip_path.is_file():
        parser.error(f"ZIP does not exist: {args.zip_path}")

    try:
        import onnxruntime as ort
        from huggingface_hub import hf_hub_download, list_repo_files
        from transformers import AutoTokenizer
    except ImportError as exc:
        print(
            f"Missing dependency: {exc}\n"
            "Install with: python -m pip install numpy onnxruntime transformers huggingface_hub",
            file=sys.stderr,
        )
        return 3

    args.output_dir.mkdir(parents=True, exist_ok=True)
    args.cache_dir.mkdir(parents=True, exist_ok=True)
    print(f"ONNX Runtime: {ort.__version__}")
    print(f"Text: {args.text}")
    print(f"Bundled ZIP: {args.zip_path}")

    print("\nLoading the official MMS tokenizer...")
    tokenizer = AutoTokenizer.from_pretrained(
        HF_TTS_REPO, cache_dir=str(args.cache_dir), trust_remote_code=False
    )
    encoded = tokenizer(args.text, return_tensors="np")
    ids = np.asarray(encoded["input_ids"], dtype=np.int64)
    print(f"Exact official token IDs: {ids.shape[1]}")

    print(f"\nResolving the original FP32 ONNX model from {ONNX_REPO}...")
    files = set(list_repo_files(ONNX_REPO))
    candidates = [f"{LANGUAGE}/model.onnx", f"{REFERENCE_REPO_CODE}/model.onnx"]
    reference_name = next((name for name in candidates if name in files), None)
    if reference_name is None:
        raise FileNotFoundError(
            f"No original model.onnx found in {ONNX_REPO}. Checked: {candidates}"
        )
    reference_path = Path(
        hf_hub_download(
            repo_id=ONNX_REPO,
            filename=reference_name,
            cache_dir=str(args.cache_dir),
        )
    )
    print(f"Original ONNX path: {reference_path}")
    print(f"Original ONNX size: {reference_path.stat().st_size / (1024 * 1024):.2f} MiB")

    print("\nLoading the mixed-INT8 model from the existing ZIP...")
    with zipfile.ZipFile(args.zip_path, "r") as archive:
        model_entry = locate_zip_entry(archive, LANGUAGE, "model.int8.mixed.onnx")
        model_bytes = archive.read(model_entry)
        config_entry = locate_zip_entry(archive, LANGUAGE, "config.json")
        config = json.loads(archive.read(config_entry).decode("utf-8"))
    sample_rate = int(config.get("sample_rate", config.get("sampling_rate", 16000)))
    print(f"Bundled entry: {model_entry}")
    print(f"Sample rate from bundled config: {sample_rate}")

    print("\nRunning original full-precision ONNX...")
    reference_audio = run_onnx(str(reference_path), ids, "Original FP32")
    reference_stats = summarize(reference_audio, sample_rate)
    reference_wav = args.output_dir / "original_fp32_onnx.wav"
    save_pcm16(reference_wav, reference_audio, sample_rate)
    print("Original FP32 ONNX:", json.dumps(reference_stats, sort_keys=True))

    del reference_audio

    print("\nRunning bundled mixed-INT8 ONNX with the same token IDs...")
    quantized_audio = run_onnx(model_bytes, ids, "Bundled mixed-INT8")
    quantized_stats = summarize(quantized_audio, sample_rate)
    quantized_wav = args.output_dir / "bundled_mixed_int8_onnx.wav"
    save_pcm16(quantized_wav, quantized_audio, sample_rate)
    print("Bundled mixed-INT8:", json.dumps(quantized_stats, sort_keys=True))

    report = {
        "language": LANGUAGE,
        "text": args.text,
        "token_count": int(ids.shape[1]),
        "token_ids": ids.tolist(),
        "source_onnx_repo": ONNX_REPO,
        "source_onnx_file": reference_name,
        "bundled_model_entry": model_entry,
        "sample_rate": sample_rate,
        "onnxruntime_version": ort.__version__,
        "outputs": {
            "original_fp32_onnx": {**reference_stats, "wav": str(reference_wav)},
            "bundled_mixed_int8_onnx": {**quantized_stats, "wav": str(quantized_wav)},
        },
    }
    report_path = args.output_dir / "comparison.json"
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")

    print("\nComparison outputs:")
    print(f"  Original FP32 ONNX: {reference_wav}")
    print(f"  Bundled mixed-INT8: {quantized_wav}")
    print(f"  Report: {report_path}")
    print(
        "\nListen to both WAVs. If only mixed-INT8 has static, quantization is implicated. "
        "If both ONNX files have static while the PyTorch FP32 reference is clean, "
        "investigate the ONNX export/runtime path before changing quantization."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
