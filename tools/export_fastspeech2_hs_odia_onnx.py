#!/usr/bin/env python3
"""Export FastSpeech2-HS Odia male acoustic model and HiFi-GAN to ONNX.

The script does graph conversion only: it does not generate or listen to speech,
quantize weights, or touch Android assets. Text must be normalized/phonified to
the checkpoint's token IDs before entering fastspeech2_odia.fp32.onnx.

The dynamic length-regulator rewrite replaces repeat_interleave with a
cumulative-duration/index-select graph so the exported ONNX can produce a
variable number of mel frames. This is experimental; exporter/runtime support
must be confirmed before proceeding to INT4 quantization.
"""
from __future__ import annotations

import argparse
import json
import shutil
import sys
from pathlib import Path
from types import MethodType
from typing import Any

import numpy as np


def die_for_missing_dependencies() -> None:
    try:
        import torch  # noqa: F401
        import onnx  # noqa: F401
        import yaml  # noqa: F401
        from espnet2.bin.tts_inference import Text2Speech  # noqa: F401
    except ImportError as exc:
        raise RuntimeError(
            f"Missing export dependency: {exc}\n"
            "Use a separate environment if possible; do not disrupt the SYSPIN test venv.\n"
            "The upstream project used ESPnet 202209. Install compatible PyTorch, "
            "ESPnet, PyYAML, ONNX and ONNXScript versions in an isolated environment."
        ) from exc


def dynamic_length_regulator_forward(self, xs, ds, alpha=1.0):
    """ONNX-friendly variable-length expansion for batch size one."""
    import torch

    if alpha != 1.0:
        ds = torch.round(ds.float() * alpha).long()
    else:
        ds = ds.long()

    # Preserve the upstream behavior for an all-zero duration prediction.
    empty_rows = ds.sum(dim=1, keepdim=True).eq(0)
    ds = torch.where(empty_rows, torch.ones_like(ds), ds)

    cumulative_ends = torch.cumsum(ds, dim=1)[0]  # [text_tokens]
    total_frames = cumulative_ends[-1]
    frame_positions = torch.arange(
        0, total_frames, 1, dtype=torch.long, device=xs.device
    )
    # A frame belongs to token i when it is >= all prior cumulative ends and
    # < its own end. Counting passed ends yields its token index.
    token_indices = (
        frame_positions.unsqueeze(1) >= cumulative_ends.unsqueeze(0)
    ).sum(dim=1)
    token_indices = token_indices.clamp(min=0, max=xs.shape[1] - 1)
    return xs[0].index_select(0, token_indices).unsqueeze(0)


def dynamic_make_pad_mask(lengths, xs=None, length_dim=-1, maxlen=None):
    """Dynamic-shape replacement for ESPnet's Python-list-based pad mask."""
    import torch

    if maxlen is None:
        if xs is None:
            maxlen = lengths[0]
        else:
            maxlen = xs.size(length_dim)
    sequence = torch.arange(0, maxlen, dtype=torch.long, device=lengths.device)
    mask = sequence.unsqueeze(0) >= lengths.unsqueeze(-1)
    if xs is not None:
        if length_dim < 0:
            length_dim = xs.dim() + length_dim
        ind = tuple(
            slice(None) if i in (0, length_dim) else None
            for i in range(xs.dim())
        )
        mask = mask[ind].expand_as(xs).to(xs.device)
    return mask


def dynamic_source_mask(self, ilens):
    """Dynamic non-padding mask for FastSpeech2's encoder."""
    import torch

    sequence = torch.arange(0, ilens[0], dtype=torch.long, device=ilens.device)
    return (sequence.unsqueeze(0) < ilens.unsqueeze(-1)).unsqueeze(-2)


def safe_export(torch, model, args, path: Path, **kwargs) -> None:
    """Use the legacy TorchScript exporter for broadly reproducible ONNX."""
    try:
        torch.onnx.export(
            model,
            args,
            str(path),
            dynamo=False,
            **kwargs,
        )
    except TypeError as exc:
        # Older Torch releases do not expose the dynamo argument.
        if "dynamo" not in str(exc):
            raise
        torch.onnx.export(model, args, str(path), **kwargs)


