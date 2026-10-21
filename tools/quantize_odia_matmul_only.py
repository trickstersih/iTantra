#!/usr/bin/env python3
"""Build and listen-test a conservative Odia MMS-TTS quantization candidate.

Only MatMul nodes (or an explicitly supplied subset) are dynamically INT8
quantized. Convolution/ConvTranspose nodes, which dominate the VITS vocoder,
are deliberately left untouched. This is a PC diagnostic; it does not alter
the Android app or the bundled model ZIP.
"""
from __future__ import annotations

import argparse
import gc
import json
import sys
import zipfile
from pathlib import Path

import numpy as np

SOURCE_REPO = "sriram09764/itantra-tts-onnx"
SOURCE_FILE = "or/model.onnx"
TOKENIZER_REPO = "facebook/mms-tts-ory"
ZIP_ROOT = ".itantra-tts-onnx"
LANGUAGE = "or"
DEFAULT_TEXT = "ନମସ୍କାର, ଏହା ଓଡ଼ିଆ ଧ୍ୱନି ପରୀକ୍ଷା।"


def locate_entry(archive: zipfile.ZipFile, filename: str) -> str:
    wanted = f"{ZIP_ROOT}/{LANGUAGE}/{filename}"
    exact = [
        item.filename.replace("\\", "/")
        for item in archive.infolist()
        if not item.is_dir() and item.filename.replace("\\", "/") == wanted
    ]
    if len(exact) != 1:
        raise FileNotFoundError(f"Expected {wanted} exactly once; found {exact}")
    return exact[0]


def write_wav(path: Path, samples: np.ndarray, rate: int) -> None:
    import wave

    x = np.nan_to_num(
        np.asarray(samples, dtype=np.float32).reshape(-1),
        nan=0.0,
        posinf=0.0,
        neginf=0.0,
    )
    pcm = (np.clip(x, -1.0, 1.0) * 32767.0).astype("<i2", copy=False)
    path.parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(path), "wb") as handle:
        handle.setnchannels(1)
        handle.setsampwidth(2)
        handle.setframerate(rate)
        handle.writeframes(pcm.tobytes())


def synthesize(model_path: str | Path | bytes, ids: np.ndarray, ort) -> np.ndarray:
    options = ort.SessionOptions()
    options.intra_op_num_threads = 1
    options.inter_op_num_threads = 1
    session = ort.InferenceSession(
        model_path,
        sess_options=options,
        providers=["CPUExecutionProvider"],
    )
    inputs = session.get_inputs()
    if len(inputs) != 1 or inputs[0].type != "tensor(int64)":
        raise RuntimeError(
            "Expected one int64 input; actual inputs: "
            + repr([(x.name, x.type, x.shape) for x in inputs])
        )
    outputs = session.get_outputs()
    target = next((x for x in outputs if x.name == "audio"), outputs[0])
    print(
        f"Runtime input={inputs[0].name} {inputs[0].shape}; "
        f"output={target.name} {target.shape}"
    )
    audio = session.run([target.name], {inputs[0].name: ids})[0]
    del session
    return np.asarray(audio, dtype=np.float32).reshape(-1)


