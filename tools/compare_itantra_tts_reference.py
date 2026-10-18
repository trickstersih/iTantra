#!/usr/bin/env python3
"""Compare a bundled mixed-INT8 MMS-TTS ONNX model against Meta's FP32 reference.

The script uses the official Hugging Face tokenizer for BOTH model paths.
This isolates model/export/quantization differences from Android tokenization.
Only one reference model is loaded at a time.

Dependencies in the active venv:
  python -m pip install torch transformers safetensors onnxruntime numpy

Examples:
  python tools/compare_itantra_tts_reference.py app/src/main/assets/models/itantra-tts.zip --language bn
  python tools/compare_itantra_tts_reference.py app/src/main/assets/models/itantra-tts.zip --language mr --text "नमस्कार, ही मराठी आवाज चाचणी आहे।"

The official MMS-TTS model checkpoints are downloaded from Hugging Face on first use.
This is a PC diagnostic, not Android code. It does not change model files.
"""
from __future__ import annotations

import argparse
import gc
import json
import math
import sys
import wave
import zipfile
from pathlib import Path

import numpy as np

LANGUAGES = {
    "bn": {
        "name": "Bengali",
        "repo_code": "ben",
        "sample": "নমস্কার, এটি বাংলা কণ্ঠস্বর পরীক্ষা।",
    },
    "en": {
        "name": "English",
        "repo_code": "eng",
        "sample": "Hello, this is an offline voice test.",
    },
    "gu": {
        "name": "Gujarati",
        "repo_code": "guj",
        "sample": "નમસ્તે, આ ગુજરાતી અવાજની કસોટી છે.",
    },
    "hi": {
        "name": "Hindi",
        "repo_code": "hin",
        "sample": "नमस्ते, यह एक हिंदी आवाज़ परीक्षण है।",
    },
    "kn": {
        "name": "Kannada",
        "repo_code": "kan",
        "sample": "ನಮಸ್ಕಾರ, ಇದು ಕನ್ನಡ ಧ್ವನಿ ಪರೀಕ್ಷೆ.",
    },
    "ml": {
        "name": "Malayalam",
        "repo_code": "mal",
        "sample": "നമസ്കാരം, ഇത് മലയാള ശബ്ദ പരീക്ഷണമാണ്.",
    },
    "mr": {
        "name": "Marathi",
        "repo_code": "mar",
        "sample": "नमस्कार, ही मराठी आवाज चाचणी आहे.",
    },
    "or": {
        "name": "Odia",
        "repo_code": "ory",
        "sample": "ନମସ୍କାର, ଏହା ଓଡ଼ିଆ ଧ୍ୱନି ପରୀକ୍ଷା।",
    },
    "ta": {
        "name": "Tamil",
        "repo_code": "tam",
        "sample": "வணக்கம், இது தமிழ் குரல் சோதனை.",
    },
    "te": {
        "name": "Telugu",
        "repo_code": "tel",
        "sample": "నమస్కారం, ఇది తెలుగు స్వర పరీక్ష.",
    },
}
ZIP_ROOT = ".itantra-tts-onnx"


def locate_entry(archive: zipfile.ZipFile, code: str, filename: str) -> str:
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
            f"Expected one {filename} entry for {code}, found {len(matches)}: {matches}"
        )
    return matches[0]


def save_pcm16(path: Path, samples: np.ndarray, sample_rate: int) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    safe = np.nan_to_num(np.asarray(samples, dtype=np.float32), nan=0.0, posinf=0.0, neginf=0.0)
    pcm = (np.clip(safe, -1.0, 1.0) * 32767.0).astype("<i2", copy=False)
    with wave.open(str(path), "wb") as wav:
        wav.setnchannels(1)
        wav.setsampwidth(2)
        wav.setframerate(sample_rate)
        wav.writeframes(pcm.tobytes())


