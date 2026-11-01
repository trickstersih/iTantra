#!/usr/bin/env python3
"""Apply symmetric per-axis INT4 weight-only quantization to the two Odia ONNX models.

Input models:
  fastspeech2_odia.fp32.onnx
  hifigan_odia.fp32.onnx

Weights are stored as packed ONNX INT4 tensors and dequantized at runtime.
Activations remain floating point. This is an aggressive size experiment; it
does not validate speech quality or guarantee Android ONNX Runtime compatibility.
"""
from __future__ import annotations

import argparse
import json
import math
import sys
from pathlib import Path
from typing import Any

import numpy as np
import onnx
from onnx import TensorProto, helper, numpy_helper

MODEL_FILES = (
    "fastspeech2_odia.fp32.onnx",
    "hifigan_odia.fp32.onnx",
)


def element_count(tensor: TensorProto) -> int:
    return math.prod(int(dim) for dim in tensor.dims) if tensor.dims else 1


def node_int_attribute(node: onnx.NodeProto, name: str, default: int = 0) -> int:
    for attr in node.attribute:
        if attr.name == name:
            try:
                return int(helper.get_attribute_value(attr))
            except (TypeError, ValueError):
                return default
    return default


def discover_weight_roles(model: onnx.ModelProto) -> dict[str, list[dict[str, Any]]]:
    initializers = {tensor.name: tensor for tensor in model.graph.initializer}
    # PyTorch's ONNX exporter commonly represents nn.Linear as
    # MatMul(activation, Transpose(weight)) + bias. In that pattern the
    # learned weight is NOT a direct MatMul input, so the old role discovery
    # missed the large attention projection matrices and left them FP32.
    producers = {
        output_name: node
        for node in model.graph.node
        for output_name in node.output
    }
    roles: dict[str, list[dict[str, Any]]] = {}

    def add(node, input_index: int, axis: int, role: str) -> None:
        if len(node.input) <= input_index:
            return
        name = node.input[input_index]
        tensor = initializers.get(name)
        if tensor is None:
            return
        if tensor.data_type not in (TensorProto.FLOAT, TensorProto.FLOAT16):
            return
        if len(tensor.dims) < 2:
            return
        roles.setdefault(name, []).append({
            "node": node.name or f"<{node.op_type}>",
            "op_type": node.op_type,
            "role": role,
            "axis": axis % len(tensor.dims),
        })

    def add_transposed_matmul_weight(node, input_index: int) -> None:
        if len(node.input) <= input_index:
            return
        transpose = producers.get(node.input[input_index])
        if transpose is None or transpose.op_type != "Transpose" or not transpose.input:
            return
        name = transpose.input[0]
        tensor = initializers.get(name)
        if tensor is None or tensor.data_type not in (TensorProto.FLOAT, TensorProto.FLOAT16):
            return
        if len(tensor.dims) < 2:
            return

        perm = None
        for attr in transpose.attribute:
            if attr.name == "perm":
                perm = list(helper.get_attribute_value(attr))
                break
        if perm is None:
            perm = list(reversed(range(len(tensor.dims))))
        if len(perm) != len(tensor.dims):
            return

        # For a right-hand MatMul weight, its output-channel axis is the
        # final axis of the transposed operand. For a left-hand weight, use
        # the first output axis. Map that axis back through Transpose.perm.
        output_axis = 0 if input_index == 0 else len(perm) - 1
        source_axis = int(perm[output_axis])
        roles.setdefault(name, []).append({
            "node": node.name or "<MatMul>",
            "op_type": "MatMul",
            "role": "MatMul.transposed_left_weight" if input_index == 0 else "MatMul.transposed_right_weight",
            "axis": source_axis,
        })

    for node in model.graph.node:
        if node.op_type == "Conv":
            add(node, 1, 0, "Conv.weight")
        elif node.op_type == "ConvTranspose":
            # ONNX ConvTranspose weights are [C_in, C_out/group, kernel...].
            add(node, 1, 1, "ConvTranspose.weight")
        elif node.op_type == "MatMul":
            add(node, 1, -1, "MatMul.right_weight")
            add(node, 0, -1, "MatMul.left_weight")
            add_transposed_matmul_weight(node, 1)
            add_transposed_matmul_weight(node, 0)
        elif node.op_type == "Gemm":
            trans_b = node_int_attribute(node, "transB", 0)
            add(node, 1, 0 if trans_b else 1, "Gemm.B")
            add(node, 0, 1, "Gemm.A")
        elif node.op_type == "Gather":
            add(node, 0, 0, "Gather.embedding")

    return roles


def unique_name(base: str, occupied: set[str]) -> str:
    result = base
    suffix = 1
    while result in occupied:
        result = f"{base}_{suffix}"
        suffix += 1
    occupied.add(result)
    return result