def load_vocoder(source_dir: Path, torch):
    hifigan_dir = source_dir / "hifigan"
    sys.path.insert(0, str(hifigan_dir))
    try:
        from env import AttrDict
        from models import Generator
    except ImportError as exc:
        raise RuntimeError(
            f"Could not import the downloaded HiFi-GAN source from {hifigan_dir}: {exc}"
        ) from exc

    config_path = source_dir / "vocoder/male/aryan/hifigan/config.json"
    checkpoint_path = source_dir / "vocoder/male/aryan/hifigan/generator"
    config = json.loads(config_path.read_text(encoding="utf-8"))
    generator = Generator(AttrDict(config)).cpu()
    try:
        checkpoint = torch.load(
            str(checkpoint_path), map_location="cpu", weights_only=False
        )
    except TypeError:
        checkpoint = torch.load(str(checkpoint_path), map_location="cpu")

    if not isinstance(checkpoint, dict) or "generator" not in checkpoint:
        raise RuntimeError(
            "Unexpected HiFi-GAN checkpoint structure: expected a dict with key 'generator'."
        )
    generator.load_state_dict(checkpoint["generator"], strict=True)
    generator.eval()
    if hasattr(generator, "remove_weight_norm"):
        generator.remove_weight_norm()
    return generator


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--source-dir",
        type=Path,
        required=True,
        help="Downloaded FastSpeech2-HS Odia source directory",
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        required=True,
        help="Fresh directory for FP32 ONNX graphs and export config",
    )
    parser.add_argument(
        "--opset",
        type=int,
        default=17,
        help="ONNX export opset (default 17; INT4 script will upgrade to 21)",
    )
    args = parser.parse_args()

    source_dir = args.source_dir.expanduser().resolve()
    output_dir = args.output_dir.expanduser().resolve()
    if not source_dir.is_dir():
        parser.error(f"Source directory not found: {source_dir}")
    if args.opset < 14:
        parser.error("--opset must be at least 14 for this export path")

    required = [
        "odia/male/model/model.pth",
        "odia/male/model/config.yaml",
        "odia/male/model/feats_stats.npz",
        "odia/male/model/pitch_stats.npz",
        "odia/male/model/energy_stats.npz",
        "vocoder/male/aryan/hifigan/generator",
        "vocoder/male/aryan/hifigan/config.json",
        "hifigan/models.py",
        "hifigan/env.py",
        "hifigan/utils.py",
    ]
    missing = [name for name in required if not (source_dir / name).is_file()]
    if missing:
        parser.error("Missing downloaded source files:\n  " + "\n  ".join(missing))

    if output_dir.exists() and any(output_dir.iterdir()):
        parser.error(
            f"Output directory must be empty to protect previous export results: {output_dir}"
        )
    output_dir.mkdir(parents=True, exist_ok=True)

    die_for_missing_dependencies()
    import onnx
    import torch
    import yaml
    from espnet2.bin.tts_inference import Text2Speech

    print("PyTorch:", torch.__version__)
    print("ONNX:", onnx.__version__)
    print("Output directory:", output_dir)

    source_config_path = source_dir / "odia/male/model/config.yaml"
    config = yaml.safe_load(source_config_path.read_text(encoding="utf-8"))

    # The upstream YAML has Linux absolute paths; make a local copy and rewrite
    # only its three normalization-stat paths. Never edit the downloaded source.
    config_updates = [
        ("normalize_conf", "feats_stats.npz"),
        ("pitch_normalize_conf", "pitch_stats.npz"),
        ("energy_normalize_conf", "energy_stats.npz"),
    ]
    for key, filename in config_updates:
        if key not in config or not isinstance(config[key], dict):
            raise RuntimeError(f"Unexpected config: missing mapping {key!r}")
        config[key]["stats_file"] = str(
            (source_dir / "odia/male/model" / filename).resolve()
        )

    export_config_path = output_dir / "config.export.yaml"
    export_config_path.write_text(
        yaml.safe_dump(config, sort_keys=False, allow_unicode=True),
        encoding="utf-8",
    )

    acoustic_path = source_dir / "odia/male/model/model.pth"
    print("\nLoading ESPnet FastSpeech2 checkpoint (CPU)...")
    espnet_model = Text2Speech(
        train_config=str(export_config_path),
        model_file=str(acoustic_path),
        device="cpu",
        dtype="float32",
    )
    espnet_model.model.eval()

    # Patch only the in-memory model instance for ONNX export; the .pth file
    # and downloaded upstream inference code remain untouched.
    # ESPnet's standard padding-mask helper converts lengths to Python lists,
    # which freezes the traced input length. Replace it in-memory with dynamic
    # tensor operations so the ONNX graph accepts sentences of different lengths.
    import espnet2.tts.fastspeech2.fastspeech2 as fastspeech2_impl
    fastspeech2_impl.make_pad_mask = dynamic_make_pad_mask
    espnet_model.model.tts._source_mask = MethodType(
        dynamic_source_mask, espnet_model.model.tts
    )
    espnet_model.model.tts.length_regulator.forward = MethodType(
        dynamic_length_regulator_forward,
        espnet_model.model.tts.length_regulator,
    )

    class AcousticWrapper(torch.nn.Module):
        def __init__(self, espnet_model):
            super().__init__()
            self.espnet_model = espnet_model.model
            self.tts = espnet_model.model.tts
            self.normalizer = espnet_model.model.normalize

        def forward(self, token_ids):
            import torch.nn.functional as F

            # Export interface: rank-1 INT64 sequence of model token IDs.
            x = F.pad(token_ids, [0, 1], "constant", self.tts.eos)
            xs = x.unsqueeze(0)
            dynamic_shape = torch._shape_as_tensor(xs)
            ilens = dynamic_shape[1].reshape(1).to(dtype=torch.long, device=xs.device)

            _, features, _, _, _ = self.tts._forward(
                xs,
                ilens,
                is_inference=True,
                alpha=1.0,
            )
            if self.normalizer is not None:
                features = self.normalizer.inverse(features.clone())
            return features.transpose(1, 2)  # [1, 80, mel_frames]

    acoustic_wrapper = AcousticWrapper(espnet_model).cpu().eval()
    token_list = config.get("token_list", [])
    if not isinstance(token_list, list) or len(token_list) < 8:
        raise RuntimeError("Config token_list is missing or unexpectedly small.")
    reserved = {"<blank>", "<unk>", "<sos/eos>"}
    dummy_values = [
        idx for idx, value in enumerate(token_list) if value not in reserved
    ][:18]
    if len(dummy_values) < 4:
        raise RuntimeError("Could not construct a representative dummy token sequence.")
    dummy_ids = torch.tensor(dummy_values, dtype=torch.long)

    acoustic_out = output_dir / "fastspeech2_odia.fp32.onnx"
    print("\nExporting FastSpeech2 ->", acoustic_out)
    try:
        safe_export(
            torch,
            acoustic_wrapper,
            (dummy_ids,),
            acoustic_out,
            opset_version=args.opset,
            input_names=["token_ids"],
            output_names=["mel"],
            dynamic_axes={
                "token_ids": {0: "text_tokens"},
                "mel": {2: "mel_frames"},
            },
            do_constant_folding=False,
            export_params=True,
        )
    except Exception as exc:
        raise RuntimeError(
            "FastSpeech2 ONNX export failed. This architecture has a data-dependent "
            "duration/length regulator; do not quantize a partial/incorrect graph. "
            "Full exporter traceback follows."
        ) from exc

    print("\nLoading HiFi-GAN checkpoint (CPU)...")
    vocoder = load_vocoder(source_dir, torch).cpu().eval()

    class VocoderWrapper(torch.nn.Module):
        def __init__(self, generator):
            super().__init__()
            self.generator = generator

        def forward(self, mel):
            return self.generator(mel)

    vocoder_wrapper = VocoderWrapper(vocoder).cpu().eval()
    dummy_mel = torch.zeros((1, 80, 64), dtype=torch.float32)
    vocoder_out = output_dir / "hifigan_odia.fp32.onnx"
    print("\nExporting HiFi-GAN ->", vocoder_out)
    try:
        safe_export(
            torch,
            vocoder_wrapper,
            (dummy_mel,),
            vocoder_out,
            opset_version=args.opset,
            input_names=["mel"],
            output_names=["audio"],
            dynamic_axes={
                "mel": {2: "mel_frames"},
                "audio": {2: "audio_samples"},
            },
            do_constant_folding=False,
            export_params=True,
        )
    except Exception as exc:
        raise RuntimeError(
            "HiFi-GAN ONNX export failed. Full exporter traceback follows."
        ) from exc

    outputs = []
    for path in (acoustic_out, vocoder_out):
        onnx.checker.check_model(str(path))
        outputs.append({
            "path": str(path),
            "bytes": path.stat().st_size,
            "MiB": round(path.stat().st_size / (1024 * 1024), 3),
        })

    # Load graph sessions without generating any speech/audio, if ORT is installed.
    try:
        import onnxruntime as ort
        for path in (acoustic_out, vocoder_out):
            ort.InferenceSession(str(path), providers=["CPUExecutionProvider"])
        ort_version = ort.__version__
    except ImportError:
        ort_version = None
        print("onnxruntime is not installed; ONNX graph checker passed, but ORT load was skipped.")

    manifest = {
        "upstream_repository": "smtiitm/Fastspeech2_HS",
        "source_dir": str(source_dir),
        "opset": args.opset,
        "torch_version": torch.__version__,
        "onnx_version": onnx.__version__,
        "onnxruntime_version": ort_version,
        "exported_models": outputs,
        "input_contract": {
            "fastspeech2": "token_ids: rank-1 int64 array of model token IDs (not raw Odia Unicode text)",
            "fastspeech2_output": "mel: float32 [1, 80, mel_frames]",
            "hifigan_input": "mel: float32 [1, 80, mel_frames]",
            "hifigan_output": "audio: float32 [1, 1, audio_samples]",
            "sample_rate_hz": 22050,
        },
        "note": (
            "No speech was generated or listened to. The exporter changes the "
            "in-memory length regulator only. Validate output alignment and actual "
            "runtime behavior before integrating into Android."
        ),
    }
    (output_dir / "fastspeech2_hs_odia_onnx_manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8"
    )

    print("\nONNX EXPORT COMPLETE (no audio test performed)")
    for item in outputs:
        print(f"  {item['path']}: {item['MiB']:.2f} MiB")
    print("  Manifest:", output_dir / "fastspeech2_hs_odia_onnx_manifest.json")
    print("No app assets, existing ZIPs, or source checkpoints were changed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
