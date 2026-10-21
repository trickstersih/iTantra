#!/usr/bin/env python3
"""Generate a multi-sentence listening suite for the Odia all-weights INT4 ONNX model.

Generates paired WAVs from a known-clear MatMul-INT8 baseline and the candidate
INT4 model using identical official MMS Odia tokenizer IDs. This is a listening
test only; it does not change app assets or package a model.
"""
from __future__ import annotations

import argparse
import json
import sys
import wave
from pathlib import Path
from typing import Any

import numpy as np

TOKENIZER_REPO = "facebook/mms-tts-ory"
SAMPLE_RATE = 16000

TEST_CASES: list[dict[str, str]] = [
    {
        "name": "01_greeting",
        "category": "short greeting",
        "text": "ନମସ୍କାର, ଆପଣ କେମିତି ଅଛନ୍ତି?",
    },
    {
        "name": "02_general_test",
        "category": "ordinary statement",
        "text": "ଏହା ଓଡ଼ିଆ ଭାଷାରେ ଏକ ସାଧାରଣ ପରୀକ୍ଷା ବାର୍ତ୍ତା।",
    },
    {
        "name": "03_short_command",
        "category": "short command",
        "text": "ଦୟାକରି ଧ୍ୟାନ ଦିଅନ୍ତୁ।",
    },
    {
        "name": "04_emergency",
        "category": "urgent alert",
        "text": "ଜରୁରୀ ସୂଚନା: ସମସ୍ତେ ତୁରନ୍ତ ସୁରକ୍ଷିତ ସ୍ଥାନକୁ ଯାଆନ୍ତୁ।",
    },
    {
        "name": "05_help",
        "category": "help request",
        "text": "ମୋତେ ସାହାଯ୍ୟ ଦରକାର।",
    },
    {
        "name": "06_question",
        "category": "question intonation",
        "text": "ଆପଣ ମୋ କଥା ଶୁଣିପାରୁଛନ୍ତି କି?",
    },
    {
        "name": "07_repeat_request",
        "category": "longer command",
        "text": "ଦୟାକରି ଏହି ବାର୍ତ୍ତାକୁ ପୁଣିଥରେ କହନ୍ତୁ।",
    },
    {
        "name": "08_squad_instruction",
        "category": "group instruction",
        "text": "ସମସ୍ତ ସଦସ୍ୟ ନିଜ ଦଳ ସହିତ ରୁହନ୍ତୁ।",
    },
    {
        "name": "09_supplies",
        "category": "everyday instruction",
        "text": "ପାଣି ଏବଂ ଔଷଧ ନେଇ ଆସନ୍ତୁ।",
    },
    {
        "name": "10_reassurance",
        "category": "longer sentence",
        "text": "ଆମେ ଏବେ ସୁରକ୍ଷିତ ସ୍ଥାନକୁ ଯାଉଛୁ, ସମସ୍ତେ ଶାନ୍ତ ରୁହନ୍ତୁ।",
    },
    {
        "name": "11_connection_warning",
        "category": "technical-style message",
        "text": "ସଂଯୋଗ ଦୁର୍ବଳ ଅଛି, ଦୟାକରି ନିକଟକୁ ଆସନ୍ତୁ।",
    },
    {
        "name": "12_long_instruction",
        "category": "long sentence",
        "text": "ଯଦି ଆପଣଙ୍କର ସଂଯୋଗ ବିଚ୍ଛିନ୍ନ ହୋଇଯାଏ, ତେବେ ନିକଟସ୍ଥ ସଦସ୍ୟଙ୍କ ସହିତ ଯୋଗାଯୋଗ କରନ୍ତୁ।",
    },
    {
        "name": "13_delivery_confirmation",
        "category": "message status",
        "text": "ବାର୍ତ୍ତା ପଠାଯାଇଛି। ଦୟାକରି ପ୍ରାପ୍ତି ନିଶ୍ଚିତ କରନ୍ତୁ।",
    },
    {
        "name": "14_natural_statement",
        "category": "multi-clause statement",
        "text": "ଆଜି ପାଗ ବହୁତ ଭଲ ଅଛି ଏବଂ ଆମେ ଯାତ୍ରା ଆରମ୍ଭ କରିବୁ।",
    },
    {
        "name": "15_safety_reminder",
        "category": "safety instruction",
        "text": "ସତର୍କ ରୁହନ୍ତୁ, ଏବଂ ଦଳରୁ ଅଲଗା ହୁଅନ୍ତୁ ନାହିଁ।",
    },
]


