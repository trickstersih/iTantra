#!/usr/bin/env python3
"""Minimal standalone English TTS test for Itantra's new model ZIP."""

from __future__ import annotations

import argparse
import shutil
import tempfile
import time
import wave
import zipfile
from pathlib import Path

from piper import PiperVoice


TEXT = "Hello, this is an offline English voice test for Itantra."


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("zip_path", type=Path)
    parser.add_argument(
        "--output",
        type=Path,
        default=Path("english_test.wav"),
    )
    args = parser.parse_args()

    zip_path = args.zip_path.expanduser().resolve()
    output = args.output.expanduser().resolve()

    if not zip_path.is_file():
        raise SystemExit(f"ZIP not found: {zip_path}")

    model_member = "english/english_indictts_int8.onnx"
    config_member = "english/english_indictts_int8.onnx.json"

    with zipfile.ZipFile(zip_path, "r") as zf:
        bad = zf.testzip()
        if bad:
            raise SystemExit(f"ZIP CRC check failed at: {bad}")

        if model_member not in zf.namelist():
            raise SystemExit(f"Missing model: {model_member}")
        if config_member not in zf.namelist():
            raise SystemExit(f"Missing config: {config_member}")

        with tempfile.TemporaryDirectory(prefix="itantra_english_") as temp_dir:
            temp = Path(temp_dir)
            model = temp / Path(model_member).name
            config = temp / Path(config_member).name

            with zf.open(model_member, "r") as src, model.open("wb") as dst:
                shutil.copyfileobj(src, dst, 1024 * 1024)

            with zf.open(config_member, "r") as src, config.open("wb") as dst:
                shutil.copyfileobj(src, dst, 1024 * 1024)

            print("Loading English model...")
            voice = PiperVoice.load(model, config_path=config)

            print("CONFIG")
            print(f"  sample rate : {voice.config.sample_rate} Hz")
            print(f"  speakers    : {voice.config.num_speakers}")
            print(f"  phoneme type: {voice.config.phoneme_type}")
            print(f"  eSpeak voice: {voice.config.espeak_voice}")
            print()

            phonemes = voice.phonemize(TEXT)
            print("PHONEMES")
            print(phonemes)
            print()

            output.parent.mkdir(parents=True, exist_ok=True)

            print(f'TEXT: "{TEXT}"')
            print(f"Writing: {output}")

            start = time.perf_counter()
            with wave.open(str(output), "wb") as wav_file:
                voice.synthesize(TEXT, wav_file)
            elapsed = time.perf_counter() - start

            with wave.open(str(output), "rb") as wav_file:
                duration = wav_file.getnframes() / wav_file.getframerate()

            print()
            print("RESULT")
            print("  synthesis       : PASS")
            print(f"  synthesis time  : {elapsed:.3f} s")
            print(f"  audio duration  : {duration:.3f} s")
            print(f"  output          : {output}")
            print()
            print("Listen to the WAV for pronunciation, naturalness, rhythm, and artifacts.")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
