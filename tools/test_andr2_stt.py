#!/usr/bin/env python3
"""
Test the multilingual STT model bundled in:

    app/src/main/assets/models/andr2.zip

The runner currently targets split Whisper-style ONNX exports because those
can be exercised directly with ONNX Runtime without changing the Android app.

The archive is auto-inspected to find:
  - an encoder .onnx file
  - a decoder .onnx file
  - an optional tokens/vocabulary file

Supported test languages in this branch:
  en -> english.wav
  hi -> hindi.wav
  bn -> bengali.wav

Audio requirements:
  - WAV
  - mono or stereo (stereo is averaged to mono)
  - 16 kHz
  - PCM/float WAV accepted by soundfile

Examples:
  python tools/test_andr2_stt.py --language en
  python tools/test_andr2_stt.py --language hi --audio hindi.wav
  python tools/test_andr2_stt.py --language bn --audio bengali.wav
  python tools/test_andr2_stt.py --all

The script does NOT modify the Android app or replace the existing STT
backends. It is only a desktop validation tool for andr2.zip.
"""

from __future__ import annotations

import argparse
import json
import re
import zipfile
from pathlib import Path
from typing import Iterable

import numpy as np
import onnxruntime as ort
import soundfile as sf
from transformers import WhisperFeatureExtractor, WhisperTokenizer


DEFAULT_ZIP = Path("app/src/main/assets/models/andr2.zip")
DEFAULT_AUDIO = {
    "en": Path("english.wav"),
    "hi": Path("hindi.wav"),
    "bn": Path("bengali.wav"),
}

SAMPLE_RATE = 16_000
MAX_NEW_TOKENS = 96
EOT_FALLBACK = 50_256

# Whisper-family dimensions used to choose the matching HF tokenizer/feature
# extractor when the custom decoder export exposes static cache dimensions.
WHISPER_SIZES = {
    # (layers, d_model): model name
    (4, 384): "tiny",
    (6, 512): "base",
    (12, 768): "small",
    (24, 1024): "medium",
    (32, 1280): "large",
}


def required_input(session: ort.InferenceSession, wanted: str) -> str:
    names = [x.name for x in session.get_inputs()]
    if wanted in names:
        return wanted

    normalized = wanted.replace("_", "").lower()
    for name in names:
        if name.replace("_", "").lower() == normalized:
            return name

    raise RuntimeError(f"Missing input {wanted!r}. Inputs: {names}")


def iter_archive_files(zip_path: Path) -> Iterable[str]:
    with zipfile.ZipFile(zip_path) as archive:
        yield from (
            name.replace("\\\\", "/")
            for name in archive.namelist()
            if not name.endswith("/")
        )


def choose_model_files(zip_path: Path) -> tuple[str, str, str | None]:
    names = list(iter_archive_files(zip_path))
    onnx_files = [name for name in names if name.lower().endswith(".onnx")]

    encoder_candidates = [
        n for n in onnx_files if "encoder" in Path(n).name.lower()
    ]
    decoder_candidates = [
        n for n in onnx_files if "decoder" in Path(n).name.lower()
    ]

    if not encoder_candidates or not decoder_candidates:
        preview = "\\n".join(names[:120])
        raise RuntimeError(
            "andr2.zip does not look like a split Whisper ONNX archive. "
            "Expected filenames containing 'encoder' and 'decoder'. "
            f"Archive preview:\\n{preview}"
        )

    def score(path: str) -> tuple[int, int]:
        name = Path(path).name.lower()
        # Prefer int8 exports and then the shorter/less auxiliary-looking name.
        return (
            0 if ("int8" in name or "quant" in name) else 1,
            len(name),
        )

    encoder = sorted(encoder_candidates, key=score)[0]
    decoder = sorted(decoder_candidates, key=score)[0]

    token_candidates = [
        n for n in names
        if Path(n).name.lower() in {
            "tokens.txt",
            "base-tokens.txt",
            "tiny-tokens.txt",
            "vocab.json",
        }
    ]
    tokens = token_candidates[0] if token_candidates else None

    print("Archive model files:")
    print("  encoder:", encoder)
    print("  decoder:", decoder)
    print("  tokens :", tokens or "<not found>")

    return encoder, decoder, tokens