def save_wav(path: Path, samples: np.ndarray, sample_rate: int = SAMPLE_RATE) -> None:
    audio = np.asarray(samples, dtype=np.float32).reshape(-1)
    if audio.size == 0 or not np.isfinite(audio).all():
        raise ValueError(f"Refusing to write empty/non-finite audio: {path}")
    pcm = (np.clip(audio, -1.0, 1.0) * 32767.0).astype("<i2", copy=False)
    with wave.open(str(path), "wb") as wav:
        wav.setnchannels(1)
        wav.setsampwidth(2)
        wav.setframerate(sample_rate)
        wav.writeframes(pcm.tobytes())


def audio_summary(samples: np.ndarray, sample_rate: int = SAMPLE_RATE) -> dict[str, Any]:
    audio = np.asarray(samples, dtype=np.float64).reshape(-1)
    if audio.size == 0 or not np.isfinite(audio).all():
        raise ValueError("Model returned an empty or non-finite waveform")
    centered = audio - audio.mean()
    spectrum = np.abs(np.fft.rfft(centered))
    frequencies = np.fft.rfftfreq(audio.size, d=1.0 / sample_rate)
    total_energy = float(np.sum(spectrum**2)) + 1e-12
    high_energy = float(
        np.sum(spectrum[frequencies >= min(4000.0, sample_rate * 0.25)] ** 2)
    )
    return {
        "samples": int(audio.size),
        "duration_seconds": round(audio.size / sample_rate, 3),
        "sample_rate": sample_rate,
        "peak": round(float(np.max(np.abs(audio))), 6),
        "rms": round(float(np.sqrt(np.mean(centered**2))), 6),
        "clipped_sample_percent": round(
            float(np.mean(np.abs(audio) >= 0.999) * 100.0), 5
        ),
        "energy_above_4khz_percent": round(100.0 * high_energy / total_energy, 3),
    }


def open_session(model_path: Path, ort):
    options = ort.SessionOptions()
    options.intra_op_num_threads = 1
    options.inter_op_num_threads = 1
    session = ort.InferenceSession(
        str(model_path),
        sess_options=options,
        providers=["CPUExecutionProvider"],
    )
    inputs = session.get_inputs()
    if len(inputs) != 1 or inputs[0].type != "tensor(int64)":
        raise RuntimeError(
            f"{model_path} must have one int64 input; found "
            f"{[(x.name, x.type, x.shape) for x in inputs]}"
        )
    outputs = session.get_outputs()
    output_meta = next((item for item in outputs if item.name == "audio"), outputs[0])
    print(
        f"Loaded {model_path.name}: input={inputs[0].name} {inputs[0].shape}; "
        f"output={output_meta.name}"
    )
    return session, inputs[0].name, output_meta.name


