#!/usr/bin/env python3
"""
Desktop test runner for the multilingual andr2 STT bundle.

Model archive:
  app/src/main/assets/models/andr2.zip

This runner intentionally uses ONLY files shipped inside andr2.zip:
  - encoder_int8.onnx
  - decoder_fp32.onnx
  - tokenizer/tokenizer.json
  - tokenizer/vocab.json
  - mel_filters_80x201.npy
  - preprocess.json
  - vocab_map.json

It reproduces the preprocessing/decoding contract documented by
andr2/preprocess.json instead of assuming the previous Whisper export.

Available local recordings:
  en -> english.wav
  hi -> hindi.wav
  bn -> bengali.wav

Examples:
  python tools/test_andr2_stt.py --language en --audio .\\english_16k.wav
  python tools/test_andr2_stt.py --language hi --audio .\\hindi_16k.wav
  python tools/test_andr2_stt.py --language bn --audio .\\bengali_16k.wav
  python tools/test_andr2_stt.py --all

Dependencies:
  python -m pip install onnxruntime numpy soundfile tokenizers
"""

from __future__ import annotations

import argparse
import json
import zipfile
from pathlib import Path

import numpy as np
import onnxruntime as ort
import soundfile as sf
from tokenizers import Tokenizer


DEFAULT_ZIP = Path("app/src/main/assets/models/andr2.zip")
DEFAULT_AUDIO = {
    "en": Path("english.wav"),
    "hi": Path("hindi.wav"),
    "bn": Path("bengali.wav"),
}

SAMPLE_RATE = 16_000
N_FFT = 400
HOP_LENGTH = 160
N_MELS = 80
TARGET_SAMPLES = 160_000
TARGET_FRAMES = 1_000
DEFAULT_EOT_ID = 5167
PREFIX_NEW_IDS = {
    "hi": [5168, 5170, 5179, 5180],
    "gu": [5168, 5177, 5179, 5180],
    "mr": [5168, 5176, 5179, 5180],
    "kn": [5168, 5175, 5179, 5180],
    "ml": [5168, 5172, 5179, 5180],
    "ta": [5168, 5171, 5179, 5180],
    "te": [5168, 5173, 5179, 5180],
    "or": [5168, 5178, 5179, 5180],
    "bn": [5168, 5174, 5179, 5180],
    "en": [5168, 5169, 5179, 5180],
}
SUPPRESS_NEW_IDS = tuple(range(5168, 5181))
MAX_NEW_TOKENS = 192
REPEAT_NGRAM = 4
REPEAT_COUNT = 3


def extract_required_files(zip_path: Path, work_dir: Path) -> dict[str, Path]:
    required = {
        "encoder": "andr2/encoder_int8.onnx",
        "decoder": "andr2/decoder_fp32.onnx",
        "tokenizer": "andr2/tokenizer/tokenizer.json",
        "vocab": "andr2/tokenizer/vocab.json",
        "mel_filters": "andr2/mel_filters_80x201.npy",
        "preprocess": "andr2/preprocess.json",
        "vocab_map": "andr2/vocab_map.json",
    }

    work_dir.mkdir(parents=True, exist_ok=True)

    with zipfile.ZipFile(zip_path) as archive:
        names = set(archive.namelist())
        missing = sorted(set(required.values()) - names)
        if missing:
            raise RuntimeError(
                "andr2.zip is missing required files:\\n  " +
                "\\n  ".join(missing)
            )

        extracted: dict[str, Path] = {}
        for key, archive_name in required.items():
            target = work_dir / Path(archive_name).name

            if not target.exists():
                print("Extracting:", archive_name)
                with archive.open(archive_name) as source, target.open("wb") as out:
                    while True:
                        chunk = source.read(1024 * 1024)
                        if not chunk:
                            break
                        out.write(chunk)

            extracted[key] = target

    return extracted


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

    # The supplied preprocessing contract expects exactly 10 seconds.
    # Zero-padding is used when the recording is shorter; longer audio is
    # truncated for this first model-validation pass.
    if audio.size < TARGET_SAMPLES:
        audio = np.pad(audio, (0, TARGET_SAMPLES - audio.size))
    elif audio.size > TARGET_SAMPLES:
        audio = audio[:TARGET_SAMPLES]

    return audio


def periodic_hann(length: int) -> np.ndarray:
    # numpy.hanning uses a symmetric window. The model explicitly says
    # hann(periodic), which matches scipy's periodic Hann definition:
    # 0.5 - 0.5*cos(2*pi*n/N).
    n = np.arange(length, dtype=np.float32)
    return 0.5 - 0.5 * np.cos((2.0 * np.pi * n) / length)