def int4_quantize(values: np.ndarray, axis: int | None) -> tuple[np.ndarray, np.ndarray, int | None]:
    source = np.asarray(values, dtype=np.float32)
    if source.size == 0 or not np.isfinite(source).all():
        raise ValueError("Cannot INT4-quantize an empty tensor or one containing NaN/Inf")

    if axis is None:
        max_abs = float(np.max(np.abs(source)))
        scale = np.asarray(max_abs / 7.0 if max_abs > 0 else 1.0, dtype=np.float32)
        return np.rint(source / scale).clip(-7, 7).astype(np.int8), scale, None

    axis %= source.ndim
    reduce_axes = tuple(index for index in range(source.ndim) if index != axis)
    max_abs = np.max(np.abs(source), axis=reduce_axes) if reduce_axes else np.abs(source)
    scales = np.where(max_abs > 0, max_abs / 7.0, 1.0).astype(np.float32)
    broadcast_shape = [1] * source.ndim
    broadcast_shape[axis] = source.shape[axis]
    quantized = np.rint(source / scales.reshape(broadcast_shape)).clip(-7, 7).astype(np.int8)
    return quantized, scales, axis


def quantize_model(input_path: Path, output_path: Path, min_elements: int) -> dict[str, Any]:
    if output_path.exists():
        raise FileExistsError(f"Refusing to overwrite existing candidate: {output_path}")

    model = onnx.load(str(input_path), load_external_data=True)
    default_opset = next(
        (item for item in model.opset_import if item.domain in ("", "ai.onnx")),
        None,
    )
    if default_opset is None:
        raise RuntimeError(f"No default ONNX opset import in {input_path}")
    if default_opset.version < 21:
        print(f"Upgrading {input_path.name} from opset {default_opset.version} to 21...")
        try:
            model = onnx.version_converter.convert_version(model, 21)
        except Exception as exc:
            raise RuntimeError(
                f"Safe ONNX opset conversion to 21 failed for {input_path}. "
                "The FP32 input was not modified; refusing to set the opset number manually."
            ) from exc

    roles = discover_weight_roles(model)
    initializers = {tensor.name: tensor for tensor in model.graph.initializer}
    occupied = set(initializers)
    for node in model.graph.node:
        occupied.update(name for name in node.input if name)
        occupied.update(name for name in node.output if name)

    extra_initializers: list[TensorProto] = []
    dequant_nodes: list[onnx.NodeProto] = []
    removed_names: set[str] = set()
    rows: list[dict[str, Any]] = []
    skipped: list[dict[str, Any]] = []

    ordered = sorted(roles, key=lambda name: element_count(initializers[name]), reverse=True)
    original_weight_bytes = 0
    packed_payload_and_scales_bytes = 0

    for tensor_name in ordered:
        tensor = initializers[tensor_name]
        n_elements = element_count(tensor)
        if n_elements < min_elements:
            skipped.append({
                "initializer": tensor_name,
                "elements": n_elements,
                "reason": "below-min-elements",
            })
            continue

        uses = roles[tensor_name]
        axes = {int(use["axis"]) for use in uses}
        axis: int | None = next(iter(axes)) if len(axes) == 1 else None

        original = np.asarray(numpy_helper.to_array(tensor))
        quantized, scales, axis = int4_quantize(original, axis)
        scale_name = unique_name(f"{tensor_name}__int4_scale", occupied)
        q_name = unique_name(f"{tensor_name}__int4_weight", occupied)
        dequant_name = unique_name(f"{tensor_name}__int4_dequant", occupied)

        # Pack signed INT4 values into ONNX raw_data without making a huge
        # Python list. The first value occupies the low nibble; the second
        # occupies the high nibble. Pad an odd element count with a zero nibble.
        flat = quantized.reshape(-1).astype(np.int8, copy=False)
        if flat.size % 2:
            flat = np.pad(flat, (0, 1), mode="constant", constant_values=0)
        nibbles = flat.astype(np.uint8) & np.uint8(0x0F)
        packed = (
            (nibbles[1::2] << np.uint8(4)) | nibbles[0::2]
        ).astype(np.uint8, copy=False)
        q_tensor = helper.make_tensor(
            name=q_name,
            data_type=TensorProto.INT4,
            dims=list(quantized.shape),
            vals=packed.tobytes(),
            raw=True,
        )
        scale_tensor = numpy_helper.from_array(
            np.asarray(scales, dtype=np.float32), name=scale_name
        )
        extra_initializers.extend([q_tensor, scale_tensor])
        attrs = {} if axis is None else {"axis": axis}
        dequant_nodes.append(
            helper.make_node(
                "DequantizeLinear",
                inputs=[q_name, scale_name],
                outputs=[tensor_name],
                name=dequant_name,
                **attrs,
            )
        )

        original_bytes = len(tensor.raw_data) if tensor.raw_data else int(original.nbytes)
        q_bytes = (n_elements + 1) // 2
        scales_bytes = int(np.asarray(scales).nbytes)
        original_weight_bytes += original_bytes
        packed_payload_and_scales_bytes += q_bytes + scales_bytes
        removed_names.add(tensor_name)
        rows.append({
            "initializer": tensor_name,
            "shape": [int(d) for d in tensor.dims],
            "elements": n_elements,
            "uses": uses,
            "granularity": "per_tensor" if axis is None else "per_axis",
            "axis": axis,
            "original_weight_bytes": original_bytes,
            "int4_packed_bytes": q_bytes,
            "scale_bytes": scales_bytes,
            "estimated_savings_bytes": original_bytes - q_bytes - scales_bytes,
        })

    if not rows:
        raise RuntimeError(f"No eligible initializer-backed weights found in {input_path}")

    kept = [tensor for tensor in model.graph.initializer if tensor.name not in removed_names]
    del model.graph.initializer[:]
    model.graph.initializer.extend(kept)
    model.graph.initializer.extend(extra_initializers)

    old_nodes = list(model.graph.node)
    del model.graph.node[:]
    model.graph.node.extend(dequant_nodes)
    model.graph.node.extend(old_nodes)

    onnx.checker.check_model(model)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    onnx.save_model(model, str(output_path))
    onnx.checker.check_model(str(output_path))

    return {
        "input_model": str(input_path),
        "input_bytes": input_path.stat().st_size,
        "output_model": str(output_path),
        "output_bytes": output_path.stat().st_size,
        "quantized_tensor_count": len(rows),
        "quantized_original_weight_bytes": original_weight_bytes,
        "int4_payload_and_scale_bytes_estimate": packed_payload_and_scales_bytes,
        "estimated_weight_savings_bytes": original_weight_bytes - packed_payload_and_scales_bytes,
        "weights": rows,
        "skipped": skipped,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input-dir", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--min-elements", type=int, default=32)
    parser.add_argument(
        "--check-ort",
        action="store_true",
        help="Try to load generated INT4 graphs in desktop ONNX Runtime (no inference/audio)",
    )
    args = parser.parse_args()

    input_dir = args.input_dir.expanduser().resolve()
    output_dir = args.output_dir.expanduser().resolve()
    if args.min_elements < 1:
        parser.error("--min-elements must be >= 1")
    if not input_dir.is_dir():
        parser.error(f"Input directory not found: {input_dir}")
    if output_dir.exists() and any(output_dir.iterdir()):
        parser.error(f"Output directory must be empty: {output_dir}")

    try:
        import onnxruntime as ort
    except ImportError:
        ort = None
        if args.check_ort:
            parser.error("--check-ort was requested, but onnxruntime is not installed")

    output_dir.mkdir(parents=True, exist_ok=True)
    reports = []
    for input_name in MODEL_FILES:
        input_path = input_dir / input_name
        if not input_path.is_file():
            parser.error(f"Missing exported ONNX model: {input_path}")
        output_name = input_name.replace(".fp32.onnx", ".int4.onnx")
        output_path = output_dir / output_name
        print(f"\nQuantizing {input_name} -> {output_name}")
        report = quantize_model(input_path, output_path, args.min_elements)
        reports.append(report)
        print(f"  FP32 ONNX: {report['input_bytes'] / (1024*1024):.2f} MiB")
        print(f"  INT4 ONNX: {report['output_bytes'] / (1024*1024):.2f} MiB")
        print(f"  Quantized weight tensors: {report['quantized_tensor_count']}")
        if args.check_ort and ort is not None:
            print("  Checking desktop ONNX Runtime load (no speech generated)...")
            ort.InferenceSession(str(output_path), providers=["CPUExecutionProvider"])
            print("  Desktop ORT load: OK")

    report_path = output_dir / "fastspeech2_hs_odia_int4_quantization_report.json"
    report_path.write_text(
        json.dumps(
            {
                "input_dir": str(input_dir),
                "output_dir": str(output_dir),
                "method": "symmetric signed INT4, per-axis where operator use permits; activations left FP32",
                "min_elements": args.min_elements,
                "models": reports,
                "combined_fp32_onnx_bytes": sum(item["input_bytes"] for item in reports),
                "combined_int4_onnx_bytes": sum(item["output_bytes"] for item in reports),
                "note": (
                    "No audio was generated/listened to. INT4 ONNX size and desktop load "
                    "do not prove model quality or compatibility with Android ONNX Runtime."
                ),
            },
            ensure_ascii=False,
            indent=2,
        ),
        encoding="utf-8",
    )

    combined_fp32 = sum(item["input_bytes"] for item in reports)
    combined_int4 = sum(item["output_bytes"] for item in reports)
    print("\nINT4 QUANTIZATION COMPLETE (no audio generated)")
    print(f"  Combined FP32 ONNX: {combined_fp32 / (1024*1024):.2f} MiB")
    print(f"  Combined INT4 ONNX: {combined_int4 / (1024*1024):.2f} MiB")
    print("  Report:", report_path)
    print("No app assets or regular TTS ZIPs were changed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
