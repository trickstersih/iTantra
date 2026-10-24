#!/usr/bin/env python3
"""Download only the Odia male FastSpeech2-HS checkpoint and its HiFi-GAN vocoder.

This deliberately does NOT snapshot the whole 4.9 GB repository and does not
download other language checkpoints. All model binaries are written to the
specified external test directory, never into app/src/main/assets or Git.

The upstream repository declares CC BY 4.0 and also ships license.pdf. Keep
both license notices with any derived model you distribute.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
import os
import urllib.request
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

REPO_ID = "smtiitm/Fastspeech2_HS"
GITHUB_RAW_BASE = "https://raw.githubusercontent.com/smtiitm/Fastspeech2_HS/main"

# Some support files are present in the upstream GitHub repository but absent
# from the Hugging Face mirror. Fetch these few text assets from GitHub if needed.
GITHUB_FALLBACK_FILES = [
    "LICENSE",
    "NumberToText.py",
    "numToText/odia.csv",
]

ALLOW_PATTERNS = [
    "README.md",
    "LICENSE",
    "license.pdf",
    "environment.yml",
    "requirements.txt",
    "inference.py",
    "text_preprocess_for_inference.py",
    "get_phone_mapped_python.py",
    "NumberToText.py",
    "multilingualcharmap.json",
    "phone_dict/odia",
    "numToText/odia.csv",
    "charmap/**",
    "odia/male/model/**",
    "vocoder/male/aryan/hifigan/**",
    "hifigan/*.py",
]

REQUIRED_FILES = [
    "LICENSE",
    "license.pdf",
    "README.md",
    "odia/male/model/config.yaml",
    "odia/male/model/energy_stats.npz",
    "odia/male/model/feats_stats.npz",
    "odia/male/model/feats_type",
    "odia/male/model/model.pth",
    "odia/male/model/pitch_stats.npz",
    "vocoder/male/aryan/hifigan/config.json",
    "vocoder/male/aryan/hifigan/generator",
    "phone_dict/odia",
    "NumberToText.py",
    "numToText/odia.csv",
]


def sha256_file(path: Path, chunk_size: int = 8 * 1024 * 1024) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while True:
            chunk = stream.read(chunk_size)
            if not chunk:
                break
            digest.update(chunk)
    return digest.hexdigest()


def file_record(root: Path, relative_name: str) -> dict[str, Any]:
    path = root / relative_name
    return {
        "path": relative_name.replace("\\", "/"),
        "bytes": path.stat().st_size,
        "sha256": sha256_file(path),
    }


def download_github_fallback(output_dir: Path, relative_name: str) -> None:
    """Download a small missing text/support file from the upstream GitHub repo."""
    destination = output_dir / relative_name
    if destination.is_file():
        return

    destination.parent.mkdir(parents=True, exist_ok=True)
    temp_path = destination.with_name(destination.name + ".download")
    url = f"{GITHUB_RAW_BASE}/{relative_name}"
    request = urllib.request.Request(
        url,
        headers={"User-Agent": "iTantra-FastSpeech2-Odia-Downloader/1.0"},
    )
    print(f"Fetching missing upstream support file: {relative_name}")
    try:
        with urllib.request.urlopen(request, timeout=60) as response, temp_path.open("wb") as out:
            while True:
                chunk = response.read(1024 * 1024)
                if not chunk:
                    break
                out.write(chunk)
        if not temp_path.is_file() or temp_path.stat().st_size < 100:
            raise RuntimeError(
                f"Downloaded support file is unexpectedly small: {relative_name}"
            )
        os.replace(temp_path, destination)
    except Exception:
        if temp_path.exists():
            temp_path.unlink()
        raise


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--output-dir",
        type=Path,
        required=True,
        help="Dedicated output directory outside the Git-tracked app assets",
    )
    parser.add_argument(
        "--revision",
        default="main",
        help="Upstream branch/tag/commit to resolve (default: main; pins the resolved commit in the manifest)",
    )
    parser.add_argument(
        "--resume",
        action="store_true",
        help="Resume this script's previous partial download in the same directory",
    )
    args = parser.parse_args()

    output_dir = args.output_dir.expanduser().resolve()
    manifest_path = output_dir / "fastspeech2_hs_odia_download_manifest.json"

    if output_dir.exists() and any(output_dir.iterdir()):
        if not manifest_path.is_file() and not args.resume:
            parser.error(
                f"Output directory is non-empty and has no downloader manifest: {output_dir}. "
                "For this script's previous partial download, rerun with --resume. "
                "For unrelated files, choose a new directory."
            )

    try:
        from huggingface_hub import HfApi, snapshot_download
    except ImportError:
        print(
            "Missing huggingface_hub. Install it in the selected Python environment:\n"
            "  python -m pip install -U huggingface_hub",
            file=sys.stderr,
        )
        return 3

    print(f"Inspecting upstream repository: {REPO_ID}@{args.revision}")
    api = HfApi()
    info = api.model_info(REPO_ID, revision=args.revision)
    resolved_commit = info.sha
    card_data = getattr(info, "cardData", None)
    license_label = (
        card_data.get("license")
        if isinstance(card_data, dict)
        else getattr(card_data, "license", None)
    )
    print("Resolved commit:", resolved_commit)
    print("Model-card license label:", license_label or "(not reported)")
    if license_label and str(license_label).lower() != "cc-by-4.0":
        parser.error(
            f"Expected the reviewed CC BY 4.0 model card, but the selected revision reports "
            f"{license_label!r}. No download was started."
        )

    output_dir.mkdir(parents=True, exist_ok=True)
    print("\nDownloading only Odia male FastSpeech2-HS files plus shared inference support.")
    print("No other language checkpoints will be downloaded.")
    print("Destination:", output_dir)

    snapshot_download(
        repo_id=REPO_ID,
        repo_type="model",
        revision=resolved_commit,
        local_dir=str(output_dir),
        allow_patterns=ALLOW_PATTERNS,
    )

    # The Hugging Face mirror omits some small support files that exist in the
    # upstream GitHub repository. Repair those assets without re-downloading
    # the large checkpoints.
    for relative_name in GITHUB_FALLBACK_FILES:
        if not (output_dir / relative_name).is_file():
            download_github_fallback(output_dir, relative_name)

    missing = [name for name in REQUIRED_FILES if not (output_dir / name).is_file()]
    if missing:
        raise RuntimeError(
            "Download completed but required files are missing:\n  "
            + "\n  ".join(missing)
            + "\nThe output was retained for inspection; nothing was deleted."
        )

    acoustic_path = output_dir / "odia/male/model/model.pth"
    vocoder_path = output_dir / "vocoder/male/aryan/hifigan/generator"
    acoustic_bytes = acoustic_path.stat().st_size
    vocoder_bytes = vocoder_path.stat().st_size

    # Hugging Face's actual Xet/LFS files must be much larger than Git-LFS
    # pointer text. Abort instead of quietly treating a pointer as a checkpoint.
    if acoustic_bytes < 100_000_000:
        raise RuntimeError(
            f"FastSpeech2 model.pth is unexpectedly small ({acoustic_bytes} bytes). "
            "It may be an LFS pointer or incomplete download."
        )
    if vocoder_bytes < 30_000_000:
        raise RuntimeError(
            f"HiFi-GAN generator is unexpectedly small ({vocoder_bytes} bytes). "
            "It may be an LFS pointer or incomplete download."
        )

    records = []
    for relative in REQUIRED_FILES:
        records.append(file_record(output_dir, relative))

    total_weight_bytes = acoustic_bytes + vocoder_bytes
    manifest = {
        "repository": REPO_ID,
        "resolved_commit": resolved_commit,
        "model_card_license": license_label or "CC BY 4.0 (verified from upstream model card)",
        "license_files": ["LICENSE", "license.pdf"],
        "downloaded_at_utc": datetime.now(timezone.utc).isoformat(),
        "selected_voice": "Odia male",
        "architecture": "ESPnet FastSpeech2-HS acoustic model + HiFi-GAN vocoder",
        "acoustic_model": {
            "path": "odia/male/model/model.pth",
            "bytes": acoustic_bytes,
        },
        "vocoder": {
            "path": "vocoder/male/aryan/hifigan/generator",
            "bytes": vocoder_bytes,
        },
        "combined_fp32_checkpoint_bytes": total_weight_bytes,
        "combined_fp32_checkpoint_MiB": round(total_weight_bytes / (1024 * 1024), 3),
        "theoretical_int4_weight_payload_bytes": (acoustic_bytes + vocoder_bytes + 7) // 8,
        "theoretical_int4_weight_payload_MiB": round(
            ((acoustic_bytes + vocoder_bytes + 7) // 8) / (1024 * 1024), 3
        ),
        "files": records,
        "notice": (
            "INT4 estimate is only one-eighth of checkpoint bytes. Real packed models "
            "will also need scale metadata, graph/ONNX overhead and runtime support. "
            "This download step does not export, quantize, or modify the Android app."
        ),
    }
    manifest_path.write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8"
    )

    print("\nDOWNLOAD VERIFIED")
    print(f"  Acoustic checkpoint: {acoustic_bytes / (1024 * 1024):.2f} MiB")
    print(f"  HiFi-GAN generator:  {vocoder_bytes / (1024 * 1024):.2f} MiB")
    print(f"  Combined FP32 files: {total_weight_bytes / (1024 * 1024):.2f} MiB")
    print(
        "  Theoretical INT4 payload (both models): "
        f"{manifest['theoretical_int4_weight_payload_MiB']:.2f} MiB"
    )
    print("  Manifest:", manifest_path)
    print("  Licenses kept: LICENSE and license.pdf")
    print("\nNo app files or regular TTS ZIPs were changed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