def summarize(samples: np.ndarray, sample_rate: int) -> dict:
    x = np.asarray(samples, dtype=np.float64).reshape(-1)
    if x.size == 0:
        raise ValueError("Empty waveform")
    if not np.isfinite(x).all():
        raise ValueError("Waveform contains NaN or infinity")
    centered = x - x.mean()
    rms = float(np.sqrt(np.mean(np.square(centered))))
    peak = float(np.max(np.abs(x)))
    clipped = float(np.mean(np.abs(x) >= 0.999) * 100.0)
    # A descriptive diagnostic, not an objective speech-quality score.
    spectrum = np.abs(np.fft.rfft(centered))
    frequencies = np.fft.rfftfreq(centered.size, d=1.0 / sample_rate)
    total = float(np.sum(spectrum ** 2)) + 1e-12
    upper = float(np.sum(spectrum[frequencies >= min(4000.0, sample_rate * 0.25)] ** 2))
    return {
        "samples": int(x.size),
        "duration_seconds": round(x.size / sample_rate, 3),
        "sample_rate": int(sample_rate),
        "peak": round(peak, 6),
        "rms": round(rms, 6),
        "mean": round(float(x.mean()), 8),
        "clipped_sample_percent": round(clipped, 5),
        "energy_above_4khz_percent": round(100.0 * upper / total, 3),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("zip_path", type=Path, help="Local itantra-tts.zip")
    parser.add_argument("--language", choices=sorted(LANGUAGES), required=True)
    parser.add_argument("--text", help="Override the default test sentence")
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=Path("build/tts/reference_comparison"),
        help="Output directory for side-by-side WAVs and JSON report",
    )
    parser.add_argument(
        "--cache-dir",
        type=Path,
        default=Path("build/tts/hf_reference_cache"),
        help="Hugging Face model cache directory",
    )
    args = parser.parse_args()

    if not args.zip_path.is_file():
        parser.error(f"ZIP does not exist: {args.zip_path}")

    try:
        import onnxruntime as ort
        import torch
        from transformers import AutoTokenizer, VitsModel
    except ImportError as exc:
        print(
            f"Missing dependency: {exc}\n"
            "Install with: python -m pip install torch transformers safetensors onnxruntime numpy",
            file=sys.stderr,
        )
        return 3

    language = LANGUAGES[args.language]
    repo_id = f"facebook/mms-tts-{language['repo_code']}"
    text = args.text or language["sample"]
    output_dir = args.output_dir / args.language
    args.cache_dir.mkdir(parents=True, exist_ok=True)
    output_dir.mkdir(parents=True, exist_ok=True)

    print(f"Language: {language['name']} ({args.language})")
    print(f"Reference checkpoint: {repo_id}")
    print(f"Text: {text}")
    print(f"ZIP: {args.zip_path}")

    # Official tokenizer is authoritative for both inference paths.
    print("\nLoading official Hugging Face tokenizer...")
    tokenizer = AutoTokenizer.from_pretrained(
        repo_id,
        cache_dir=str(args.cache_dir),
        trust_remote_code=False,
    )
    encoded = tokenizer(text, return_tensors="pt")
    if "input_ids" not in encoded:
        raise RuntimeError(f"Official tokenizer produced no input_ids: {encoded.keys()}")
    ids = encoded["input_ids"].detach().cpu().numpy().astype(np.int64)
    print(f"Official tokenizer IDs: {ids.shape[1]} tokens")
    print(f"Tokenizer output keys: {list(encoded.keys())}")
    with (output_dir / "official_token_ids.json").open("w", encoding="utf-8") as handle:
        json.dump(
            {
                "repo_id": repo_id,
                "language": args.language,
                "text": text,
                "input_ids": ids.tolist(),
                "attention_mask": encoded.get("attention_mask").tolist()
                if encoded.get("attention_mask") is not None else None,
            },
            handle,
            ensure_ascii=False,
            indent=2,
        )

    report = {
        "language": args.language,
        "reference_repo": repo_id,
        "text": text,
        "official_token_count": int(ids.shape[1]),
        "onnxruntime_version": ort.__version__,
        "outputs": {},
    }

    # Run the original FP32 reference model first and release it before loading ONNX.
    print("\nDownloading/loading FP32 reference checkpoint (first run can be large)...")
    torch.manual_seed(1234)
    reference_model = VitsModel.from_pretrained(
        repo_id,
        cache_dir=str(args.cache_dir),
    ).to("cpu").eval()
    sample_rate = int(getattr(reference_model.config, "sampling_rate", 16000))
    with torch.inference_mode():
        reference_output = reference_model(**encoded).waveform.detach().cpu().numpy().reshape(-1)
    ref_report = summarize(reference_output, sample_rate)
    ref_path = output_dir / "reference_fp32.wav"
    save_pcm16(ref_path, reference_output, sample_rate)
    report["outputs"]["reference_fp32"] = {**ref_report, "wav": str(ref_path)}
    print("FP32 reference:", json.dumps(ref_report, sort_keys=True))
    del reference_model, reference_output, encoded
    gc.collect()

    # Run the packaged ONNX checkpoint with the SAME authoritative token IDs.
    print("\nRunning the ONNX model from the ZIP with the same input IDs...")
    with zipfile.ZipFile(args.zip_path, "r") as archive:
        model_path = locate_entry(archive, args.language, "model.int8.mixed.onnx")
        model_bytes = archive.read(model_path)
        config_path = locate_entry(archive, args.language, "config.json")
        model_config = json.loads(archive.read(config_path).decode("utf-8"))
    onnx_options = ort.SessionOptions()
    onnx_options.intra_op_num_threads = 1
    onnx_options.inter_op_num_threads = 1
    session = ort.InferenceSession(
        model_bytes,
        sess_options=onnx_options,
        providers=["CPUExecutionProvider"],
    )
    inputs = session.get_inputs()
    if len(inputs) != 1 or inputs[0].type != "tensor(int64)":
        raise RuntimeError(
            "Expected one int64 ONNX input; got "
            + str([(item.name, item.type, item.shape) for item in inputs])
        )
    onnx_input = inputs[0]
    out_meta = session.get_outputs()
    audio_output = next((item for item in out_meta if item.name == "audio"), out_meta[0])
    torch.manual_seed(1234)
    onnx_output = session.run(
        [audio_output.name],
        {onnx_input.name: ids},
    )[0].astype(np.float32).reshape(-1)
    onnx_sample_rate = int(model_config.get("sample_rate", sample_rate))
    if onnx_sample_rate != sample_rate:
        print(
            f"WARNING: sample rates differ (FP32={sample_rate}, ONNX={onnx_sample_rate})"
        )
    onnx_report = summarize(onnx_output, onnx_sample_rate)
    onnx_path = output_dir / "bundled_mixed_int8_onnx.wav"
    save_pcm16(onnx_path, onnx_output, onnx_sample_rate)
    report["outputs"]["bundled_mixed_int8_onnx"] = {
        **onnx_report,
        "wav": str(onnx_path),
        "onnx_entry": model_path,
    }
    print("Bundled ONNX:", json.dumps(onnx_report, sort_keys=True))

    report_path = output_dir / "comparison.json"
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print("\nComparison files:")
    print(f"  FP32 reference WAV: {ref_path}")
    print(f"  Mixed-INT8 ONNX WAV: {onnx_path}")
    print(f"  Exact token IDs: {output_dir / 'official_token_ids.json'}")
    print(f"  Report: {report_path}")
    print("\nListen to both WAVs. The FP32 reference tells us whether the artifacts are already")
    print("present in the original checkpoint; the ONNX file tests the bundled quantized export.")
    print("Because VITS sampling can be stochastic, do not interpret raw waveform differences alone.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
