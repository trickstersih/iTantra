#!/usr/bin/env python3
"""Build a less aggressive all-weights INT8 Odia VITS ONNX candidate.

Replaces eligible floating-point weights used by Conv, ConvTranspose, MatMul,
Gemm, and Gather with symmetric signed INT8 weights plus DequantizeLinear nodes.
Activations are not quantized. Per-axis scales are used when unambiguous, with
operator-appropriate axes. The source model is never modified.

This is a desktop quality/size experiment, not an app-ready artifact.
"""
from __future__ import annotations

import argparse
import json
import math
import sys
import wave
from pathlib import Path
from typing import Any

import numpy as np
import onnx
from onnx import TensorProto, helper, numpy_helper

TOKENIZER_REPO = "facebook/mms-tts-ory"
DEFAULT_TEXT = "ନମସ୍କାର, ଏହା ଓଡ଼ିଆ ଧ୍ୱନି ପରୀକ୍ଷା।"
SAMPLE_RATE = 16000


def tensor_elements(tensor: TensorProto) -> int:
    return math.prod(int(d) for d in tensor.dims) if tensor.dims else 1


def node_attribute_int(node: onnx.NodeProto, name: str, default: int = 0) -> int:
    for attr in node.attribute:
        if attr.name == name:
            try:
                return int(helper.get_attribute_value(attr))
            except (TypeError, ValueError):
                return default
    return default


def collect_weight_roles(model: onnx.ModelProto) -> dict[str, list[dict[str, Any]]]:
    """Map eligible initializer names to uses and quantization axes."""
    init_by_name = {item.name: item for item in model.graph.initializer}
    roles: dict[str, list[dict[str, Any]]] = {}

    for node in model.graph.node:
        role: str | None = None
        weight_name: str | None = None
        axis: int | None = None

        if node.op_type == "Conv" and len(node.input) > 1:
            weight_name, axis, role = node.input[1], 0, "Conv"
        elif node.op_type == "ConvTranspose" and len(node.input) > 1:
            # ONNX ConvTranspose weights are [C_in, C_out/group, kernel...].
            weight_name, axis, role = node.input[1], 1, "ConvTranspose"
        elif node.op_type == "MatMul" and len(node.input) > 1:
            weight_name, role = node.input[1], "MatMul.B"
            tensor = init_by_name.get(weight_name)
            if tensor is None or len(tensor.dims) < 2:
                continue
            axis = len(tensor.dims) - 1
        elif node.op_type == "Gemm" and len(node.input) > 1:
            weight_name, role = node.input[1], "Gemm.B"
            tensor = init_by_name.get(weight_name)
            if tensor is None or len(tensor.dims) < 2:
                continue
            axis = 0 if node_attribute_int(node, "transB", 0) else 1
        elif node.op_type == "Gather" and node.input:
            weight_name, axis, role = node.input[0], 0, "Gather.embedding"
        else:
            continue

        tensor = init_by_name.get(weight_name)
        if tensor is None or tensor.data_type not in (TensorProto.FLOAT, TensorProto.FLOAT16):
            continue
        if len(tensor.dims) < 2:
            continue

        roles.setdefault(weight_name, []).append({
            "node": node.name or f"<{node.op_type}>",
            "role": role,
            "axis": int(axis),
        })

    return roles


def unique_name(base: str, occupied: set[str]) -> str:
    name = base
    suffix = 1
    while name in occupied:
        name = f"{base}_{suffix}"
        suffix += 1
    occupied.add(name)
    return name


def quantize_symmetric_int8(
    values: np.ndarray,
    axis: int | None,
) -> tuple[np.ndarray, np.ndarray, int | None]:
    """Symmetric signed INT8 weight quantization, using [-127, 127]."""
    source = np.asarray(values, dtype=np.float32)
    if not np.isfinite(source).all():
        raise ValueError("Cannot INT8-quantize a tensor containing NaN or infinity")

    if axis is None:
        max_abs = float(np.max(np.abs(source))) if source.size else 0.0
        scale = np.asarray(max_abs / 127.0 if max_abs > 0 else 1.0, dtype=np.float32)
        quantized = np.rint(source / scale).clip(-127, 127).astype(np.int8)
        return quantized, scale, None

    axis %= source.ndim
    reduce_axes = tuple(index for index in range(source.ndim) if index != axis)
    max_abs = np.max(np.abs(source), axis=reduce_axes) if reduce_axes else np.abs(source)
    scales = np.where(max_abs > 0, max_abs / 127.0, 1.0).astype(np.float32)
    broadcast_shape = [1] * source.ndim
    broadcast_shape[axis] = source.shape[axis]
    quantized = np.rint(
        source / scales.reshape(broadcast_shape)
    ).clip(-127, 127).astype(np.int8)
    return quantized, scales, axis