def extract_entry(
    zip_path: Path,
    archive_name: str,
    output_dir: Path,
) -> Path:
    output_dir.mkdir(parents=True, exist_ok=True)
    target = output_dir / Path(archive_name).name

    with zipfile.ZipFile(zip_path) as archive, archive.open(archive_name) as source:
        # Guard against ZIP path traversal while still flattening output names.
        target = target.resolve()
        root = output_dir.resolve()
        if root not in target.parents:
            raise RuntimeError(f"Unsafe archive entry: {archive_name}")

        if not target.exists():
            print("Extracting:", archive_name)
            with target.open("wb") as destination:
                while True:
                    chunk = source.read(1024 * 1024)
                    if not chunk:
                        break
                    destination.write(chunk)

    return target


def read_audio(path: Path) -> np.ndarray:
    audio, sr = sf.read(
        str(path),
        dtype="float32",
        always_2d=False,
    )

    if sr != SAMPLE_RATE:
        raise RuntimeError(
            f"{path} is {sr} Hz. Convert it to mono {SAMPLE_RATE} Hz WAV first."
        )

    if audio.size == 0:
        raise RuntimeError(f"{path} is empty.")

    if audio.ndim > 1:
        audio = np.mean(audio, axis=1)

    audio = np.asarray(audio, dtype=np.float32)
    audio = np.nan_to_num(audio, nan=0.0, posinf=0.0, neginf=0.0)

    peak = float(np.max(np.abs(audio)))
    if peak > 1.0:
        audio = audio / peak

    return audio


def static_dim(shape: list[object], index: int) -> int | None:
    if index >= len(shape):
        return None
    value = shape[index]
    return value if isinstance(value, int) and value > 0 else None


def infer_whisper_size(decoder: ort.InferenceSession) -> tuple[int, int, str]:
    """
    Infer (n_layers, d_model, HF model size) from self-K/self-V cache shapes.

    Typical exported decoder cache:
      [n_layers, batch, cache_length, d_model]
    """
    candidates = []
    for item in decoder.get_inputs():
        name = item.name.lower()
        if "self" not in name:
            continue
        if not any(k in name for k in ("cache", "k", "v")):
            continue

        shape = list(item.shape)
        if len(shape) != 4:
            continue

        layers = static_dim(shape, 0)
        d_model = static_dim(shape, 3)
        if layers is not None and d_model is not None:
            candidates.append((layers, d_model))

    # Fall back to any 4D input if naming is unusual.
    if not candidates:
        for item in decoder.get_inputs():
            shape = list(item.shape)
            if len(shape) != 4:
                continue
            layers = static_dim(shape, 0)
            d_model = static_dim(shape, 3)
            if layers is not None and d_model is not None:
                candidates.append((layers, d_model))

    for layers, d_model in candidates:
        model_name = WHISPER_SIZES.get((layers, d_model))
        if model_name:
            return layers, d_model, model_name

    if candidates:
        layers, d_model = candidates[0]
        raise RuntimeError(
            "Could not map decoder cache dimensions to a known Whisper size: "
            f"layers={layers}, d_model={d_model}. "
            "The archive may use a different architecture."
        )

    raise RuntimeError(
        "Could not infer Whisper decoder layer count/d_model from its inputs."
    )


def log_softmax(logits: np.ndarray) -> np.ndarray:
    logits = np.asarray(logits, dtype=np.float64)
    shifted = logits - np.max(logits)
    return shifted - np.log(np.sum(np.exp(shifted)))


def suppress_special_tokens(
    log_probs: np.ndarray,
    tokenizer: WhisperTokenizer,
    allowed_tokens: set[int],
) -> None:
    for token_id in tokenizer.all_special_ids:
        if token_id not in allowed_tokens and 0 <= token_id < log_probs.size:
            log_probs[token_id] = -np.inf