def synthesize(session, input_name: str, output_name: str, token_ids: np.ndarray) -> np.ndarray:
    output = session.run([output_name], {input_name: token_ids})[0]
    audio = np.asarray(output, dtype=np.float32).reshape(-1)
    if audio.size == 0 or not np.isfinite(audio).all():
        raise RuntimeError("ONNX Runtime returned empty or non-finite audio")
    return audio


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--baseline-model",
        type=Path,
        required=True,
        help="Known-clear MatMul-only INT8 ONNX model",
    )
    parser.add_argument(
        "--candidate-model",
        type=Path,
        required=True,
        help="16 MiB all-weights INT4 candidate model",
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        required=True,
        help="Directory for paired baseline/candidate WAVs and the JSON report",
    )
    parser.add_argument(
        "--cache-dir",
        type=Path,
        default=Path("build/tts/odia_hf_cache"),
        help="Hugging Face tokenizer cache",
    )
    args = parser.parse_args()

    for label, path in (
        ("baseline model", args.baseline_model),
        ("candidate model", args.candidate_model),
    ):
        if not path.is_file():
            parser.error(f"{label} not found: {path}")
    if args.baseline_model.resolve() == args.candidate_model.resolve():
        parser.error("Baseline and candidate model paths must be different")

    args.output_dir.mkdir(parents=True, exist_ok=True)
    baseline_dir = args.output_dir / "baseline"
    candidate_dir = args.output_dir / "int4_candidate"
    report_path = args.output_dir / "odia_int4_multi_sentence_report.json"
    intended_outputs = [report_path]
    for case in TEST_CASES:
        intended_outputs.extend(
            [
                baseline_dir / f"{case['name']}.wav",
                candidate_dir / f"{case['name']}.wav",
            ]
        )
    existing = [path for path in intended_outputs if path.exists()]
    if existing:
        parser.error(
            "Refusing to overwrite existing test outputs. Choose a new --output-dir. "
            f"First existing path: {existing[0]}"
        )
    baseline_dir.mkdir(parents=True, exist_ok=True)
    candidate_dir.mkdir(parents=True, exist_ok=True)
    args.cache_dir.mkdir(parents=True, exist_ok=True)

    try:
        import onnxruntime as ort
        from transformers import AutoTokenizer
    except ImportError as exc:
        print(
            f"Missing dependency: {exc}\n"
            "Install with: python -m pip install numpy onnxruntime transformers",
            file=sys.stderr,
        )
        return 3

    print("ONNX Runtime:", ort.__version__)
    print("Loading official tokenizer:", TOKENIZER_REPO)
    tokenizer = AutoTokenizer.from_pretrained(
        TOKENIZER_REPO,
        cache_dir=str(args.cache_dir),
        trust_remote_code=False,
    )

    print("\nLoading both ONNX models...")
    baseline_session, baseline_input, baseline_output = open_session(
        args.baseline_model, ort
    )
    candidate_session, candidate_input, candidate_output = open_session(
        args.candidate_model, ort
    )

    report: dict[str, Any] = {
        "tokenizer_repo": TOKENIZER_REPO,
        "onnxruntime_version": ort.__version__,
        "sample_rate": SAMPLE_RATE,
        "baseline_model": str(args.baseline_model),
        "baseline_model_bytes": args.baseline_model.stat().st_size,
        "candidate_model": str(args.candidate_model),
        "candidate_model_bytes": args.candidate_model.stat().st_size,
        "test_case_count": len(TEST_CASES),
        "tests": [],
        "note": (
            "Statistics are diagnostics only; listen to the paired WAVs. "
            "Desktop inference does not prove Android runtime compatibility."
        ),
    }

    print(f"\nGenerating {len(TEST_CASES)} baseline/candidate WAV pairs...")
    for index, case in enumerate(TEST_CASES, start=1):
        text = case["text"]
        encoded = tokenizer(text, return_tensors="np")
        token_ids = np.asarray(encoded["input_ids"], dtype=np.int64)
        if token_ids.ndim != 2 or token_ids.shape[0] != 1:
            raise RuntimeError(
                f"Unexpected tokenizer output shape {token_ids.shape} for {case['name']}"
            )

        print(f"\n[{index:02d}/{len(TEST_CASES)}] {case['name']} ({case['category']})")
        print("Text:", text)
        print("Token count:", token_ids.shape[1])

        baseline_audio = synthesize(
            baseline_session, baseline_input, baseline_output, token_ids
        )
        candidate_audio = synthesize(
            candidate_session, candidate_input, candidate_output, token_ids
        )

        baseline_wav = baseline_dir / f"{case['name']}.wav"
        candidate_wav = candidate_dir / f"{case['name']}.wav"
        save_wav(baseline_wav, baseline_audio)
        save_wav(candidate_wav, candidate_audio)

        baseline_stats = audio_summary(baseline_audio)
        candidate_stats = audio_summary(candidate_audio)
        print(
            f"  baseline: {baseline_stats['duration_seconds']:.2f}s, "
            f"RMS={baseline_stats['rms']:.4f}"
        )
        print(
            f"  INT4:     {candidate_stats['duration_seconds']:.2f}s, "
            f"RMS={candidate_stats['rms']:.4f}"
        )

        report["tests"].append(
            {
                **case,
                "token_count": int(token_ids.shape[1]),
                "baseline": {
                    **baseline_stats,
                    "wav": str(baseline_wav),
                },
                "int4_candidate": {
                    **candidate_stats,
                    "wav": str(candidate_wav),
                },
            }
        )

    report_path.write_text(
        json.dumps(report, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    print("\nALL TEST WAV PAIRS GENERATED")
    print("Baseline WAVs: ", baseline_dir)
    print("INT4 WAVs:     ", candidate_dir)
    print("JSON report:   ", report_path)
    print("\nListen to each candidate against the matching baseline.")
    print("No app source, app assets, or model ZIPs were changed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