def quantize_weights_int8(
    model: onnx.ModelProto,
    min_elements: int,
) -> dict[str, Any]:
    roles = collect_weight_roles(model)
    initializers = {item.name: item for item in model.graph.initializer}
    occupied = {item.name for item in model.graph.initializer}
    for node in model.graph.node:
        occupied.update(name for name in node.input if name)
        occupied.update(name for name in node.output if name)

    added_initializers: list[TensorProto] = []
    added_nodes: list[onnx.NodeProto] = []
    replaced_names: set[str] = set()
    rows: list[dict[str, Any]] = []
    skipped: list[dict[str, Any]] = []
    total_original_bytes = 0
    total_int8_payload_and_scales = 0

    ordered_names = sorted(
        roles,
        key=lambda name: tensor_elements(initializers[name]),
        reverse=True,
    )

    for weight_name in ordered_names:
        tensor = initializers[weight_name]
        count = tensor_elements(tensor)
        if count < min_elements:
            skipped.append({
                "initializer": weight_name,
                "elements": count,
                "reason": "below min-elements",
            })
            continue

        uses = roles[weight_name]
        axes = {
            int(use["axis"]) % len(tensor.dims)
            for use in uses
            if use.get("axis") is not None
        }
        # A tensor shared by incompatible operator axes uses one scale for the
        # entire tensor; this avoids applying a scale axis incorrectly.
        axis: int | None = next(iter(axes)) if len(axes) == 1 else None

        original_array = np.asarray(numpy_helper.to_array(tensor))
        q_values, scales_f32, axis = quantize_symmetric_int8(original_array, axis)
        scale_dtype = np.float16 if original_array.dtype == np.float16 else np.float32
        scales = np.asarray(scales_f32, dtype=scale_dtype)

        scale_name = unique_name(f"{weight_name}__int8_scale", occupied)
        zero_name = unique_name(f"{weight_name}__int8_zero", occupied)
        quant_name = unique_name(f"{weight_name}__int8_values", occupied)
        dequant_node_name = unique_name(f"{weight_name}__int8_dequant", occupied)

        added_initializers.append(numpy_helper.from_array(scales, name=scale_name))
        added_initializers.append(
            numpy_helper.from_array(np.zeros(scales.shape, dtype=np.int8), name=zero_name)
        )
        # numpy_helper stores INT8 as raw bytes, avoiding expanded protobuf values.
        added_initializers.append(
            numpy_helper.from_array(np.asarray(q_values, dtype=np.int8), name=quant_name)
        )

        dequant_attrs = {} if axis is None else {"axis": axis}
        added_nodes.append(
            helper.make_node(
                "DequantizeLinear",
                inputs=[quant_name, scale_name, zero_name],
                outputs=[weight_name],
                name=dequant_node_name,
                **dequant_attrs,
            )
        )
        replaced_names.add(weight_name)

        original_bytes = len(tensor.raw_data) if tensor.raw_data else int(original_array.nbytes)
        quant_payload_bytes = int(q_values.size)
        scale_bytes = int(scales.nbytes)
        zero_bytes = int(scales.size)
        total_original_bytes += original_bytes
        total_int8_payload_and_scales += quant_payload_bytes + scale_bytes + zero_bytes

        rows.append({
            "initializer": weight_name,
            "shape": [int(d) for d in tensor.dims],
            "elements": count,
            "uses": uses,
            "granularity": "per_tensor" if axis is None else "per_axis",
            "axis": axis,
            "original_weight_bytes": original_bytes,
            "int8_payload_bytes": quant_payload_bytes,
            "scale_bytes": scale_bytes,
            "zero_point_bytes": zero_bytes,
            "estimated_savings_bytes": original_bytes - quant_payload_bytes - scale_bytes - zero_bytes,
        })

    if not rows:
        raise RuntimeError("No eligible floating-point weights met --min-elements")

    kept = [item for item in model.graph.initializer if item.name not in replaced_names]
    del model.graph.initializer[:]
    model.graph.initializer.extend(kept)
    model.graph.initializer.extend(added_initializers)

    original_nodes = list(model.graph.node)
    del model.graph.node[:]
    model.graph.node.extend(added_nodes)
    model.graph.node.extend(original_nodes)

    default_opset = next(
        (item for item in model.opset_import if item.domain in ("", "ai.onnx")),
        None,
    )
    if default_opset is None:
        raise RuntimeError("ONNX model has no default ai.onnx opset import")
    if default_opset.version < 13:
        print(f"Converting default ONNX opset {default_opset.version} to 13 for per-axis DequantizeLinear...")
        try:
            model = onnx.version_converter.convert_version(model, 13)
        except Exception as exc:
            raise RuntimeError(
                "ONNX version conversion to opset 13 failed; source model was not modified."
            ) from exc

    return {
        "quantized_tensor_count": len(rows),
        "quantized_original_weight_bytes": total_original_bytes,
        "int8_payload_and_scales_bytes": total_int8_payload_and_scales,
        "estimated_weight_bytes_saved": total_original_bytes - total_int8_payload_and_scales,
        "weights": rows,
        "skipped": skipped,
    }


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
    total = float(np.sum(spectrum**2)) + 1e-12
    high = float(np.sum(spectrum[frequencies >= min(4000.0, sample_rate * 0.25)]**2))
    return {
        "samples": int(audio.size),
        "duration_seconds": round(audio.size / sample_rate, 3),
        "sample_rate": sample_rate,
        "peak": round(float(np.max(np.abs(audio))), 6),
        "rms": round(float(np.sqrt(np.mean(centered**2))), 6),
        "clipped_sample_percent": round(float(np.mean(np.abs(audio) >= 0.999) * 100), 5),
        "energy_above_4khz_percent": round(100.0 * high / total, 3),
    }