def decode_greedy(
    decoder: ort.InferenceSession,
    cross_k: np.ndarray,
    cross_v: np.ndarray,
    prefix_tokens: list[int],
    eos_token_id: int,
    tokenizer: WhisperTokenizer,
    n_layers: int,
    d_model: int,
    cache_length: int,
) -> list[int]:
    token_name = required_input(decoder, "tokens")
    self_k_name = required_input(decoder, "in_n_layer_self_k_cache")
    self_v_name = required_input(decoder, "in_n_layer_self_v_cache")
    cross_k_name = required_input(decoder, "n_layer_cross_k")
    cross_v_name = required_input(decoder, "n_layer_cross_v")
    offset_name = required_input(decoder, "offset")

    self_k = np.zeros(
        (n_layers, 1, cache_length, d_model),
        dtype=np.float32,
    )
    self_v = np.zeros(
        (n_layers, 1, cache_length, d_model),
        dtype=np.float32,
    )

    first_outputs = decoder.run(
        None,
        {
            token_name: np.asarray([prefix_tokens], dtype=np.int64),
            self_k_name: self_k,
            self_v_name: self_v,
            cross_k_name: cross_k,
            cross_v_name: cross_v,
            offset_name: np.asarray([0], dtype=np.int64),
        },
    )

    log_probs = log_softmax(first_outputs[0][0, -1])
    suppress_special_tokens(
        log_probs,
        tokenizer,
        allowed_tokens={eos_token_id},
    )
    next_token = int(np.argmax(log_probs))

    generated = [next_token]
    self_k = np.asarray(first_outputs[1], dtype=np.float32)
    self_v = np.asarray(first_outputs[2], dtype=np.float32)

    if next_token == eos_token_id:
        return generated

    for _ in range(1, MAX_NEW_TOKENS):
        current_offset = len(prefix_tokens) + len(generated) - 1

        outputs = decoder.run(
            None,
            {
                token_name: np.asarray([[next_token]], dtype=np.int64),
                self_k_name: self_k,
                self_v_name: self_v,
                cross_k_name: cross_k,
                cross_v_name: cross_v,
                offset_name: np.asarray([current_offset], dtype=np.int64),
            },
        )

        log_probs = log_softmax(outputs[0][0, -1])
        suppress_special_tokens(
            log_probs,
            tokenizer,
            allowed_tokens={eos_token_id},
        )
        next_token = int(np.argmax(log_probs))
        generated.append(next_token)

        self_k = np.asarray(outputs[1], dtype=np.float32)
        self_v = np.asarray(outputs[2], dtype=np.float32)

        if next_token == eos_token_id:
            break

    return generated


