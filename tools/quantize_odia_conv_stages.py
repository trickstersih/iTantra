#!/usr/bin/env python3
"""Quantize only the N largest eligible Conv nodes in the Odia MatMul-INT8 model.

Conv activations use static QDQ INT8 calibration; Conv weights use per-channel
INT8. ConvTranspose remains FP32. The input candidate is never overwritten.
The script emits candidate WAVs and size/statistics reports for listening tests.
"""
from __future__ import annotations

import argparse
import json
import math
import sys
import wave
from pathlib import Path

import numpy as np
import onnx

SAMPLE_TEXTS = [
    "ନମସ୍କାର, ଏହା ଓଡ଼ିଆ ଧ୍ୱନି ପରୀକ୍ଷା।",
    "ଆପଣ କେମିତି ଅଛନ୍ତି?",
    "ଏହା ଏକ ପରୀକ୍ଷା ବାର୍ତ୍ତା।",
    "ଦୟାକରି ତୁରନ୍ତ ପଛକୁ ଫେରିଯାଆନ୍ତୁ।",
    "ମୋତେ ସାହାଯ୍ୟ ଦରକାର।",
    "ଦୟାକରି ପାଣି ଆଣନ୍ତୁ।",
    "ଆମେ ଏବେ ସୁରକ୍ଷିତ ସ୍ଥାନକୁ ଯାଉଛୁ।",
    "ଦଳ ଆଗକୁ ବଢ଼ୁଛି, ସମସ୍ତେ ସତର୍କ ରୁହନ୍ତୁ।",
    "ଆଜି ପାଗ ବହୁତ ଭଲ ଅଛି।",
    "ସଂଯୋଗ ନଥିଲେ ମଧ୍ୟ ଏହି ବାର୍ତ୍ତା ଚାଲିବ।",
    "ଦୟାକରି ଶୀଘ୍ର ଉତ୍ତର ଦିଅନ୍ତୁ।",
    "ଆମେ କାଲି ସକାଳେ ପୁଣି ଦେଖା ହେବା।",
]
DEFAULT_TEXT = SAMPLE_TEXTS[0]
TOKENIZER_REPO = "facebook/mms-tts-ory"


def tensor_nbytes(tensor) -> int:
    if tensor.raw_data:
        return len(tensor.raw_data)
    sizes = {
        onnx.TensorProto.FLOAT: 4,
        onnx.TensorProto.FLOAT16: 2,
        onnx.TensorProto.BFLOAT16: 2,
        onnx.TensorProto.DOUBLE: 8,
        onnx.TensorProto.INT8: 1,
        onnx.TensorProto.UINT8: 1,
        onnx.TensorProto.INT16: 2,
        onnx.TensorProto.UINT16: 2,
        onnx.TensorProto.INT32: 4,
        onnx.TensorProto.UINT32: 4,
        onnx.TensorProto.INT64: 8,
        onnx.TensorProto.UINT64: 8,
    }
    count = math.prod(int(d) for d in tensor.dims) if tensor.dims else 1
    return count * sizes.get(tensor.data_type, 0)


def name_and_rank_conv_nodes(model: onnx.ModelProto):
    used_names = {node.name for node in model.graph.node if node.name}
    for index, node in enumerate(model.graph.node):
        if node.op_type == "Conv" and not node.name:
            candidate = f"ODIA_AUTO_CONV_{index:04d}"
            suffix = 1
            while candidate in used_names:
                candidate = f"ODIA_AUTO_CONV_{index:04d}_{suffix}"
                suffix += 1
            node.name = candidate
            used_names.add(candidate)

    initializers = {item.name: item for item in model.graph.initializer}
    rows = []
    skipped = []
    for index, node in enumerate(model.graph.node):
        if node.op_type != "Conv":
            continue
        if len(node.input) < 2 or node.input[1] not in initializers:
            skipped.append(node.name or f"<Conv index {index}>")
            continue
        tensor = initializers[node.input[1]]
        rows.append({
            "name": node.name,
            "graph_index": index,
            "weight": tensor.name,
            "shape": [int(d) for d in tensor.dims],
            "bytes": tensor_nbytes(tensor),
        })
    rows.sort(key=lambda row: row["bytes"], reverse=True)
    return rows, skipped


def save_wav(path: Path, samples: np.ndarray, sample_rate: int) -> None:
    safe = np.nan_to_num(
        np.asarray(samples, dtype=np.float32).reshape(-1),
        nan=0.0,
        posinf=0.0,
        neginf=0.0,
    )
    pcm = (np.clip(safe, -1.0, 1.0) * 32767.0).astype("<i2", copy=False)
    with wave.open(str(path), "wb") as wav:
        wav.setnchannels(1)
        wav.setsampwidth(2)
        wav.setframerate(sample_rate)
        wav.writeframes(pcm.tobytes())


