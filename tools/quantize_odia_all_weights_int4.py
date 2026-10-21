#!/usr/bin/env python3
"""Aggressive weight-only INT4 test for the Odia VITS ONNX model.

Quantizes large floating-point initializer tensors used as weights by Conv,
ConvTranspose, MatMul, Gemm, or Gather (embedding) nodes. INT4 values are stored
packed in the ONNX file; DequantizeLinear nodes restore floating-point weights
at runtime. Activations are not quantized. The input model is never modified.
The script validates the graph and creates baseline/candidate WAVs.

Requires ONNX opset 21+ support for INT4 DequantizeLinear in the target runtime.
This is an aggressive quality experiment, not an app-ready model.
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


def tensor_elements(tensor: TensorProto) -> int:
    return math.prod(int(d) for d in tensor.dims) if tensor.dims else 1


def attr_int(node: onnx.NodeProto, name: str, default: int = 0) -> int:
    for attr in node.attribute:
        if attr.name == name:
            try:
                return int(helper.get_attribute_value(attr))
            except (TypeError, ValueError):
                return default
    return default


def collect_weight_roles(model: onnx.ModelProto) -> dict[str, list[dict[str, Any]]]:
    """Map eligible floating initializer names to operator roles and axes."""
    init_by_name = {t.name: t for t in model.graph.initializer}
    roles: dict[str, list[dict[str, Any]]] = {}
    for node in model.graph.node:
        name = node.name or f"<{node.op_type}>"
        if node.op_type in ("Conv", "ConvTranspose") and len(node.input) > 1:
            weight_name = node.input[1]
            axis = 0
            role = node.op_type
        elif node.op_type == "MatMul" and len(node.input) > 1:
            weight_name = node.input[1]
            tensor = init_by_name.get(weight_name)
            if tensor is None or len(tensor.dims) < 2:
                continue
            axis = len(tensor.dims) - 1
            role = "MatMul.B"
        elif node.op_type == "Gemm" and len(node.input) > 1:
            weight_name = node.input[1]
            tensor = init_by_name.get(weight_name)
            if tensor is None or len(tensor.dims) < 2:
                continue
            axis = 0 if attr_int(node, "transB", 0) else 1
            role = "Gemm.B"
        elif node.op_type == "Gather" and node.input:
            weight_name = node.input[0]
            tensor = init_by_name.get(weight_name)
            if tensor is None or len(tensor.dims) < 2:
                continue
            axis = 0
            role = "Gather.embedding"
        else:
            continue

        tensor = init_by_name.get(weight_name)
        if tensor is None or tensor.data_type not in (TensorProto.FLOAT, TensorProto.FLOAT16):
            continue
        if len(tensor.dims) < 2:
            continue
        roles.setdefault(weight_name, []).append({"node": name, "role": role, "axis": axis})
    return roles


def quantize_symmetric_int4(
    values: np.ndarray, axis: int | None
) -> tuple[np.ndarray, np.ndarray, int | None]:
    """Symmetric signed INT4, using [-7, 7] and implicit zero point 0."""
    source = np.asarray(values, dtype=np.float32)
    if not np.isfinite(source).all():
        raise ValueError("Cannot INT4-quantize a tensor containing NaN or infinity")

    if axis is None:
        max_abs = float(np.max(np.abs(source))) if source.size else 0.0
        scale = np.asarray(max_abs / 7.0 if max_abs > 0 else 1.0, dtype=np.float32)
        quantized = np.rint(source / scale).clip(-7, 7).astype(np.int8)
        return quantized, scale, None

    axis = axis % source.ndim
    reduce_axes = tuple(i for i in range(source.ndim) if i != axis)
    max_abs = np.max(np.abs(source), axis=reduce_axes) if reduce_axes else np.abs(source)
    scales = np.where(max_abs > 0, max_abs / 7.0, 1.0).astype(np.float32)
    broadcast_shape = [1] * source.ndim
    broadcast_shape[axis] = source.shape[axis]
    quantized = np.rint(source / scales.reshape(broadcast_shape)).clip(-7, 7).astype(np.int8)
    return quantized, scales, axis


def unique_name(base: str, occupied: set[str]) -> str:
    name = base
    suffix = 1
    while name in occupied:
        name = f"{base}_{suffix}"
        suffix += 1
    occupied.add(name)
    return name


def make_int4_initializer(name: str, q: np.ndarray) -> TensorProto:
    # ONNX helper packs signed INT4 values into nibbles in the stored tensor.
    return helper.make_tensor(
        name=name,
        data_type=TensorProto.INT4,
        dims=list(q.shape),
        vals=np.asarray(q, dtype=np.int8).reshape(-1),
        raw=False,
    )


def make_weight_only_int4(model: onnx.ModelProto, min_elements: int) -> dict[str, Any]:
    roles = collect_weight_roles(model)
    initializers = {t.name: t for t in model.graph.initializer}
    occupied = {t.name for t in model.graph.initializer}
    for node in model.graph.node:
        occupied.update(n for n in node.input if n)
        occupied.update(n for n in node.output if n)

    new_initializers: list[TensorProto] = []
    dequant_nodes: list[onnx.NodeProto] = []
    quantized_names: set[str] = set()
    report_rows: list[dict[str, Any]] = []
    original_bytes = 0
    packed_bytes = 0
    skipped: list[dict[str, Any]] = []

    ordered_names = sorted(roles, key=lambda name: tensor_elements(initializers[name]), reverse=True)
    for weight_name in ordered_names:
        tensor = initializers[weight_name]
        count = tensor_elements(tensor)
        if count < min_elements:
            skipped.append({"name": weight_name, "elements": count, "reason": "below min-elements"})
            continue

        weight_roles = roles[weight_name]
        axes = {int(item["axis"]) % len(tensor.dims) for item in weight_roles}
        # If the same tensor is shared with different axis conventions, use
        # one tensor-wide scale rather than incorrectly applying an axis.
        axis: int | None = next(iter(axes)) if len(axes) == 1 else None

        arr = np.asarray(numpy_helper.to_array(tensor))
        q, scales_f32, axis = quantize_symmetric_int4(arr, axis)
        scale_name = unique_name(f"{weight_name}__int4_scale", occupied)
        q_name = unique_name(f"{weight_name}__int4_values", occupied)

        scale_dtype = np.float16 if arr.dtype == np.float16 else np.float32
        scales = np.asarray(scales_f32, dtype=scale_dtype)
        scale_tensor = numpy_helper.from_array(scales, name=scale_name)
        quant_tensor = make_int4_initializer(q_name, q)

        dq_attrs = {} if axis is None else {"axis": axis}
        dq = helper.make_node(
            "DequantizeLinear",
            inputs=[q_name, scale_name],
            outputs=[weight_name],
            name=unique_name(f"{weight_name}__int4_dequant", occupied),
            **dq_attrs,
        )
        dequant_nodes.append(dq)
        new_initializers.extend([quant_tensor, scale_tensor])
        quantized_names.add(weight_name)

        before_nbytes = len(tensor.raw_data) if tensor.raw_data else int(arr.nbytes)
        after_nbytes = (count + 1) // 2
        original_bytes += before_nbytes
        packed_bytes += after_nbytes + int(scales.nbytes)
        report_rows.append({
            "initializer": weight_name,
            "shape": [int(d) for d in tensor.dims],
            "elements": count,
            "roles": weight_roles,
            "granularity": "per_tensor" if axis is None else "per_axis",
            "axis": axis,
            "original_weight_bytes": before_nbytes,
            "int4_payload_bytes": after_nbytes,
            "scale_bytes": int(scales.nbytes),
            "estimated_savings_bytes": before_nbytes - after_nbytes - int(scales.nbytes),
        })

    if not report_rows:
        raise RuntimeError("No eligible floating-point weights met --min-elements")

    kept_initializers = [t for t in model.graph.initializer if t.name not in quantized_names]
    del model.graph.initializer[:]
    model.graph.initializer.extend(kept_initializers)
    model.graph.initializer.extend(new_initializers)

    # Dequantizers must precede original consumers in topological order.
    original_nodes = list(model.graph.node)
    del model.graph.node[:]
    model.graph.node.extend(dequant_nodes)
    model.graph.node.extend(original_nodes)

    default_import = next((imp for imp in model.opset_import if imp.domain in ("", "ai.onnx")), None)
    if default_import is None:
        default_import = model.opset_import.add()
        default_import.domain = ""
    default_import.version = max(int(default_import.version), 21)
    model.ir_version = max(int(model.ir_version), 10)

    return {
        "quantized_tensor_count": len(report_rows),
        "quantized_original_weight_bytes": original_bytes,
        "packed_int4_payload_and_scale_bytes": packed_bytes,
        "estimated_weight_bytes_saved": original_bytes - packed_bytes,
        "weights": report_rows,
        "skipped": skipped,
    }


def save_wav(path: Path, samples: np.ndarray, sample_rate: int) -> None:
    safe = np.nan_to_num(
        np.asarray(samples, dtype=np.float32).reshape(-1),
        nan=0.0, posinf=0.0, neginf=0.0,
    )
    pcm = (np.clip(safe, -1.0, 1.0) * 32767.0).astype("<i2", copy=False)
    with wave.open(str(path), "wb") as wav:
        wav.setnchannels(1)
        wav.setsampwidth(2)
        wav.setframerate(sample_rate)
        wav.writeframes(pcm.tobytes())


def audio_summary(samples: np.ndarray, sample_rate: int) -> dict[str, Any]:
    x = np.asarray(samples, dtype=np.float64).reshape(-1)
    if x.size == 0 or not np.isfinite(x).all():
        raise ValueError("Model returned an empty or non-finite waveform")
    centered = x - x.mean()
    spectrum = np.abs(np.fft.rfft(centered))
    frequencies = np.fft.rfftfreq(x.size, d=1 / sample_rate)
    total_energy = float(np.sum(spectrum**2)) + 1e-12
    high_energy = float(np.sum(spectrum[frequencies >= min(4000.0, sample_rate * 0.25)]**2))
    return {
        "samples": int(x.size),
        "duration_seconds": round(x.size / sample_rate, 3),
        "sample_rate": sample_rate,
        "peak": round(float(np.max(np.abs(x))), 6),
        "rms": round(float(np.sqrt(np.mean(centered**2))), 6),
        "clipped_sample_percent": round(float(np.mean(np.abs(x) >= 0.999) * 100), 5),
        "energy_above_4khz_percent": round(100.0 * high_energy / total_energy, 3),
    }


def run_model(path: Path, ids: np.ndarray, ort) -> np.ndarray:
    options = ort.SessionOptions()
    options.intra_op_num_threads = 1
    options.inter_op_num_threads = 1
    session = ort.InferenceSession(str(path), sess_options=options, providers=["CPUExecutionProvider"])
    inputs = session.get_inputs()
    if len(inputs) != 1 or inputs[0].type != "tensor(int64)":
        raise RuntimeError("Expected one int64 token input; found " + repr([(i.name, i.type, i.shape) for i in inputs]))
    input_meta = inputs[0]
    outputs = session.get_outputs()
    output_meta = next((o for o in outputs if o.name == "audio"), outputs[0])
    print(f"Runtime input={input_meta.name} {input_meta.shape}; output={output_meta.name}")
    result = session.run([output_meta.name], {input_meta.name: ids})[0]
    return np.asarray(result, dtype=np.float32).reshape(-1)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input-model", type=Path, required=True, help="Clean, already-listened-to MatMul-only INT8 model; never overwritten")
    parser.add_argument("--output-dir", type=Path, required=True, help="New output directory for model, WAVs, and report")
    parser.add_argument("--cache-dir", type=Path, default=Path("build/tts/odia_onnx_cache"))
    parser.add_argument("--text", default=DEFAULT_TEXT, help="Odia sentence used for both WAVs")
    parser.add_argument("--min-elements", type=int, default=32, help="Skip eligible weights smaller than this element count (default: 32)")
    args = parser.parse_args()

    if not args.input_model.is_file():
        parser.error(f"Input model not found: {args.input_model}")
    if args.min_elements < 1:
        parser.error("--min-elements must be positive")
    args.output_dir.mkdir(parents=True, exist_ok=True)

    candidate_path = args.output_dir / "model.odia.all_weights.int4.onnx"
    base_wav = args.output_dir / "base_matmul_int8.wav"
    candidate_wav = args.output_dir / "all_weights_int4_candidate.wav"
    report_path = args.output_dir / "all_weights_int4_report.json"
    for path in (candidate_path, base_wav, candidate_wav, report_path):
        if path.exists():
            parser.error(f"Refusing to overwrite existing result: {path}")
    args.cache_dir.mkdir(parents=True, exist_ok=True)

    try:
        import onnxruntime as ort
        from transformers import AutoTokenizer
    except ImportError as exc:
        print(
            f"Missing dependency: {exc}\n"
            "Install with: python -m pip install numpy onnx onnxruntime transformers huggingface_hub",
            file=sys.stderr,
        )
        return 3

    print("ONNX Runtime:", ort.__version__)
    model = onnx.load(str(args.input_model), load_external_data=True)
    print("Input opset(s):", [(item.domain or "ai.onnx", int(item.version)) for item in model.opset_import])
    default_import = next((imp for imp in model.opset_import if imp.domain in ("", "ai.onnx")), None)
    if default_import is None:
        raise RuntimeError("Input ONNX model has no default ai.onnx opset import")
    if default_import.version < 21:
        print("Converting ONNX graph to opset 21 for native INT4 DequantizeLinear...")
        try:
            model = onnx.version_converter.convert_version(model, 21)
        except Exception as exc:
            raise RuntimeError(
                "ONNX version conversion to opset 21 failed. Original input was not modified. "
                "Do not force the opset number manually; inspect this conversion error first."
            ) from exc

    print("Quantizing all eligible floating-point weights to packed signed INT4 (weight-only)...")
    quant_report = make_weight_only_int4(model, args.min_elements)
    print("Quantized eligible tensors:", quant_report["quantized_tensor_count"])
    print("Approx. original weight bytes:", f'{quant_report["quantized_original_weight_bytes"] / (1024*1024):.2f} MiB')
    print("Approx. INT4 payload + scales:", f'{quant_report["packed_int4_payload_and_scale_bytes"] / (1024*1024):.2f} MiB')
    print("Estimated savings on replaced weights:", f'{quant_report["estimated_weight_bytes_saved"] / (1024*1024):.2f} MiB')

    onnx.checker.check_model(model)
    onnx.save_model(model, str(candidate_path))
    print("Saved candidate:", candidate_path)
    print("Candidate file size:", f'{candidate_path.stat().st_size / (1024*1024):.2f} MiB')

    print("Loading the official MMS Odia tokenizer...")
    tokenizer = AutoTokenizer.from_pretrained(
        TOKENIZER_REPO, cache_dir=str(args.cache_dir), trust_remote_code=False
    )
    ids = np.asarray(tokenizer(args.text, return_tensors="np")["input_ids"], dtype=np.int64)
    print("Text:", args.text)
    print("Input token count:", ids.shape[1])

    print("\nSynthesizing baseline from the clean MatMul-only INT8 model...")
    base_audio = run_model(args.input_model, ids, ort)
    base_stats = audio_summary(base_audio, 16000)
    save_wav(base_wav, base_audio, 16000)
    del base_audio

    print("\nLoading/synthesizing aggressive INT4 candidate (desktop runtime compatibility check)...")
    candidate_audio = run_model(candidate_path, ids, ort)
    candidate_stats = audio_summary(candidate_audio, 16000)
    save_wav(candidate_wav, candidate_audio, 16000)

    report = {
        "input_model": str(args.input_model),
        "input_model_bytes": args.input_model.stat().st_size,
        "candidate_model": str(candidate_path),
        "candidate_model_bytes": candidate_path.stat().st_size,
        "opset": 21,
        "quantization": "symmetric signed INT4 weight-only via packed INT4 initializers + DequantizeLinear; activations unchanged",
        "min_elements": args.min_elements,
        **quant_report,
        "listening_text": args.text,
        "outputs": {
            "baseline_matmul_int8": {**base_stats, "wav": str(base_wav)},
            "all_weights_int4_candidate": {**candidate_stats, "wav": str(candidate_wav)},
        },
        "note": "Desktop ORT success does not guarantee compatibility with Android runtime. Listen before considering app integration.",
    }
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print("\nRESULTS")
    print(f'  Baseline model: {args.input_model.stat().st_size / (1024*1024):.2f} MiB')
    print(f'  INT4 candidate: {candidate_path.stat().st_size / (1024*1024):.2f} MiB')
    print(f"  Baseline WAV:   {base_wav}")
    print(f"  Candidate WAV:  {candidate_wav}")
    print(f"  Report:         {report_path}")
    print("No app assets or regular model ZIPs were changed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