def describe(x: np.ndarray, sample_rate: int) -> dict:
    x = np.asarray(x, dtype=np.float64).reshape(-1)
    if not x.size:
        raise ValueError("Empty waveform")
    if not np.isfinite(x).all():
        raise ValueError("Waveform contains non-finite samples")
    centered = x - x.mean()
    spec = np.abs(np.fft.rfft(centered))
    freq = np.fft.rfftfreq(x.size, d=1 / sample_rate)
    total = float(np.sum(spec * spec)) + 1e-12
    high = float(np.sum(spec[freq >= min(4000.0, sample_rate * 0.25)] ** 2))
    return {
        "samples": int(x.size),
        "duration_seconds": round(x.size / sample_rate, 3),
        "peak": round(float(np.max(np.abs(x))), 6),
        "rms": round(float(np.sqrt(np.mean(centered * centered))), 6),
        "clipped_sample_percent": round(float(np.mean(np.abs(x) >= 0.999) * 100), 5),
        "energy_above_4khz_percent": round(100.0 * high / total, 3),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--text",
        default=DEFAULT_TEXT,
        help="Odia sentence; defaults to the sentence used in previous tests",
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=Path("build/tts/odia_matmul_only"),
    )
    parser.add_argument(
        "--cache-dir",
        type=Path,
        default=Path("build/tts/odia_onnx_cache"),
    )
    parser.add_argument(
        "--zip-path",
        type=Path,
        default=Path("app/src/main/assets/models/itantra-tts.zip"),
    )
    args = parser.parse_args()

    if not args.zip_path.is_file():
        parser.error(f"ZIP does not exist: {args.zip_path}")

    try:
        import onnx
        import onnxruntime as ort
        from huggingface_hub import hf_hub_download
        from onnxruntime.quantization import QuantType, quantize_dynamic
        from transformers import AutoTokenizer
    except ImportError as exc:
        print(
            f"Missing dependency: {exc}\n"
            "Install with: python -m pip install numpy onnx onnxruntime transformers huggingface_hub",
            file=sys.stderr,
        )
        return 3

    args.output_dir.mkdir(parents=True, exist_ok=True)
    args.cache_dir.mkdir(parents=True, exist_ok=True)
    print("ONNX Runtime:", ort.__version__)
    print("Loading authoritative MMS Odia tokenizer...")
    tokenizer = AutoTokenizer.from_pretrained(
        TOKENIZER_REPO,
        cache_dir=str(args.cache_dir),
        trust_remote_code=False,
    )
    encoded = tokenizer(args.text, return_tensors="np")
    ids = np.asarray(encoded["input_ids"], dtype=np.int64)
    print("Text:", args.text)
    print("Official token IDs:", ids.shape[1])

    print(f"\nDownloading/resolving source FP32 ONNX: {SOURCE_REPO}/{SOURCE_FILE}")
    source_path = Path(
        hf_hub_download(
            repo_id=SOURCE_REPO,
            filename=SOURCE_FILE,
            cache_dir=str(args.cache_dir),
        )
    )
    print(f"FP32 source: {source_path}")
    print(f"FP32 size: {source_path.stat().st_size / (1024 * 1024):.2f} MiB")

    with zipfile.ZipFile(args.zip_path, "r") as archive:
        config_entry = locate_entry(archive, "config.json")
        config = json.loads(archive.read(config_entry).decode("utf-8"))
        mixed_entry = locate_entry(archive, "model.int8.mixed.onnx")
        mixed_bytes = archive.read(mixed_entry)
    sample_rate = int(config.get("sample_rate", config.get("sampling_rate", 16000)))

    # Save a direct local copy from the repository's FP32 reference, so the
    # candidate remains reproducible and easy to compare with the prior baseline.
    fp32_out = args.output_dir / "model.fp32.onnx"
    if not fp32_out.exists():
        import shutil
        shutil.copy2(source_path, fp32_out)

    candidate = args.output_dir / "model.matmul.int8.onnx"
    if not candidate.exists():
        print("\nQuantizing MatMul nodes only; Conv and ConvTranspose remain FP32...")
        quantize_dynamic(
            model_input=str(source_path),
            model_output=str(candidate),
            op_types_to_quantize=["MatMul"],
            weight_type=QuantType.QInt8,
            per_channel=True,
            extra_options={"MatMulConstBOnly": True},
        )
    else:
        print("\nReusing existing candidate:", candidate)

    print("Checking ONNX structure...")
    onnx.checker.check_model(str(candidate))
    candidate_model = onnx.load(str(candidate), load_external_data=False)
    ops = {}
    for node in candidate_model.graph.node:
        ops[node.op_type] = ops.get(node.op_type, 0) + 1
    print("Candidate top-level operator counts:", json.dumps(ops, sort_keys=True))
    del candidate_model
    gc.collect()

    print("\nRunning original FP32 ONNX...")
    fp32_audio = synthesize(str(fp32_out), ids, ort)
    fp32_stats = describe(fp32_audio, sample_rate)
    fp32_wav = args.output_dir / "original_fp32_onnx.wav"
    write_wav(fp32_wav, fp32_audio, sample_rate)
    print("FP32:", json.dumps(fp32_stats, sort_keys=True))
    del fp32_audio
    gc.collect()

    print("\nRunning conservative MatMul-INT8 candidate...")
    int8_audio = synthesize(str(candidate), ids, ort)
    int8_stats = describe(int8_audio, sample_rate)
    int8_wav = args.output_dir / "matmul_int8_candidate.wav"
    write_wav(int8_wav, int8_audio, sample_rate)
    print("MatMul INT8:", json.dumps(int8_stats, sort_keys=True))
    del int8_audio
    gc.collect()

    print("\nRunning current bundled mixed-INT8 baseline...")
    mixed_audio = synthesize(mixed_bytes, ids, ort)
    mixed_stats = describe(mixed_audio, sample_rate)
    mixed_wav = args.output_dir / "current_mixed_int8.wav"
    write_wav(mixed_wav, mixed_audio, sample_rate)
    print("Current mixed INT8:", json.dumps(mixed_stats, sort_keys=True))

    report = {
        "language": "Odia",
        "text": args.text,
        "token_count": int(ids.shape[1]),
        "token_ids": ids.tolist(),
        "sample_rate": sample_rate,
        "source_repository": SOURCE_REPO,
        "source_onnx_file": SOURCE_FILE,
        "quantized_ops": ["MatMul"],
        "operator_counts": ops,
        "outputs": {
            "original_fp32_onnx": {
                **fp32_stats, "path": str(fp32_wav),
                "model_bytes": fp32_out.stat().st_size,
            },
            "matmul_int8_candidate": {
                **int8_stats, "path": str(int8_wav),
                "model_bytes": candidate.stat().st_size,
            },
            "current_bundled_mixed_int8": {
                **mixed_stats, "path": str(mixed_wav),
                "model_bytes": len(mixed_bytes),
            },
        },
    }
    report_path = args.output_dir / "comparison.json"
    report_path.write_text(
        json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    print("\nWAVs and report written under:", args.output_dir)
    print("  original_fp32_onnx.wav")
    print("  matmul_int8_candidate.wav")
    print("  current_mixed_int8.wav")
    print("  comparison.json")
    print("\nListen before using any candidate in the app. No app files or ZIPs were modified.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
