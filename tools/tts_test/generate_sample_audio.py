#!/usr/bin/env python3
from __future__ import annotations

import argparse
import shutil
import tempfile
import time
import wave
import zipfile
from pathlib import Path, PurePosixPath

from piper import PiperVoice, SynthesisConfig

SAMPLES = {
    "english": ("English", "Hello, this is an offline voice test."),
    "bengali": ("Bengali", "নমস্কার, এটি একটি অফলাইন ভয়েস পরীক্ষা।"),
    "gujarati": ("Gujarati", "નમસ્તે, આ એક ઑફલાઇન અવાજ પરીક્ષણ છે."),
    "hindi": ("Hindi", "नमસ્તે, यह एक ऑफलाइन आवाज़ परीक्षण है।"),
    "kannada": ("Kannada", "ನಮಸ್ಕಾರ, ಇದು ಒಂದು ಆಫ್‌ಲೈನ್ ಧ್ವನಿ ಪರೀಕ್ಷೆ."),
    "malayalam": ("Malayalam", "നമസ്കാരം, ഇത് ഒരു ഓഫ്‌ലൈൻ ശബ്ദ പരിശോധനയാണ്."),
    "marathi": ("Marathi", "नमस्कार, ही एक ऑफलाइन आवाजाची चाचणी आहे."),
    "odia": ("Odia", "ନମସ୍କାର, ଏହା ଏକ ଅଫଲାଇନ୍ ଭଏସ୍ ପରୀକ୍ଷା।"),
    "tamil": ("Tamil", "வணக்கம், இது ஒரு ஆஃப்லைன் குரல் சோதனை."),
    "telugu": ("Telugu", "నమస్కారం, ఇది ఒక ఆఫ్‌లైన్ వాయిస్ పరీక్ష."),
}

def safe_extract(zf: zipfile.ZipFile, member: str, dest: Path) -> Path:
    p = PurePosixPath(member)
    if p.is_absolute() or ".." in p.parts:
        raise ValueError(f"Unsafe ZIP path: {member}")
    out = dest.joinpath(*p.parts)
    out.parent.mkdir(parents=True, exist_ok=True)
    with zf.open(member, "r") as src, out.open("wb") as dst:
        shutil.copyfileobj(src, dst, 1024 * 1024)
    return out

def config_for(zf: zipfile.ZipFile, model_member: str, dest: Path) -> Path:
    candidates = [
        model_member + ".json",
        str(PurePosixPath(model_member).with_suffix(".json")),
    ]
    for candidate in candidates:
        if candidate in zf.namelist():
            return safe_extract(zf, candidate, dest)
    raise FileNotFoundError(f"No JSON config found for {model_member}")

def synthesize(voice: PiperVoice, text: str, output: Path) -> float:
    chunks = []
    start = time.perf_counter()
    for chunk in voice.synthesize(text, SynthesisConfig()):
        chunks.append(chunk)
    elapsed = time.perf_counter() - start

    if not chunks:
        raise RuntimeError("No audio returned")

    audio = b"".join(c.audio_int16_bytes for c in chunks)
    sr = int(chunks[0].sample_rate)
    sw = int(chunks[0].sample_width)
    channels = int(chunks[0].sample_channels)

    output.parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(output), "wb") as wav:
        wav.setnchannels(channels)
        wav.setsampwidth(sw)
        wav.setframerate(sr)
        wav.writeframes(audio)

    duration = len(audio) / (sw * channels * sr)
    print(f"{output.name:18s}  {elapsed:.3f}s synthesis  {duration:.3f}s audio")
    return duration

def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("zip_path")
    parser.add_argument("--out", default="tts_sample_audio")
    args = parser.parse_args()

    zip_path = Path(args.zip_path).expanduser().resolve()
    out = Path(args.out).expanduser().resolve()
    out.mkdir(parents=True, exist_ok=True)

    with zipfile.ZipFile(zip_path, "r") as zf:
        bad = zf.testzip()
        if bad:
            raise SystemExit(f"ZIP CRC failure at {bad}")

        models = {}
        for member in zf.namelist():
            if member.lower().endswith(".onnx"):
                folder = PurePosixPath(member).parts[0].lower()
                models[folder] = member

        missing = sorted(set(SAMPLES) - set(models))
        if missing:
            raise SystemExit("Missing language models: " + ", ".join(missing))

        print("Generating one native-language sample per voice...")
        print(f"Output: {out}")
        print()

        for folder, (language, text) in SAMPLES.items():
            print(f"[{language}]")
            model_member = models[folder]
            with tempfile.TemporaryDirectory(prefix="itantra_sample_") as td:
                temp = Path(td)
                model = safe_extract(zf, model_member, temp)
                config = config_for(zf, model_member, temp)

                voice = None
                try:
                    voice = PiperVoice.load(model, config_path=config)
                    output = out / f"{folder}.wav"
                    duration = synthesize(voice, text, output)
                    print(f"  Text: {text}")
                    print()
                finally:
                    close = getattr(voice, "close", None)
                    if callable(close):
                        try:
                            close()
                        except Exception:
                            pass

    print("Done. Listen to each WAV for language correctness, pronunciation, naturalness, pauses, and glitches.")
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