def compute_mel(audio: np.ndarray, mel_filters: np.ndarray) -> np.ndarray:
    """
    Reproduce preprocess.json:

      400 FFT
      160 hop
      80 mel bins
      periodic Hann
      reflect pad
      power 2
      bundled 80x201 mel filters
      log10(clamp 1e-10)
      max-8
      (x+4)/4
      drop last STFT frame

    Result shape: [1, 80, 1000].
    """
    if audio.size != TARGET_SAMPLES:
        raise ValueError(f"Expected {TARGET_SAMPLES} samples, got {audio.size}")
    if mel_filters.shape != (N_MELS, N_FFT // 2 + 1):
        raise ValueError(
            f"Expected mel filters {(N_MELS, N_FFT // 2 + 1)}, "
            f"got {mel_filters.shape}"
        )

    pad = N_FFT // 2
    padded = np.pad(audio, (pad, pad), mode="reflect")

    window = periodic_hann(N_FFT)
    frame_count = 1 + (padded.size - N_FFT) // HOP_LENGTH

    # [frames, fft_bins]
    frames = np.lib.stride_tricks.sliding_window_view(
        padded,
        N_FFT,
    )[::HOP_LENGTH]

    if frames.shape[0] != frame_count:
        raise RuntimeError(
            f"Unexpected frame count: {frames.shape[0]} != {frame_count}"
        )

    frames = frames * window
    spectrum = np.fft.rfft(frames, n=N_FFT, axis=-1)
    power = np.square(np.abs(spectrum)).astype(np.float32)

    mel = power @ mel_filters.T
    mel = np.maximum(mel, 1e-10)
    mel = np.log10(mel)

    mel_max = float(np.max(mel))
    mel = np.maximum(mel, mel_max - 8.0)
    mel = (mel + 4.0) / 4.0

    # Drop the final STFT frame exactly as preprocess.json specifies.
    mel = mel[:-1]

    if mel.shape != (TARGET_FRAMES, N_MELS):
        raise RuntimeError(
            f"Unexpected mel shape after preprocessing: {mel.shape}; "
            f"expected {(TARGET_FRAMES, N_MELS)}"
        )

    return np.ascontiguousarray(mel.T[None, :, :], dtype=np.float32)


def inspect_runtime_inputs(
    encoder: ort.InferenceSession,
    decoder: ort.InferenceSession,
) -> None:
    print()
    print("=== ONNX INTERFACES ===")
    print("Encoder inputs:")
    for item in encoder.get_inputs():
        print(" ", item.name, item.type, item.shape)

    print("Encoder outputs:")
    for item in encoder.get_outputs():
        print(" ", item.name, item.type, item.shape)

    print("Decoder inputs:")
    for item in decoder.get_inputs():
        print(" ", item.name, item.type, item.shape)

    print("Decoder outputs:")
    for item in decoder.get_outputs():
        print(" ", item.name, item.type, item.shape)


def validate_metadata(
    preprocess_path: Path,
    vocab_map_path: Path,
) -> tuple[dict[str, list[int]], int, list[int]]:
    preprocess = json.loads(preprocess_path.read_text(encoding="utf-8"))
    vocab_map = json.loads(vocab_map_path.read_text(encoding="utf-8"))

    sample_rate = int(preprocess["sample_rate"])
    frames = int(preprocess["frames"])
    prefix_map = {
        str(k): [int(x) for x in v]
        for k, v in preprocess["prefix_new_ids"].items()
    }
    eot_id = int(preprocess["eot_new_id"])
    suppress_ids = [
        int(x) for x in preprocess["suppress_new_ids"]
    ]
    new_to_old = [int(x) for x in vocab_map["new_to_old"]]

    if sample_rate != SAMPLE_RATE:
        raise RuntimeError(
            f"andr2 expects {sample_rate} Hz according to preprocess.json, "
            f"not {SAMPLE_RATE} Hz."
        )
    if frames != TARGET_FRAMES:
        raise RuntimeError(
            f"andr2 expects {frames} mel frames, not {TARGET_FRAMES}."
        )

    print()
    print("=== MODEL METADATA ===")
    print("Sample rate:", sample_rate)
    print("Frames:", frames)
    print("EOT new ID:", eot_id)
    print("Exported vocabulary size:", len(new_to_old))
    print("Suppressed new IDs:", suppress_ids)

    return prefix_map, eot_id, new_to_old


def suppress_special(
    logits: np.ndarray,
    suppress_ids: list[int],
) -> None:
    for token_id in suppress_ids:
        if 0 <= token_id < logits.size:
            logits[token_id] = -np.inf


def has_repeated_4gram_x3(tokens: list[int]) -> bool:
    if len(tokens) < REPEAT_NGRAM * REPEAT_COUNT:
        return False

    tail = tokens[-REPEAT_NGRAM * REPEAT_COUNT :]
    gram = tail[:REPEAT_NGRAM]

    return (
        tail[REPEAT_NGRAM : 2 * REPEAT_NGRAM] == gram
        and tail[2 * REPEAT_NGRAM :] == gram
    )


def update_cache_shapes(
    self_k: np.ndarray,
    self_v: np.ndarray,
) -> None:
    if self_k.shape != self_v.shape:
        raise RuntimeError(
            f"Decoder cache shapes differ: {self_k.shape} vs {self_v.shape}"
        )


def run_encoder(
    encoder: ort.InferenceSession,
    features: np.ndarray,
) -> tuple[np.ndarray, np.ndarray]:
    input_names = [x.name for x in encoder.get_inputs()]
    if input_names != ["input_features"]:
        mel_name = next(
            (x.name for x in encoder.get_inputs() if x.name == "input_features"),
            None,
        )
        if mel_name is None:
            raise RuntimeError(
                f"Expected encoder input_features. Inputs: {input_names}"
            )
    else:
        mel_name = "input_features"

    outputs = encoder.run(None, {mel_name: features})

    if len(outputs) != 2:
        raise RuntimeError(
            f"Expected 2 encoder outputs, got {len(outputs)}"
        )

    cross_k = np.asarray(outputs[0], dtype=np.float32)
    cross_v = np.asarray(outputs[1], dtype=np.float32)

    expected = (4, 1, 6, 500, 64)
    if cross_k.shape != expected or cross_v.shape != expected:
        raise RuntimeError(
            "Unexpected encoder output shapes. "
            f"cross_k={cross_k.shape}, cross_v={cross_v.shape}, "
            f"expected={expected}"
        )

    return cross_k, cross_v


def run_decoder(
    decoder: ort.InferenceSession,
    cross_k: np.ndarray,
    cross_v: np.ndarray,
    prefix_tokens: list[int],
    eot_id: int,
    suppress_ids: list[int],
) -> list[int]:
    input_names = {x.name for x in decoder.get_inputs()}
    required = {
        "input_id",
        "position",
        "self_k",
        "self_v",
        "cross_k",
        "cross_v",
    }
    missing = required - input_names
    if missing:
        raise RuntimeError(
            "Decoder is missing required inputs: " +
            ", ".join(sorted(missing))
        )

    self_k = np.zeros(
        (4, 1, 6, 0, 64),
        dtype=np.float32,
    )
    self_v = np.zeros(
        (4, 1, 6, 0, 64),
        dtype=np.float32,
    )

    logits: np.ndarray | None = None

    # The model contract explicitly says:
    # "feed prefix tokens one by one (position 0..3)".
    for position, token_id in enumerate(prefix_tokens):
        outputs = decoder.run(
            None,
            {
                "input_id": np.asarray([token_id], dtype=np.int64).reshape(1, 1),
                "position": np.asarray([position], dtype=np.int64),
                "self_k": self_k,
                "self_v": self_v,
                "cross_k": cross_k,
                "cross_v": cross_v,
            },
        )

        if len(outputs) != 3:
            raise RuntimeError(
                f"Expected decoder to return logits/new_self_k/new_self_v; got {len(outputs)}"
            )

        logits = np.asarray(outputs[0], dtype=np.float32)
        self_k = np.asarray(outputs[1], dtype=np.float32)
        self_v = np.asarray(outputs[2], dtype=np.float32)
        update_cache_shapes(self_k, self_v)

    if logits is None:
        raise RuntimeError("Decoder produced no logits.")

    generated: list[int] = []

    for step in range(MAX_NEW_TOKENS):
        next_logits = logits.reshape(-1).copy()

        if next_logits.size != 5181:
            raise RuntimeError(
                f"Expected decoder vocabulary size 5181, got {next_logits.size}"
            )

        suppress_special(next_logits, suppress_ids)

        next_id = int(np.argmax(next_logits))
        generated.append(next_id)

        if next_id == eot_id:
            generated.pop()
            break

        if has_repeated_4gram_x3(generated):
            # preprocess.json says: "then drop 8"
            del generated[-8:]
            break

        position = len(prefix_tokens) + len(generated) - 1

        outputs = decoder.run(
            None,
            {
                "input_id": np.asarray([next_id], dtype=np.int64).reshape(1, 1),
                "position": np.asarray([position], dtype=np.int64),
                "self_k": self_k,
                "self_v": self_v,
                "cross_k": cross_k,
                "cross_v": cross_v,
            },
        )

        logits = np.asarray(outputs[0], dtype=np.float32)
        self_k = np.asarray(outputs[1], dtype=np.float32)
        self_v = np.asarray(outputs[2], dtype=np.float32)
        update_cache_shapes(self_k, self_v)

    return generated


def decode_new_tokens(
    generated_new_ids: list[int],
    new_to_old: list[int],
    tokenizer: Tokenizer,
) -> str:
    old_ids: list[int] = []

    for new_id in generated_new_ids:
        if new_id < 0 or new_id >= len(new_to_old):
            raise RuntimeError(
                f"Generated token ID {new_id} is outside vocab map "
                f"of size {len(new_to_old)}"
            )
        old_ids.append(new_to_old[new_id])

    # The special prefix tokens are not included here; only generated speech
    # tokens are detokenized.
    text = tokenizer.decode(old_ids, skip_special_tokens=True)
    return text.strip()


def test_one(
    zip_path: Path,
    audio_path: Path,
    language: str,
    work_root: Path,
) -> None:
    print()
    print("=" * 72)
    print(f"TEST: {language.upper()} | {audio_path}")
    print("=" * 72)

    if language not in PREFIX_NEW_IDS:
        raise ValueError(f"Unsupported language: {language}")

    audio = read_audio(audio_path)
    print("Audio duration used: %.2f s" % (audio.size / SAMPLE_RATE))

    work_dir = work_root / language
    files = extract_required_files(zip_path, work_dir)

    prefix_map, eot_id, new_to_old = validate_metadata(
        files["preprocess"],
        files["vocab_map"],
    )

    prefix_tokens = prefix_map.get(language)
    if prefix_tokens is None:
        raise RuntimeError(
            f"No prefix_new_ids entry for language {language!r}"
        )

    tokenizer = Tokenizer.from_file(str(files["tokenizer"]))

    mel_filters = np.load(files["mel_filters"]).astype(np.float32)
    features = compute_mel(audio, mel_filters)

    encoder = ort.InferenceSession(
        str(files["encoder"]),
        providers=["CPUExecutionProvider"],
    )
    decoder = ort.InferenceSession(
        str(files["decoder"]),
        providers=["CPUExecutionProvider"],
    )

    inspect_runtime_inputs(encoder, decoder)

    print()
    print("Feature shape:", features.shape)
    print("Language:", language)
    print("Prefix:", prefix_tokens)
    print("Running encoder...")

    cross_k, cross_v = run_encoder(encoder, features)

    print("Cross-K shape:", cross_k.shape)
    print("Cross-V shape:", cross_v.shape)
    print("Running decoder...")

    generated = run_decoder(
        decoder,
        cross_k,
        cross_v,
        prefix_tokens,
        eot_id,
        suppress_ids=list(SUPPRESS_NEW_IDS),
    )

    transcript = decode_new_tokens(
        generated,
        new_to_old,
        tokenizer,
    )

    print()
    print("Generated tokens:", len(generated))
    print("TRANSCRIPTION:")
    print(transcript or "<empty>")
    print("TRANSCRIPTION UNICODE:")
    print(
        transcript.encode("unicode_escape").decode("ascii")
        if transcript
        else "<empty>"
    )
    print("SUCCESS")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--zip",
        dest="zip_path",
        type=Path,
        default=DEFAULT_ZIP,
        help=f"andr2 model ZIP (default: {DEFAULT_ZIP})",
    )
    parser.add_argument(
        "--language",
        choices=("en", "hi", "bn"),
        help="Run one test.",
    )
    parser.add_argument(
        "--audio",
        type=Path,
        help="WAV file for a single-language test.",
    )
    parser.add_argument(
        "--all",
        action="store_true",
        help="Run English, Hindi, and Bengali tests.",
    )
    parser.add_argument(
        "--work-dir",
        type=Path,
        default=Path("build/stt/andr2"),
        help="Extraction/work directory.",
    )
    args = parser.parse_args()

    if args.language and args.all:
        parser.error("Use either --language or --all, not both.")

    if not args.zip_path.is_file():
        raise FileNotFoundError(
            f"Model ZIP not found: {args.zip_path}"
        )

    if args.language:
        audio_path = args.audio or DEFAULT_AUDIO[args.language]
        if not audio_path.is_file():
            raise FileNotFoundError(
                f"Audio file not found: {audio_path}"
            )
        test_one(
            args.zip_path,
            audio_path,
            args.language,
            args.work_dir,
        )
        return 0

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