def audio_summary(samples: np.ndarray, sample_rate: int) -> dict:
    x = np.asarray(samples, dtype=np.float64).reshape(-1)
    if x.size == 0:
        raise ValueError("Empty waveform")
    if not np.isfinite(x).all():
        raise ValueError("Waveform contains NaN or infinity")
    centered = x - x.mean()
    spectrum = np.abs(np.fft.rfft(centered))
    frequencies = np.fft.rfftfreq(x.size, d=1 / sample_rate)
    total = float(np.sum(spectrum ** 2)) + 1e-12
    high = float(np.sum(spectrum[frequencies >= min(4000.0, sample_rate * 0.25)] ** 2))
    return {
        "samples": int(x.size),
        "duration_seconds": round(x.size / sample_rate, 3),
        "sample_rate": sample_rate,
        "peak": round(float(np.max(np.abs(x))), 6),
        "rms": round(float(np.sqrt(np.mean(centered ** 2))), 6),
        "clipped_sample_percent": round(float(np.mean(np.abs(x) >= 0.999) * 100), 5),
        "energy_above_4khz_percent": round(100.0 * high / total, 3),
    }


class OdiaCalibrationReader:
    """Compatible with ONNX Runtime's CalibrationDataReader duck-typed API."""

    def __init__(self, tokenizer, input_name: str, texts: list[str]):
        self.samples = []
        for sentence in texts:
            encoded = tokenizer(sentence, return_tensors="np")
            ids = np.asarray(encoded["input_ids"], dtype=np.int64)
            if ids.ndim != 2 or ids.shape[0] != 1:
                raise ValueError(f"Unexpected token array shape {ids.shape} for: {sentence}")
            self.samples.append({input_name: ids})
        self.index = 0

    def get_next(self):
        if self.index >= len(self.samples):
            return None
        sample = self.samples[self.index]
        self.index += 1
        return sample

    def rewind(self):
        self.index = 0


