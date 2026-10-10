#!/usr/bin/env python3
"""Run real ONNX inference for the multilingual iTantra VITS ZIP and save WAVs.

This is a PC-side diagnostic only. It does not alter the ZIP or Android app.

Usage:
  python tools/test_itantra_tts_inference.py app/src/main/assets/models/itantra-tts.zip
  python tools/test_itantra_tts_inference.py app/src/main/assets/models/itantra-tts.zip --language gu
  python tools/test_itantra_tts_inference.py app/src/main/assets/models/itantra-tts.zip --text "નમસ્તે"

The tokenizer follows the sidecars in this archive: character-to-ID rows in
 tokens.txt and config.json's add_blank/sample_rate. The archive does not
 declare a separate blank ID, so --blank-id defaults to 0 (standard VITS
 convention); confirm this against the original inference pipeline if outputs
 do not match the known-good PC samples.
"""
from __future__ import annotations

import argparse
import gc
import json
import math
import sys
import time
import unicodedata
import wave
import zipfile
from pathlib import Path, PurePosixPath

LANGUAGES = {
    "bn": ("Bengali", "নমস্কার, এটি বাংলা কণ্ঠস্বর পরীক্ষা।"),
    "en": ("English", "Hello, this is an offline voice test."),
    "gu": ("Gujarati", "નમસ્તે, આ ગુજરાતી અવાજની કસોટી છે."),
    "hi": ("Hindi", "नमस्ते, यह एक हिंदी आवाज़ परीक्षण है।"),
    "kn": ("Kannada", "ನಮಸ್ಕಾರ, ಇದು ಕನ್ನಡ ಧ್ವನಿ ಪರೀಕ್ಷೆ."),
    "ml": ("Malayalam", "നമസ്കാരം, ഇത് മലയാള ശബ്ദ പരീക്ഷണമാണ്."),
    "mr": ("Marathi", "नमस्कार, ही मराठी आवाज चाचणी आहे."),
    "or": ("Odia", "ନମସ୍କାର, ଏହା ଓଡ଼ିଆ ଧ୍ୱନି ପରୀକ୍ଷା।"),
    "ta": ("Tamil", "வணக்கம், இது தமிழ் குரல் சோதனை."),
    "te": ("Telugu", "నమస్కారం, ఇది తెలుగు స్వర పరీక్ష."),
}
ZIP_ROOT = ".itantra-tts-onnx"


def parse_tokens(raw: bytes) -> dict[str, int]:
    """Parse 'symbol <integer-id>' while preserving a literal space symbol."""
    mapping: dict[str, int] = {}
    for line_number, line in enumerate(raw.decode("utf-8-sig").splitlines(), start=1):
        if not line:
            continue
        try:
            symbol, number = line.rsplit(" ", 1)
            token_id = int(number)
        except (ValueError, TypeError) as exc:
            raise ValueError(
                f"Invalid tokens.txt row {line_number}: {line!r}"
            ) from exc
        if symbol in mapping and mapping[symbol] != token_id:
            raise ValueError(
                f"Token {symbol!r} has conflicting IDs at row {line_number}"
            )
        mapping[symbol] = token_id
    if not mapping:
        raise ValueError("tokens.txt contains no tokens")
    return mapping


def encode_text(
    text: str,
    token_ids: dict[str, int],
    add_blank: bool,
    blank_id: int,
) -> tuple[list[int], str, list[str]]:
    # Keep this conservative: NFC makes canonically equivalent Indic strings
    # consistent, but we do not apply unverified language-specific rewriting.
    normalized = unicodedata.normalize("NFC", text).replace("\r", " ").replace("\n", " ")
    recognized: list[int] = []
    kept_chars: list[str] = []
    dropped: list[str] = []
    for char in normalized:
        if char in token_ids:
            kept_chars.append(char)
            recognized.append(token_ids[char])
        elif char not in dropped:
            dropped.append(char)

    if not recognized:
        raise ValueError("No input characters were found in this language's tokens.txt")
    if add_blank:
        sequence = [blank_id]
        for token_id in recognized:
            sequence.extend((token_id, blank_id))
    else:
        sequence = recognized
    return sequence, "".join(kept_chars), dropped


def archive_entry(archive: zipfile.ZipFile, code: str, filename: str) -> str:
    expected_suffix = f"/{code}/{filename}"
    matches = [
        item.filename
        for item in archive.infolist()
        if not item.is_dir()
        and item.filename.replace("\\", "/").endswith(expected_suffix)
        and ZIP_ROOT in PurePosixPath(item.filename.replace("\\", "/")).parts
    ]
    if len(matches) != 1:
        raise FileNotFoundError(
            f"Expected exactly one {filename} for '{code}' below {ZIP_ROOT}; "
            f"found {len(matches)}"
        )
    return matches[0]