def test_one(
    zip_path: Path,
    audio_path: Path,
    language: str,
    work_root: Path,
) -> None:
    print()
    print("=" * 72)
    print(f"TEST: {language.upper()}  |  {audio_path}")
    print("=" * 72)

    audio = read_audio(audio_path)
    print("Audio duration: %.2f s" % (audio.size / SAMPLE_RATE))

    encoder_name, decoder_name, _ = choose_model_files(zip_path)

    work_dir = work_root / language
    encoder_path = extract_entry(zip_path, encoder_name, work_dir)
    decoder_path = extract_entry(zip_path, decoder_name, work_dir)

    encoder = ort.InferenceSession(
        str(encoder_path),
        providers=["CPUExecutionProvider"],
    )
    decoder = ort.InferenceSession(
        str(decoder_path),
        providers=["CPUExecutionProvider"],
    )

    layers, d_model, model_size = infer_whisper_size(decoder)
    print("Detected Whisper size:", model_size)
    print("Decoder layers:", layers)
    print("Decoder d_model:", d_model)

    # The cache length is visible in the self-K cache input. Fall back to the
    # standard custom-export length when the dimension is symbolic.
    cache_length = 448
    for item in decoder.get_inputs():
        name = item.name.lower()
        if "self" in name and "k" in name:
            value = static_dim(list(item.shape), 2)
            if value:
                cache_length = value
                break

    extractor = WhisperFeatureExtractor.from_pretrained(
        f"openai/whisper-{model_size}"
    )
    tokenizer = WhisperTokenizer.from_pretrained(
        f"openai/whisper-{model_size}"
    )

    features = extractor(
        audio,
        sampling_rate=SAMPLE_RATE,
        return_tensors="np",
    )["input_features"]
    features = np.asarray(features, dtype=np.float32)

    print("Mel shape:", features.shape)
    print("Encoder inputs:", [x.name for x in encoder.get_inputs()])
    print("Decoder inputs:", [x.name for x in decoder.get_inputs()])

    mel_name = required_input(encoder, "mel")

    print("Running encoder...")
    encoder_outputs = encoder.run(
        None,
        {mel_name: features},
    )

    if len(encoder_outputs) < 2:
        raise RuntimeError(
            "Expected the custom Whisper encoder to return cross-attention "
            f"keys and values, but got {len(encoder_outputs)} outputs."
        )

    cross_k = np.asarray(encoder_outputs[0], dtype=np.float32)
    cross_v = np.asarray(encoder_outputs[1], dtype=np.float32)

    print("Cross-K shape:", cross_k.shape)
    print("Cross-V shape:", cross_v.shape)

    tokenizer.set_prefix_tokens(
        language=language,
        task="transcribe",
        predict_timestamps=False,
    )
    prefix_tokens = list(tokenizer.prefix_tokens)
    eos_token_id = int(tokenizer.eos_token_id or EOT_FALLBACK)

    print("Language:", language)
    print("Prefix token IDs:", prefix_tokens)
    print("EOS token:", eos_token_id)
    print("Running greedy decoder...")

    generated = decode_greedy(
        decoder=decoder,
        cross_k=cross_k,
        cross_v=cross_v,
        prefix_tokens=prefix_tokens,
        eos_token_id=eos_token_id,
        tokenizer=tokenizer,
        n_layers=layers,
        d_model=d_model,
        cache_length=cache_length,
    )

    all_tokens = prefix_tokens + generated
    transcript = tokenizer.decode(
        all_tokens,
        skip_special_tokens=True,
        normalize=False,
    ).strip()

    print()
    print("TRANSCRIPTION:")
    print(transcript or "<empty>")
    print("TRANSCRIPTION UNICODE:")
    print(
        transcript.encode("unicode_escape").decode("ascii")
        if transcript
        else "<empty>"
    )
    print("Generated tokens:", len(generated))
    print("SUCCESS")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--zip",
        dest="zip_path",
        type=Path,
        default=DEFAULT_ZIP,
        help=f"Model ZIP (default: {DEFAULT_ZIP})",
    )
    parser.add_argument(
        "--language",
        choices=("en", "hi", "bn"),
        help="Single language test.",
    )
    parser.add_argument(
        "--audio",
        type=Path,
        help="WAV for a single-language test.",
    )
    parser.add_argument(
        "--all",
        action="store_true",
        help="Run all three available recordings: English, Hindi, Bengali.",
    )
    parser.add_argument(
        "--work-dir",
        type=Path,
        default=Path("build/stt/andr2"),
    )
    args = parser.parse_args()

    if args.language and args.all:
        parser.error("Use either --language or --all, not both.")

    if not args.zip_path.is_file():
        raise FileNotFoundError(f"Model ZIP not found: {args.zip_path}")

    if args.language:
        audio_path = args.audio or DEFAULT_AUDIO[args.language]
        if not audio_path.is_file():
            raise FileNotFoundError(f"Audio file not found: {audio_path}")

        test_one(
            args.zip_path,
            audio_path,
            args.language,
            args.work_dir,
        )
        return 0

    # Default to all three test recordings so a bare invocation is useful.
    for language, audio_path in DEFAULT_AUDIO.items():
        if not audio_path.is_file():
            raise FileNotFoundError(
                f"Missing {language} test audio: {audio_path}"
            )

    for language, audio_path in DEFAULT_AUDIO.items():
        test_one(
            args.zip_path,
            audio_path,
            language,
            args.work_dir,
        )

    print()
    print("=" * 72)
    print("ALL THREE STT TESTS COMPLETED")
    print("=" * 72)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