def run_model(path: str | Path, ids: np.ndarray, ort):
    options = ort.SessionOptions()
    options.intra_op_num_threads = 1
    options.inter_op_num_threads = 1
    session = ort.InferenceSession(
        str(path), sess_options=options, providers=["CPUExecutionProvider"]
    )
    inputs = session.get_inputs()
    if len(inputs) != 1 or inputs[0].type != "tensor(int64)":
        raise RuntimeError(
            "Expected one int64 token input; got "
            + repr([(x.name, x.type, x.shape) for x in inputs])
        )
    input_meta = inputs[0]
    outputs = session.get_outputs()
    output_meta = next((item for item in outputs if item.name == "audio"), outputs[0])
    print(f"Runtime input={input_meta.name} {input_meta.shape}; output={output_meta.name}")
    samples = session.run([output_meta.name], {input_meta.name: ids})[0]
    return np.asarray(samples, dtype=np.float32).reshape(-1)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--input-model",
        type=Path,
        required=True,
        help="Previously tested model.matmul.int8.onnx; never overwritten",
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        required=True,
        help="New directory for prepared graph, candidate ONNX, WAVs and report",
    )
    parser.add_argument(
        "--ranks",
        default="1",
        help="Comma-separated 1-based Conv ranks by descending weight size, e.g. '1', '2', or '1,2' (default: 1)",
    )
    parser.add_argument(
        "--cache-dir",
        type=Path,
        default=Path("build/tts/odia_onnx_cache"),
    )
    parser.add_argument(
        "--text",
        default=DEFAULT_TEXT,
        help="Sentence used to synthesize the base and quantized-candidate WAVs",
    )
    args = parser.parse_args()

    if not args.input_model.is_file():
        parser.error(f"Input model not found: {args.input_model}")
    try:
        ranks = sorted(set(int(part.strip()) for part in args.ranks.split(",") if part.strip()))
    except ValueError:
        parser.error("--ranks must be comma-separated positive integers, e.g. 1 or 1,3")
    if not ranks or min(ranks) < 1:
        parser.error("--ranks must contain one or more positive integers")
    args.output_dir.mkdir(parents=True, exist_ok=True)
    args.cache_dir.mkdir(parents=True, exist_ok=True)

    prepared_model_path = args.output_dir / "prepared_named_matmul_int8.onnx"
    rank_tag = "-".join(f"{rank:02d}" for rank in ranks)
    candidate_path = args.output_dir / f"model.matmul.int8.conv_ranks_{rank_tag}.qdq.onnx"
    base_wav = args.output_dir / "base_matmul_int8.wav"
    candidate_wav = args.output_dir / f"conv_ranks_{rank_tag}_candidate.wav"
    report_path = args.output_dir / f"conv_ranks_{rank_tag}_report.json"
    for path in (candidate_path, candidate_wav, report_path):
        if path.exists():
            parser.error(f"Refusing to overwrite existing result: {path}")

    try:
        import onnxruntime as ort
        from huggingface_hub import hf_hub_download
        from onnxruntime.quantization import (
            CalibrationDataReader,
            CalibrationMethod,
            QuantFormat,
            QuantType,
            quantize_static,
        )
        from transformers import AutoTokenizer
    except ImportError as exc:
        print(
            f"Missing dependency: {exc}\n"
            "Install with: python -m pip install numpy onnx onnxruntime transformers huggingface_hub",
            file=sys.stderr,
        )
        return 3

    print("ONNX Runtime:", ort.__version__)
    print("Loading candidate and finding Conv weights...")
    model = onnx.load(str(args.input_model), load_external_data=True)
    conv_rows, skipped = name_and_rank_conv_nodes(model)
    if not conv_rows:
        raise RuntimeError("No initializer-backed Conv nodes found in the input model")
    if max(ranks) > len(conv_rows):
        parser.error(
            f"Requested rank {max(ranks)}, but only {len(conv_rows)} "
            "initializer-backed Conv nodes can be ranked"
        )
    selected = [conv_rows[rank - 1] for rank in ranks]
    selected_names = [row["name"] for row in selected]
    onnx.save_model(model, str(prepared_model_path))
    del model

    print(f"Input model size: {args.input_model.stat().st_size / (1024 * 1024):.2f} MiB")
    print(f"Rankable Conv nodes: {len(conv_rows)}; skipped: {len(skipped)}")
    print("Selected Conv weights (largest first):")
    for rank, row in enumerate(selected, 1):
        shape = "x".join(str(dim) for dim in row["shape"])
        print(
            f"  {rank}. {row['name']} | weight {shape} | "
            f"{row['bytes'] / (1024 * 1024):.3f} MiB"
        )
    if skipped:
        print("Skipped nodes with non-initializer weights:", skipped)

    print("\nLoading official MMS Odia tokenizer...")
    tokenizer = AutoTokenizer.from_pretrained(
        TOKENIZER_REPO, cache_dir=str(args.cache_dir), trust_remote_code=False
    )
    import warnings
    warnings.filterwarnings("ignore", category=DeprecationWarning)

    print("Creating calibration input IDs from representative Odia sentences...")
    calibration_reader = OdiaCalibrationReader(
        tokenizer, "x", SAMPLE_TEXTS
    )

    print(
        f"\nStatic QDQ INT8 quantization of {len(selected_names)} selected Conv node(s); "
        "all other Conv and all ConvTranspose nodes remain unquantized..."
    )
    quantize_static(
        model_input=str(prepared_model_path),
        model_output=str(candidate_path),
        calibration_data_reader=calibration_reader,
        quant_format=QuantFormat.QDQ,
        activation_type=QuantType.QInt8,
        weight_type=QuantType.QInt8,
        op_types_to_quantize=["Conv"],
        nodes_to_quantize=selected_names,
        per_channel=True,
        reduce_range=False,
        calibrate_method=CalibrationMethod.MinMax,
        extra_options={
            "ActivationSymmetric": True,
            "WeightSymmetric": True,
        },
    )

    print("Checking generated model...")
    onnx.checker.check_model(str(candidate_path))
    check_session = ort.InferenceSession(
        str(candidate_path), providers=["CPUExecutionProvider"]
    )
    print("Candidate model loads in desktop ONNX Runtime.")
    del check_session

    print("\nLoading official tokenizer IDs for the listening sentence...")
    encoded = tokenizer(args.text, return_tensors="np")
    ids = np.asarray(encoded["input_ids"], dtype=np.int64)
    print(f"Text: {args.text}")
    print(f"Input token count: {ids.shape[1]}")

    print("\nGenerating base MatMul-only INT8 WAV...")
    base_audio = run_model(args.input_model, ids, ort)
    base_stats = audio_summary(base_audio, 16000)
    save_wav(base_wav, base_audio, 16000)
    del base_audio

    print("\nGenerating selective Conv-INT8 candidate WAV...")
    candidate_audio = run_model(candidate_path, ids, ort)
    candidate_stats = audio_summary(candidate_audio, 16000)
    save_wav(candidate_wav, candidate_audio, 16000)

    report = {
        "input_model": str(args.input_model),
        "input_model_bytes": args.input_model.stat().st_size,
        "candidate_model": str(candidate_path),
        "candidate_model_bytes": candidate_path.stat().st_size,
        "selected_conv_ranks": ranks
        "quantized_conv_nodes": selected,
        "skipped_conv_nodes": skipped,
        "quantization": {
            "format": "QDQ",
            "activation_type": "QInt8",
            "weight_type": "QInt8",
            "per_channel": True,
            "calibration": "MinMax over 12 representative Odia sentences",
            "ConvTranspose": "left unquantized",
        },
        "listening_text": args.text,
        "outputs": {
            "base_matmul_int8": {
                **base_stats,
                "wav": str(base_wav),
            },
            "selected_conv_int8": {
                **candidate_stats,
                "wav": str(candidate_wav),
            },
        },
    }
    report_path.write_text(
        json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8"
    )

    print("\nResults:")
    print(f"  Base size:      {args.input_model.stat().st_size / (1024 * 1024):.2f} MiB")
    print(f"  Candidate size: {candidate_path.stat().st_size / (1024 * 1024):.2f} MiB")
    print(f"  Base WAV:       {base_wav}")
    print(f"  Candidate WAV:  {candidate_wav}")
    print(f"  Report:         {report_path}")
    print("\nListen to both WAVs before adding more Conv ranks. No app files or ZIPs were changed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
