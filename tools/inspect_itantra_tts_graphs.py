#!/usr/bin/env python3
"""Inspect ONNX graph structure and weight precision for every model in the iTantra TTS ZIP.

This is a read-only diagnostic. It does not rewrite models or change app assets.
Requires the 'onnx' Python package in the active environment:
  python -m pip install onnx
Run:
  python tools/inspect_itantra_tts_graphs.py app/src/main/assets/models/itantra-tts.zip
"""
from __future__ import annotations

import argparse
import collections
import json
import math
import sys
import zipfile
from pathlib import PurePosixPath
from typing import Iterable

ZIP_ROOT = ".itantra-tts-onnx"
LANGUAGES = {
    "bn": "Bengali",
    "en": "English",
    "gu": "Gujarati",
    "hi": "Hindi",
    "kn": "Kannada",
    "ml": "Malayalam",
    "mr": "Marathi",
    "or": "Odia",
    "ta": "Tamil",
    "te": "Telugu",
}
QUANT_OPS = {
    "QuantizeLinear", "DequantizeLinear", "DynamicQuantizeLinear",
    "QLinearConv", "QLinearMatMul", "MatMulInteger", "ConvInteger",
    "QAttention", "GatherBlockQuantized",
}
DTYPE_SIZES = {
    "FLOAT": 4, "UINT8": 1, "INT8": 1, "UINT16": 2, "INT16": 2,
    "INT32": 4, "INT64": 8, "BOOL": 1, "FLOAT16": 2, "BFLOAT16": 2,
    "DOUBLE": 8, "UINT32": 4, "UINT64": 8, "COMPLEX64": 8,
    "COMPLEX128": 16, "FLOAT8E4M3FN": 1, "FLOAT8E4M3FNUZ": 1,
    "FLOAT8E5M2": 1, "FLOAT8E5M2FNUZ": 1, "INT4": 0.5, "UINT4": 0.5,
}


def fmt_bytes(value: float | int) -> str:
    amount = float(value)
    for unit in ("B", "KiB", "MiB", "GiB"):
        if amount < 1024 or unit == "GiB":
            return f"{amount:,.2f} {unit}"
        amount /= 1024
    return f"{amount:,.2f} GiB"


def dtype_name(tensor, onnx) -> str:
    try:
        return onnx.TensorProto.DataType.Name(tensor.data_type)
    except Exception:
        return str(tensor.data_type)


def element_count(tensor) -> int:
    if not tensor.dims:
        return 1
    return math.prod(int(dim) for dim in tensor.dims)


def decoded_size(tensor, onnx) -> float:
    name = dtype_name(tensor, onnx)
    size = DTYPE_SIZES.get(name)
    if size is None:
        return 0
    return element_count(tensor) * size


def find_model_entry(archive: zipfile.ZipFile, code: str) -> str:
    wanted = f"{ZIP_ROOT}/{code}/model.int8.mixed.onnx"
    exact = [i.filename for i in archive.infolist() if i.filename.replace("\\", "/") == wanted]
    if len(exact) != 1:
        candidates = [
            i.filename for i in archive.infolist()
            if not i.is_dir()
            and i.filename.replace("\\", "/").endswith(f"/{code}/model.int8.mixed.onnx")
        ]
        if len(candidates) != 1:
            raise FileNotFoundError(
                f"Expected one ONNX model for {code}; candidates: {candidates}"
            )
        return candidates[0]
    return exact[0]


def recursive_graphs(graph, onnx) -> Iterable:
    yield graph
    for node in graph.node:
        for attribute in node.attribute:
            if attribute.type == onnx.AttributeProto.GRAPH:
                yield from recursive_graphs(attribute.g, onnx)
            elif attribute.type == onnx.AttributeProto.GRAPHS:
                for child in attribute.graphs:
                    yield from recursive_graphs(child, onnx)


def tensor_payloads(graph):
    """Yield all stored initializer and Constant-node tensors in this graph."""
    for item in graph.initializer:
        yield item, "initializer"
    for node in graph.node:
        if node.op_type != "Constant":
            continue
        for attribute in node.attribute:
            if attribute.type == attribute.TENSOR:
                yield attribute.t, "Constant-node tensor"
            elif attribute.type == attribute.TENSORS:
                for tensor in attribute.tensors:
                    yield tensor, "Constant-node tensor"