def run_model(session, input_name: str, output_name: str, ids: np.ndarray) -> np.ndarray:
    output = session.run([output_name], {input_name: ids})[0]
    audio = np.asarray(output, dtype=np.float32).reshape(-1)
    if audio.size == 0 or not np.isfinite(audio).all():
        raise RuntimeError("ONNX Runtime returned empty or non-finite audio")
    return audio


def open_session(path: Path, ort):
    options = ort.SessionOptions()
    options.intra_op_num_threads = 1
    options.inter_op_num_threads = 1
    session = ort.InferenceSession(
        str(path), sess_options=options, providers=["CPUExecutionProvider"]
    )
    inputs = session.get_inputs()
    if len(inputs) != 1 or inputs[0].type != "tensor(int64)":
        raise RuntimeError(
            f"Expected one int64 token input in {path}; got "
            f"{[(item.name, item.type, item.shape) for item in inputs]}"
        )
    outputs = session.get_outputs()
    output = next((item for item in outputs if item.name == "audio"), outputs[0])
    print(f"{path.name}: input={inputs[0].name} {inputs[0].shape}; output={output.name}")
    return session, inputs[0].name, output.name


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input-model", type=Path, required=True,
                        help="Known-clear MatMul-only INT8 ONNX model; never overwritten")
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--cache-dir", type=Path, default=Path("build/tts/odia_hf_cache"))
    parser.add_argument("--text", default=DEFAULT_TEXT)
    parser.add_argument("--min-elements", type=int, default=32)
    args = parser.parse_args()

    if not args.input_model.is_file():
        parser.error(f"Input model not found: {args.input_model}")
    if args.min_elements < 1:
        parser.error("--min-elements must be positive")
    args.output_dir.mkdir(parents=True, exist_ok=True)
    candidate_path = args.output_dir / "model.odia.all_weights.int8.onnx"
    base_wav = args.output_dir / "base_matmul_int8.wav"
    candidate_wav = args.output_dir / "all_weights_int8_candidate.wav"
    report_path = args.output_dir / "all_weights_int8_report.json"
    existing = [path for path in (candidate_path, base_wav, candidate_wav, report_path) if path.exists()]
    if existing:
        parser.error(f"Refusing to overwrite existing result: {existing[0]}")
    args.cache_dir.mkdir(parents=True, exist_ok=True)

    try:
        import onnxruntime as ort
        from transformers import AutoTokenizer
    except ImportError as exc:
        print(
            f"Missing dependency: {exc}\n"
            "Install with: python -m pip install numpy onnx onnxruntime transformers",
            file=sys.stderr,
        )
        return 3

    print("ONNX Runtime:", ort.__version__)
    print("Loading input model...")
    model = onnx.load(str(args.input_model), load_external_data=True)
    print("Original opset(s):", [(item.domain or "ai.onnx", int(item.version)) for item in model.opset_import])

    print("Quantizing eligible floating-point weights to symmetric INT8 (weight-only)...")
    quant_report = quantize_weights_int8(model, args.min_elements)
    print("Quantized tensors:", quant_report["quantized_tensor_count"])
    print(f"Original eligible weights: {quant_report['quantized_original_weight_bytes'] / (1024*1024):.2f} MiB")
    print(f"Estimated INT8 payload + scales: {quant_report['int8_payload_and_scales_bytes'] / (1024*1024):.2f} MiB")
    print(f"Estimated savings in replaced weights: {quant_report['estimated_weight_bytes_saved'] / (1024*1024):.2f} MiB")

    print("Checking graph and writing candidate...")
    onnx.checker.check_model(model)
    onnx.save_model(model, str(candidate_path))
    print(f"Candidate size: {candidate_path.stat().st_size / (1024*1024):.2f} MiB")

    print("Loading official MMS Odia tokenizer...")
    tokenizer = AutoTokenizer.from_pretrained(
        TOKENIZER_REPO, cache_dir=str(args.cache_dir), trust_remote_code=False
    )
    ids = np.asarray(tokenizer(args.text, return_tensors="np")["input_ids"], dtype=np.int64)
    print("Text:", args.text)
    print("Token count:", ids.shape[1])

    print("\nLoading baseline model...")
    base_session, base_input, base_output = open_session(args.input_model, ort)
    print("Loading INT8 candidate (desktop runtime compatibility check)...")
    candidate_session, candidate_input, candidate_output = open_session(candidate_path, ort)

    print("\nGenerating baseline WAV...")
    base_audio = run_model(base_session, base_input, base_output, ids)
    base_stats = audio_summary(base_audio)
    save_wav(base_wav, base_audio)

    print("Generating all-weights INT8 candidate WAV...")
    candidate_audio = run_model(candidate_session, candidate_input, candidate_output, ids)
    candidate_stats = audio_summary(candidate_audio)
    save_wav(candidate_wav, candidate_audio)

    report = {
        "input_model": str(args.input_model),
        "input_model_bytes": args.input_model.stat().st_size,
        "candidate_model": str(candidate_path),
        "candidate_model_bytes": candidate_path.stat().st_size,
        "quantization": "symmetric signed INT8 weight-only with DequantizeLinear; activations left unchanged",
        "min_elements": args.min_elements,
        **quant_report,
        "listening_text": args.text,
        "outputs": {
            "baseline_matmul_int8": {**base_stats, "wav": str(base_wav)},
            "all_weights_int8_candidate": {**candidate_stats, "wav": str(candidate_wav)},
        },
        "note": "Listen before using the multi-sentence test suite. Desktop inference does not guarantee Android runtime compatibility.",
    }
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print("\nRESULTS")
    print(f"  Baseline size: {args.input_model.stat().st_size / (1024*1024):.2f} MiB")
    print(f"  INT8 size:     {candidate_path.stat().st_size / (1024*1024):.2f} MiB")
    print(f"  Baseline WAV:  {base_wav}")
    print(f"  INT8 WAV:      {candidate_wav}")
    print(f"  Report:        {report_path}")
    print("No app source, app assets, or model ZIPs were changed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
