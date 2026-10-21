#!/usr/bin/env python3
"""Rank Odia ONNX Conv weights by stored size; read-only inspection."""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

import onnx

DTYPE_BYTES = {
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


def tensor_nbytes(tensor) -> int:
    if tensor.raw_data:
        return len(tensor.raw_data)
    count = 1
    for dim in tensor.dims:
        count *= int(dim)
    return count * DTYPE_BYTES.get(tensor.data_type, 0)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "model",
        type=Path,
        help="FP32-Conv model.matmul.int8.onnx candidate",
    )
    parser.add_argument("--top", type=int, default=30, help="Rows to print")
    args = parser.parse_args()

    if not args.model.is_file():
        parser.error(f"Model not found: {args.model}")
    if args.top < 1:
        parser.error("--top must be >= 1")

    try:
        model = onnx.load(str(args.model), load_external_data=True)
        initializers = {item.name: item for item in model.graph.initializer}
        rows = []
        skipped = []
        for index, node in enumerate(model.graph.node):
            if node.op_type != "Conv":
                continue
            if len(node.input) < 2:
                skipped.append((index, node.name or "<unnamed>", "no weight input"))
                continue
            tensor = initializers.get(node.input[1])
            if tensor is None:
                skipped.append((index, node.name or "<unnamed>", "weight not an initializer"))
                continue
            name = node.name or f"<unnamed Conv graph-index={index}>"
            rows.append({
                "name": name,
                "graph_index": index,
                "weight": tensor.name,
                "shape": tuple(int(dim) for dim in tensor.dims),
                "dtype": onnx.TensorProto.DataType.Name(tensor.data_type),
                "bytes": tensor_nbytes(tensor),
            })

        rows.sort(key=lambda row: row["bytes"], reverse=True)
        print(f"Model: {args.model}")
        print(f"Model file size: {args.model.stat().st_size / (1024 * 1024):.2f} MiB")
        print(f"Top-level Conv nodes: {sum(n.op_type == 'Conv' for n in model.graph.node)}")
        print(f"Initializer-backed Conv nodes rankable: {len(rows)}")
        print(f"Conv nodes skipped from ranking: {len(skipped)}")
        print()
        print(f"{'RANK':>4}  {'MiB':>9}  {'SHAPE':<24}  NODE NAME")
        print("-" * 100)
        for rank, row in enumerate(rows[:args.top], start=1):
            shape = "x".join(str(dim) for dim in row["shape"])
            print(
                f"{rank:>4}  {row['bytes'] / (1024 * 1024):>9.3f}  "
                f"{shape:<24}  {row['name']}"
            )
        if skipped:
            print("\nSkipped Conv nodes:")
            for index, name, reason in skipped:
                print(f"  graph-index={index}: {name}: {reason}")
        print("\nRead-only inspection complete. No model was modified.")
        return 0
    except Exception as exc:
        print(f"Inspection failed: {type(exc).__name__}: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