def summarize_tokens(raw: bytes) -> dict:
    mapping: dict[str, int] = {}
    malformed: list[str] = []
    for number, line in enumerate(raw.decode("utf-8-sig").splitlines(), 1):
        if line == "":
            continue
        try:
            symbol, token_id_text = line.rsplit(" ", 1)
            token_id = int(token_id_text)
        except ValueError:
            malformed.append(f"line {number}: {line!r}")
            continue
        if symbol in mapping and mapping[symbol] != token_id:
            malformed.append(f"duplicate symbol with different ID at line {number}: {symbol!r}")
        mapping[symbol] = token_id
    zero_symbols = sorted((symbol for symbol, token_id in mapping.items() if token_id == 0))
    all_ids = list(mapping.values())
    duplicates = len(all_ids) - len(set(all_ids))
    return {
        "line_count": len(raw.decode("utf-8-sig").splitlines()),
        "unique_symbols": len(mapping),
        "unique_ids": len(set(all_ids)),
        "min_id": min(all_ids) if all_ids else None,
        "max_id": max(all_ids) if all_ids else None,
        "duplicate_id_aliases": duplicates,
        "id_zero_symbols": [repr(s) for s in zero_symbols],
        "malformed": malformed,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("zip_path", help="Path to itantra-tts.zip")
    parser.add_argument(
        "--language", choices=sorted(LANGUAGES),
        help="Inspect one language only; default is all ten",
    )
    args = parser.parse_args()

    try:
        import onnx
        import onnxruntime as ort
    except ImportError as exc:
        print(
            f"Missing dependency: {exc}. Activate the project venv and run "
            "'python -m pip install onnx onnxruntime'.",
            file=sys.stderr,
        )
        return 3

    try:
        with zipfile.ZipFile(args.zip_path, "r") as archive:
            infos = [item for item in archive.infolist() if not item.is_dir()]
            bad_member = archive.testzip()
            duplicate_names = [
                name for name, count in collections.Counter(i.filename for i in infos).items()
                if count > 1
            ]
            print(f"ZIP: {args.zip_path}")
            print(f"Files: {len(infos)} | compressed size: {fmt_bytes(sum(i.compress_size for i in infos))}")
            print(f"Uncompressed entry total: {fmt_bytes(sum(i.file_size for i in infos))}")
            print(f"CRC test: {'PASS' if bad_member is None else 'FAIL: ' + bad_member}")
            print(f"Duplicate archive paths: {duplicate_names or 'none'}")
            print(f"ONNX package version: {onnx.__version__}")
            print(f"ONNX Runtime version: {ort.__version__}")

            selected = [args.language] if args.language else list(LANGUAGES)
            all_ok = bad_member is None and not duplicate_names
            for code in selected:
                display = LANGUAGES[code]
                print("\n" + "=" * 76)
                print(f"{display} ({code})")

                config_matches = [
                    i.filename for i in infos
                    if i.filename.replace("\\", "/").endswith(f"/{code}/config.json")
                    and ZIP_ROOT in PurePosixPath(i.filename.replace("\\", "/")).parts
                ]
                token_matches = [
                    i.filename for i in infos
                    if i.filename.replace("\\", "/").endswith(f"/{code}/tokens.txt")
                    and ZIP_ROOT in PurePosixPath(i.filename.replace("\\", "/")).parts
                ]
                if len(config_matches) != 1 or len(token_matches) != 1:
                    print(f"SIDECAR ERROR: config={config_matches}; tokens={token_matches}")
                    all_ok = False
                    continue
                config = json.loads(archive.read(config_matches[0]).decode("utf-8"))
                token_summary = summarize_tokens(archive.read(token_matches[0]))
                print("config.json: " + json.dumps(config, ensure_ascii=False, sort_keys=True))
                print("tokens.txt summary: " + json.dumps(token_summary, ensure_ascii=True))
                if token_summary["malformed"]:
                    all_ok = False

                model_entry = find_model_entry(archive, code)
                model_info = next(item for item in infos if item.filename == model_entry)
                model_bytes = archive.read(model_entry)
                print(f"ONNX archive path: {model_entry}")
                print(f"Compressed model size: {fmt_bytes(model_info.compress_size)}")
                print(f"Uncompressed ONNX size: {fmt_bytes(model_info.file_size)}")

                try:
                    model = onnx.load_model_from_string(model_bytes)
                    print("IR version: " + str(model.ir_version))
                    print("Opsets: " + json.dumps([
                        {"domain": op.domain or "ai.onnx", "version": op.version}
                        for op in model.opset_import
                    ]))
                    print("Producer: " + repr(model.producer_name))
                    print("Graph: " + repr(model.graph.name))
                    print("Model metadata properties: " + json.dumps(
                        {item.key: item.value for item in model.metadata_props},
                        ensure_ascii=True,
                        sort_keys=True,
                    ))

                    graph_list = list(recursive_graphs(model.graph, onnx))
                    op_counts = collections.Counter()
                    quant_counts = collections.Counter()
                    tensors = []
                    external_tensors = []
                    for graph in graph_list:
                        op_counts.update(node.op_type for node in graph.node)
                        quant_counts.update(
                            node.op_type for node in graph.node if node.op_type in QUANT_OPS
                        )
                        for tensor, origin in tensor_payloads(graph):
                            tensors.append((tensor, origin))
                            if tensor.data_location == onnx.TensorProto.EXTERNAL or tensor.external_data:
                                external_tensors.append(tensor.name)

                    dtype_counts = collections.Counter()
                    dtype_elements = collections.Counter()
                    dtype_decoded_bytes = collections.Counter()
                    largest = []
                    for tensor, origin in tensors:
                        kind = dtype_name(tensor, onnx)
                        count = element_count(tensor)
                        size = decoded_size(tensor, onnx)
                        dtype_counts[kind] += 1
                        dtype_elements[kind] += count
                        dtype_decoded_bytes[kind] += size
                        largest.append({
                            "name": tensor.name or f"<unnamed {origin}>",
                            "origin": origin,
                            "dtype": kind,
                            "elements": count,
                            "dims": list(tensor.dims),
                            "decoded_size": size,
                            "has_raw_data": bool(tensor.raw_data),
                            "external_data": bool(tensor.external_data),
                        })

                    print(f"Graphs including subgraphs: {len(graph_list)}")
                    print(f"Nodes across graphs: {sum(op_counts.values())}")
                    print("Operator counts: " + json.dumps(dict(op_counts.most_common()), sort_keys=True))
                    print("Quantization-related operators: " + json.dumps(dict(quant_counts), sort_keys=True))
                    print(f"Initializer/Constant tensors: {len(tensors)}")
                    precision_rows = []
                    for kind in sorted(dtype_counts):
                        precision_rows.append({
                            "dtype": kind,
                            "tensor_count": dtype_counts[kind],
                            "elements": dtype_elements[kind],
                            "decoded_tensor_bytes_estimate": fmt_bytes(dtype_decoded_bytes[kind]),
                        })
                    print("Tensor precision summary: " + json.dumps(precision_rows, sort_keys=True))
                    all_decoded = sum(dtype_decoded_bytes.values())
                    print(f"Decoded tensor payload estimate: {fmt_bytes(all_decoded)}")
                    print(f"External tensor data references: {external_tensors or 'none'}")
                    print("Largest stored tensors (up to 12):")
                    for item in sorted(largest, key=lambda value: value["decoded_size"], reverse=True)[:12]:
                        print("  " + json.dumps(item, ensure_ascii=False, sort_keys=True))

                    # ONNX Runtime verifies that the provider can construct a session.
                    options = ort.SessionOptions()
                    options.intra_op_num_threads = 1
                    options.inter_op_num_threads = 1
                    session = ort.InferenceSession(
                        model_bytes, sess_options=options, providers=["CPUExecutionProvider"]
                    )
                    print("ORT inputs: " + json.dumps([
                        {"name": x.name, "type": x.type, "shape": x.shape}
                        for x in session.get_inputs()
                    ]))
                    print("ORT outputs: " + json.dumps([
                        {"name": x.name, "type": x.type, "shape": x.shape}
                        for x in session.get_outputs()
                    ]))
                    del session, model, model_bytes
                except Exception as exc:
                    print(f"MODEL INSPECTION FAILED: {type(exc).__name__}: {exc}")
                    all_ok = False

            print("\n" + "=" * 76)
            print("Inspection completed. This script reports graph/initializer metadata; it does NOT quantize models or prove audio quality.")
            print("For INT4 eligibility and quality, inspect the largest eligible MatMul/Gather weights and run comparative inference/audio tests.")
            return 0 if all_ok else 1
    except (OSError, zipfile.BadZipFile) as exc:
        print(f"Cannot inspect ZIP: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
