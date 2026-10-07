#!/usr/bin/env python3
"""
Create lean copies of the bundled model ZIPs by keeping only files that the
Android model stores actually require.

This script does NOT modify the original ZIPs. It writes trimmed copies next
to them with a .trimmed.zip suffix and prints the before/after sizes.

Review the printed file list before replacing the originals in Git.
"""

from __future__ import annotations

import zipfile
from pathlib import Path

ROOT = Path("app/src/main/assets/models")

KEEP = {
    "andr2.zip": {
        "andr2/encoder_int8.onnx",
        "andr2/decoder_fp32.onnx",
        "andr2/mel_filters_80x201.npy",
        "andr2/preprocess.json",
        "andr2/vocab_map.json",
        "andr2/tokenizer/vocab.json",
    },
    "english_stt.zip": {
        "New folder/model.int8.onnx",
        "New folder/config.json",
        "New folder/vocab.txt",
    },
    "quantized_models.zip": {
        "quantized_models/hin/model.int8.onnx",
        "quantized_models/hin/config.json",
        "quantized_models/hin/vocab.json",
        "quantized_models/hin/tokenizer_config.json",
        "quantized_models/hin/tokens.txt",
        "quantized_models/eng/model.int8.onnx",
        "quantized_models/eng/config.json",
        "quantized_models/eng/vocab.json",
        "quantized_models/eng/tokenizer_config.json",
        "quantized_models/eng/tokens.txt",
    },
}


def main() -> int:
    for name, keep in KEEP.items():
        src = ROOT / name
        dst = ROOT / f"{src.stem}.trimmed.zip"

        if not src.is_file():
            raise FileNotFoundError(src)

        with zipfile.ZipFile(src, "r") as zin:
            members = [info for info in zin.infolist() if not info.is_dir()]
            available = {info.filename for info in members}

            missing = sorted(keep - available)
            if missing:
                raise RuntimeError(
                    f"{src}: required files are missing:\n"
                    + "\n".join(f"  {m}" for m in missing)
                )

            with zipfile.ZipFile(
                dst, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9
            ) as zout:
                for info in members:
                    if info.filename not in keep:
                        continue
                    data = zin.read(info.filename)
                    zout.writestr(info, data)

        print(
            f"{name}: "
            f"{src.stat().st_size / 1048576:.2f} MB -> "
            f"{dst.stat().st_size / 1048576:.2f} MB"
        )

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
