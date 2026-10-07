#!/usr/bin/env python3
"""
Quantize the large andr2 decoder and produce a replacement, trimmed ZIP.

This is intentionally an offline build-side optimization tool. It does not
touch app/src/main/assets/models/andr2.zip unless the generated ZIP is copied
over it explicitly.

Requirements:
    pip install onnx onnxruntime

Example:
    python tools/quantize_andr2_decoder.py \
        --input app/src/main/assets/models/andr2.zip \
        --output build/andr2/andr2_int8.zip

The generated ZIP keeps only files required by the Android Andr2SttModelStore
and replaces decoder_fp32.onnx with decoder_int8.onnx. The Android runtime
already prefers decoder_int8.onnx and falls back to decoder_fp32.onnx.
"""

from __future__ import annotations

import argparse
import shutil
import tempfile
import zipfile
from pathlib import Path

from onnxruntime.quantization import QuantType, quantize_dynamic


ZIP_ROOT = "andr2"
SOURCE_DECODER = "decoder_fp32.onnx"
QUANTIZED_DECODER = "decoder_int8.onnx"

REQUIRED_FILES = (
    "encoder_int8.onnx",
    "mel_filters_80x201.npy",
    "preprocess.json",
    "vocab_map.json",
    "tokenizer/vocab.json",
)


def extract_required_source(
    source_zip: Path,
    staging_dir: Path,
) -> dict[str, bytes]:
    wanted = {f"{ZIP_ROOT}/{name}" for name in REQUIRED_FILES}
    wanted.add(f"{ZIP_ROOT}/{SOURCE_DECODER}")

    with zipfile.ZipFile(source_zip, "r") as archive:
        names = set(archive.namelist())
        missing = sorted(wanted - names)
        if missing:
            raise RuntimeError(
                "Source ZIP is missing required entries: " + ", ".join(missing)
            )

        files: dict[str, bytes] = {}
        for name in sorted(wanted):
            target = staging_dir / name
            target.parent.mkdir(parents=True, exist_ok=True)
            data = archive.read(name)
            target.write_bytes(data)
            files[name] = data
        return files


def build_quantized_zip(
    output_zip: Path,
    source_files: dict[str, bytes],
    quantized_decoder: Path,
) -> None:
    output_zip.parent.mkdir(parents=True, exist_ok=True)

    with zipfile.ZipFile(
        output_zip,
        "w",
        compression=zipfile.ZIP_DEFLATED,
        compresslevel=9,
    ) as archive:
        for relative in REQUIRED_FILES:
            archive.writestr(
                f"{ZIP_ROOT}/{relative}",
                source_files[f"{ZIP_ROOT}/{relative}"],
            )

        archive.write(
            quantized_decoder,
            f"{ZIP_ROOT}/{QUANTIZED_DECODER}",
        )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--input",
        type=Path,
        default=Path("app/src/main/assets/models/andr2.zip"),
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=Path("build/andr2/andr2_int8.zip"),
    )
    args = parser.parse_args()

    source_zip = args.input
    output_zip = args.output

    if not source_zip.is_file():
        raise SystemExit(f"Input ZIP not found: {source_zip}")

    with tempfile.TemporaryDirectory(prefix="andr2_quantize_") as temp:
        staging = Path(temp)
        source_files = extract_required_source(source_zip, staging)

        source_decoder = staging / ZIP_ROOT / SOURCE_DECODER
        quantized_decoder = staging / ZIP_ROOT / QUANTIZED_DECODER

        print(f"Source decoder: {source_decoder}")
        print(
            "Source decoder size: "
            f"{source_decoder.stat().st_size / (1024 * 1024):.2f} MiB"
        )

        print("Quantizing decoder weights to dynamic INT8...")
        quantize_dynamic(
            model_input=str(source_decoder),
            model_output=str(quantized_decoder),
            weight_type=QuantType.QInt8,
            per_channel=True,
            reduce_range=False,
        )

        if not quantized_decoder.is_file() or quantized_decoder.stat().st_size == 0:
            raise RuntimeError("Quantization did not produce a valid decoder")

        print(
            "Quantized decoder size: "
            f"{quantized_decoder.stat().st_size / (1024 * 1024):.2f} MiB"
        )

        build_quantized_zip(output_zip, source_files, quantized_decoder)

    source_zip_size = source_zip.stat().st_size
    output_zip_size = output_zip.stat().st_size
    print(
        f"Source ZIP: {source_zip_size / (1024 * 1024):.2f} MiB"
    )
    print(
        f"Quantized ZIP: {output_zip_size / (1024 * 1024):.2f} MiB"
    )
    print(
        "ZIP reduction: "
        f"{(source_zip_size - output_zip_size) / (1024 * 1024):.2f} MiB"
    )
    print(f"Output: {output_zip}")
    print()
    print("Next step:")
    print(f"  copy / replace {output_zip} -> app/src/main/assets/models/andr2.zip")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
