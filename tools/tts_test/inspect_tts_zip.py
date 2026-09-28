#!/usr/bin/env python3
from __future__ import annotations
import argparse, hashlib, json, zipfile
from pathlib import Path, PurePosixPath

LANGUAGES = {
    "english": ("English", "en"), "bengali": ("Bengali", "bn"),
    "gujarati": ("Gujarati", "gu"), "hindi": ("Hindi", "hi"),
    "kannada": ("Kannada", "kn"), "malayalam": ("Malayalam", "ml"),
    "marathi": ("Marathi", "mr"), "odia": ("Odia", "or"),
    "tamil": ("Tamil", "ta"), "telugu": ("Telugu", "te"),
}

def sha256_stream(zf, member):
    h = hashlib.sha256()
    with zf.open(member) as f:
        while chunk := f.read(1024 * 1024):
            h.update(chunk)
    return h.hexdigest()

def find_config(names, model):
    for candidate in (
        model + ".json",
        str(PurePosixPath(model).with_suffix(".json")),
        str(PurePosixPath(model).parent / "config.json"),
        str(PurePosixPath(model).parent / "model.json"),
    ):
        if candidate in names:
            return candidate
    same_dir = PurePosixPath(model).parent
    js = [n for n in names if PurePosixPath(n).parent == same_dir and n.endswith(".json")]
    return js[0] if len(js) == 1 else None

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("zip_path")
    args = ap.parse_args()
    path = Path(args.zip_path).expanduser().resolve()
    if not path.is_file():
        raise SystemExit(f"ZIP not found: {path}")
    with zipfile.ZipFile(path) as zf:
        if zf.testzip():
            raise SystemExit("ZIP CRC check failed")
        names = {i.filename.replace("\\", "/") for i in zf.infolist() if not i.is_dir()}
        models = sorted(n for n in names if n.lower().endswith(".onnx"))
        print(f"ZIP: {path}")
        print(f"ONNX models: {len(models)}")
        total = 0
        for i, model in enumerate(models, 1):
            info = zf.getinfo(model)
            total += info.file_size
            cfg_name = find_config(names, model)
            print(f"[{i:02d}] {model}  {info.file_size / 1048576:.2f} MiB")
            print(f"     sha256={sha256_stream(zf, model)}")
            print(f"     config={cfg_name or 'MISSING'}")
            if cfg_name:
                try:
                    with zf.open(cfg_name) as f:
                        cfg = json.load(f)
                    lang = cfg.get("language", {})
                    audio = cfg.get("audio", {})
                    print(f"     language={lang.get('code') if isinstance(lang, dict) else None}")
                    print(f"     espeak={lang.get('espeak', {}).get('voice') if isinstance(lang, dict) and isinstance(lang.get('espeak'), dict) else None}")
                    print(f"     sample_rate={audio.get('sample_rate') if isinstance(audio, dict) else None}")
                    print(f"     speakers={cfg.get('num_speakers')}")
                except Exception as exc:
                    print(f"     config_error={exc}")
        print(f"Total ONNX size: {total / 1048576:.2f} MiB")
        print("Expected language folders:")
        folders = {PurePosixPath(m).parts[0].lower() for m in models}
        for folder, (name, iso) in LANGUAGES.items():
            print(f"  {'OK' if folder in folders else 'MISSING':7s} {name} ({iso})")

if __name__ == "__main__":
    main()