def synthesize_one(
    archive: zipfile.ZipFile,
    code: str,
    output_dir: Path,
    text_override: str | None,
    blank_id: int,
) -> bool:
    import numpy as np
    import onnxruntime as ort

    display_name, default_text = LANGUAGES[code]
    try:
        config_path = archive_entry(archive, code, "config.json")
        tokens_path = archive_entry(archive, code, "tokens.txt")
        model_path = archive_entry(archive, code, "model.int8.mixed.onnx")
        config = json.loads(archive.read(config_path).decode("utf-8"))
        token_ids = parse_tokens(archive.read(tokens_path))
        text = text_override if text_override is not None else default_text
        ids, normalized_text, dropped = encode_text(
            text,
            token_ids,
            bool(config.get("add_blank", False)),
            blank_id,
        )

        session_options = ort.SessionOptions()
        session_options.intra_op_num_threads = 1
        session_options.inter_op_num_threads = 1
        session = ort.InferenceSession(
            archive.read(model_path),
            sess_options=session_options,
            providers=["CPUExecutionProvider"],
        )
        inputs = session.get_inputs()
        if len(inputs) != 1:
            raise RuntimeError(
                "Expected one token input tensor, got: "
                + ", ".join(item.name for item in inputs)
            )
        input_info = inputs[0]
        if input_info.type != "tensor(int64)":
            raise RuntimeError(f"Expected int64 token input; got {input_info.type}")
        output_info = next(
            (item for item in session.get_outputs() if item.name == "audio"),
            session.get_outputs()[0],
        )

        input_tensor = np.asarray([ids], dtype=np.int64)
        started = time.perf_counter()
        result = session.run([output_info.name], {input_info.name: input_tensor})[0]
        elapsed_ms = (time.perf_counter() - started) * 1000.0
        samples = np.asarray(result, dtype=np.float32).reshape(-1)
        if samples.size == 0:
            raise RuntimeError("Model returned an empty audio tensor")
        if not np.isfinite(samples).all():
            raise RuntimeError("Model returned NaN or infinite audio samples")

        sample_rate = int(config.get("sample_rate", 16000))
        if sample_rate <= 0:
            raise ValueError(f"Invalid sample_rate in config.json: {sample_rate}")
        peak = float(np.max(np.abs(samples)))
        duration = samples.size / sample_rate
        pcm = (
            np.clip(samples, -1.0, 1.0) * 32767.0
        ).astype("<i2", copy=False).tobytes()

        output_dir.mkdir(parents=True, exist_ok=True)
        output_path = output_dir / f"{code}_{display_name.lower()}.wav"
        with wave.open(str(output_path), "wb") as wav:
            wav.setnchannels(1)
            wav.setsampwidth(2)
            wav.setframerate(sample_rate)
            wav.writeframes(pcm)

        print(f"\n[{display_name} / {code}] PASS")
        print(f"  input: {input_info.name} {input_info.type} shape={input_tensor.shape}")
        print(f"  output: {output_info.name} samples={samples.size}")
        print(f"  sample rate: {sample_rate} Hz | duration: {duration:.2f}s")
        print(f"  inference: {elapsed_ms:.1f} ms | waveform peak: {peak:.5f}")
        print(f"  tokens: {len(ids)} | recognized text: {normalized_text!r}")
        print(f"  dropped characters: {dropped if dropped else 'none'}")
        print(f"  WAV: {output_path}")
        if peak < 0.005:
            print("  WARNING: waveform peak is very low; listen for near-silence.")
        if dropped:
            print("  WARNING: some input characters were skipped; judge the WAV accordingly.")
        del session
        gc.collect()
        return True
    except Exception as exc:
        print(f"\n[{display_name} / {code}] FAIL: {type(exc).__name__}: {exc}")
        return False


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("zip_path", type=Path, help="Path to itantra-tts.zip")
    parser.add_argument("--language", choices=sorted(LANGUAGES), help="Run one language only")
    parser.add_argument("--text", help="Override the sample sentence (with --language)")
    parser.add_argument(
        "--blank-id",
        type=int,
        default=0,
        help="Blank token ID when add_blank=true (default: 0)",
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=Path("build/tts/itantra_tts_audio"),
        help="Directory for generated WAV files",
    )
    args = parser.parse_args()

    if args.text is not None and not args.language:
        parser.error("--text requires --language")
    if not args.zip_path.is_file():
        parser.error(f"ZIP file not found: {args.zip_path}")

    try:
        import onnxruntime  # noqa: F401
        import numpy  # noqa: F401
    except ImportError as exc:
        print(
            f"Missing dependency: {exc}. Activate the project venv and install "
            "'onnxruntime' and 'numpy'.",
            file=sys.stderr,
        )
        return 3

    selected = [args.language] if args.language else list(LANGUAGES)
    success = 0
    with zipfile.ZipFile(args.zip_path, "r") as archive:
        for code in selected:
            if synthesize_one(
                archive, code, args.output_dir, args.text, args.blank_id
            ):
                success += 1

    print(f"\nInference summary: {success}/{len(selected)} languages generated audio.")
    print(f"WAV directory: {args.output_dir.resolve()}")
    return 0 if success == len(selected) else 1


if __name__ == "__main__":
    raise SystemExit(main())
