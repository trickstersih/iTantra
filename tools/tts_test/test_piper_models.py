#!/usr/bin/env python3
from __future__ import annotations
import argparse, csv, hashlib, json, math, os, shutil, tempfile, time, wave, zipfile
from pathlib import Path, PurePosixPath
from datetime import datetime

try:
    import psutil
except ImportError:
    psutil = None

try:
    from piper import PiperVoice, SynthesisConfig
except ImportError as exc:
    raise SystemExit("Install dependencies with: python -m pip install -r requirements.txt") from exc

LANG = {
    "eng": ("English", "en", [
        "Hello. This is an offline speech test.",
        "This is an Itantra voice test for clear local speech.",
        "The quick response test checks whether long sentences remain clear and stable on a low power device.",
    ]),
    "ben": ("Bengali", "bn", [
        "নমস্কার। এটি একটি অফলাইন স্পিচ পরীক্ষা।",
        "এটি ইটান্ট্রার পরিষ্কার স্থানীয় বক্তৃতার জন্য একটি ভয়েস পরীক্ষা।",
        "এই দীর্ঘ পরীক্ষা যাচাই করে যে কম শক্তির ডিভাইসে দীর্ঘ বাক্য স্পষ্ট এবং স্থিরভাবে বলা যায় কি না।",
    ]),
    "guj": ("Gujarati", "gu", [
        "નમસ્તે. આ એક ઑફલાઇન વૉઇસ પરીક્ષણ છે.",
        "આ ઇટાન્ટ્રા માટે સ્વચ્છ સ્થાનિક અવાજનું પરીક્ષણ છે.",
        "આ લાંબી ચકાસણી એ તપાસે છે કે ઓછી શક્તિવાળા ઉપકરણ પર લાંબા વાક્યો સ્પષ્ટ અને સ્થિર રીતે બોલી શકાય છે કે નહીં.",
    ]),
    "hin": ("Hindi", "hi", [
        "नमस्ते। यह एक ऑफलाइन आवाज़ परीक्षण है।",
        "यह इटान्ट्रा के लिए साफ़ स्थानीय आवाज़ का परीक्षण है।",
        "यह लंबी जाँच देखती है कि कम शक्ति वाले डिवाइस पर लंबे वाक्य स्पष्ट और स्थिर रूप से बोले जा सकते हैं या नहीं।",
    ]),
    "kan": ("Kannada", "kn", [
        "ನಮಸ್ಕಾರ. ಇದು ಒಂದು ಆಫ್‌ಲೈನ್ ಧ್ವನಿ ಪರೀಕ್ಷೆ.",
        "ಇದು ಇಟಾನ್ಟ್ರಾ ಸ್ಥಳೀಯ ಧ್ವನಿಯ ಸ್ಪಷ್ಟತೆ ಪರೀಕ್ಷೆಯಾಗಿದೆ.",
        "ಕಡಿಮೆ ಶಕ್ತಿಯ ಸಾಧನದಲ್ಲಿ ದೀರ್ಘ ವಾಕ್ಯಗಳು ಸ್ಪಷ್ಟವಾಗಿ ಮತ್ತು ಸ್ಥಿರವಾಗಿ ಕೇಳಿಸುತ್ತವೆಯೇ ಎಂಬುದನ್ನು ಈ ಪರೀಕ್ಷೆ ಪರಿಶೀಲಿಸುತ್ತದೆ.",
    ]),
    "mal": ("Malayalam", "ml", [
        "നമസ്കാരം. ഇത് ഒരു ഓഫ്‌ലൈൻ ശബ്ദ പരിശോധനയാണ്.",
        "ഇത് ഇറ്റാൻട്രയുടെ പ്രാദേശിക ശബ്ദത്തിന്റെ വ്യക്തത പരിശോധിക്കുന്ന പരീക്ഷണമാണ്.",
        "കുറഞ്ഞ ശക്തിയുള്ള ഉപകരണത്തിൽ ദൈർഘ്യമേറിയ വാക്യങ്ങൾ വ്യക്തമായും സ്ഥിരതയോടെയും സംസാരിക്കാനാകുമോ എന്ന് ഈ ദീർഘ പരിശോധന പരിശോധിക്കുന്നു.",
    ]),
    "mar": ("Marathi", "mr", [
        "नमस्कार। ही एक ऑफलाइन आवाजाची चाचणी आहे.",
        "ही इटान्ट्राच्या स्वच्छ स्थानिक आवाजाची चाचणी आहे.",
        "कमी शक्तीच्या उपकरणावर मोठी वाक्ये स्पष्ट आणि स्थिरपणे बोलता येतात का हे ही दीर्घ चाचणी तपासते.",
    ]),
    "ory": ("Odia", "or", [
        "ନମସ୍କାର। ଏହା ଏକ ଅଫଲାଇନ୍ ଭଏସ୍ ପରୀକ୍ଷା।",
        "ଏହା ଇଟାନ୍ଟ୍ରାର ସ୍ଥାନୀୟ ଭଏସ୍ ସ୍ପଷ୍ଟତା ପାଇଁ ଏକ ପରୀକ୍ଷା।",
        "କମ୍ ଶକ୍ତିର ଉପକରଣରେ ଦୀର୍ଘ ବାକ୍ୟଗୁଡ଼ିକ ସ୍ପଷ୍ଟ ଏବଂ ସ୍ଥିର ଭାବେ କୁହାଯାଇପାରେ କି ନାହିଁ ଏହି ଦୀର୍ଘ ପରୀକ୍ଷା ଯାଞ୍ଚ କରେ।",
    ]),
    "tam": ("Tamil", "ta", [
        "வணக்கம். இது ஒரு ஆஃப்லைன் குரல் சோதனை.",
        "இது இட்டான்ட்ராவின் உள்ளூர் குரல் தெளிவுக்கான சோதனை.",
        "குறைந்த சக்தி கொண்ட சாதனத்தில் நீண்ட வாக்கியங்கள் தெளிவாகவும் நிலையாகவும் பேசப்படுகிறதா என்பதை இந்த நீண்ட சோதனை சரிபார்க்கிறது.",
    ]),
    "tel": ("Telugu", "te", [
        "నమస్కారం. ఇది ఒక ఆఫ్‌లైన్ వాయిస్ పరీక్ష.",
        "ఇది ఇటాన్ట్రా స్థానిక వాయిస్ స్పష్టత కోసం ఒక పరీక్ష.",
        "తక్కువ శక్తి ఉన్న పరికరంలో పొడవైన వాక్యాలు స్పష్టంగా మరియు స్థిరంగా మాట్లాడబడుతున్నాయా అని ఈ దీర్ఘ పరీక్ష తనిఖీ చేస్తుంది.",
    ]),
}

def rss_mb():
    if psutil is None:
        return None
    return psutil.Process(os.getpid()).memory_info().rss / 1048576

def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        while chunk := f.read(1024 * 1024):
            h.update(chunk)
    return h.hexdigest()

def safe_extract(zf, member, dest):
    p = PurePosixPath(member)
    if p.is_absolute() or ".." in p.parts:
        raise ValueError(f"Unsafe ZIP path: {member}")
    out = dest.joinpath(*p.parts)
    out.parent.mkdir(parents=True, exist_ok=True)
    with zf.open(member) as src, open(out, "wb") as dst:
        shutil.copyfileobj(src, dst, 1024 * 1024)
    return out

def find_config(member, extracted):
    model = Path(member)
    candidates = [
        extracted / (str(model.name) + ".json"),
        extracted / model.with_suffix(".json"),
        extracted / model.parent / "config.json",
        extracted / model.parent / "model.json",
    ]
    for p in candidates:
        if p.is_file():
            return p
    js = list(extracted.parent.glob("**/*.json"))
    return js[0] if len(js) == 1 else None

def language_for(member, config):
    hay = str(member).lower()
    for key, data in LANG.items():
        if key in hay:
            return data
    try:
        cfg = json.loads(config.read_text(encoding="utf-8")) if config else {}
        language = cfg.get("language", {})
        code = str(language.get("code", "")).lower() if isinstance(language, dict) else ""
        espeak = str(language.get("espeak", {}).get("voice", "")).lower() if isinstance(language, dict) and isinstance(language.get("espeak"), dict) else ""
        combined = code + " " + espeak
        for key, data in LANG.items():
            if data[1] in combined:
                return data
    except Exception:
        pass
    return ("Unknown", "xx", ["This is a generic Piper model test sentence."])

def load_voice(model, config):
    if config is not None:
        try:
            return PiperVoice.load(model, config_path=config)
        except TypeError:
            pass
    return PiperVoice.load(model)

def synthesize(voice, text, out_path, speaker_id=None):
    cfg = SynthesisConfig()
    if speaker_id is not None:
        cfg.speaker_id = speaker_id
    chunks = []
    sr = sw = ch = None
    start = time.perf_counter()
    peak = rss_mb()
    for chunk in voice.synthesize(text, cfg):
        sr = sr or int(chunk.sample_rate)
        sw = sw or int(chunk.sample_width)
        ch = ch or int(chunk.sample_channels)
        chunks.append(chunk.audio_int16_bytes)
        current = rss_mb()
        if current is not None and peak is not None:
            peak = max(peak, current)
    elapsed = time.perf_counter() - start
    if not chunks:
        raise RuntimeError("Piper returned no audio")
    audio = b"".join(chunks)
    duration = len(audio) / max(1, sw * ch * sr)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(out_path), "wb") as wav:
        wav.setnchannels(ch)
        wav.setsampwidth(sw)
        wav.setframerate(sr)
        wav.writeframes(audio)
    return elapsed, duration, sr, ch, peak

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("zip_path")
    ap.add_argument("--out", default=None)
    args = ap.parse_args()
    zip_path = Path(args.zip_path).expanduser().resolve()
    if not zip_path.is_file():
        raise SystemExit(f"ZIP not found: {zip_path}")
    stamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    out = Path(args.out).resolve() if args.out else Path.cwd() / ("tts_test_run_" + stamp)
    audio = out / "audio"
    out.mkdir(parents=True, exist_ok=True)
    rows = []

    with zipfile.ZipFile(zip_path) as zf:
        if zf.testzip():
            raise SystemExit("ZIP CRC check failed")
        models = sorted(n for n in zf.namelist() if n.lower().endswith(".onnx"))
        print(f"Found {len(models)} ONNX model(s)")
        for idx, member in enumerate(models, 1):
            print("\n" + "=" * 80)
            print(f"[{idx}/{len(models)}] {member}")
            with tempfile.TemporaryDirectory() as td:
                root = Path(td)
                model = safe_extract(zf, member, root)
                config_member = member + ".json"
                if config_member not in zf.namelist():
                    alt = str(PurePosixPath(member).with_suffix(".json"))
                    config_member = alt if alt in zf.namelist() else None
                config = safe_extract(zf, config_member, root) if config_member else find_config(member, model)
                name, iso, texts = language_for(member, config)
                size_mb = model.stat().st_size / 1048576
                digest = sha256_file(model)
                print(f"Language: {name} ({iso})")
                print(f"Size: {size_mb:.2f} MiB")
                print(f"SHA-256: {digest}")
                voice = None
                try:
                    before = rss_mb()
                    load_start = time.perf_counter()
                    voice = load_voice(model, config)
                    load_s = time.perf_counter() - load_start
                    after = rss_mb()
                    vcfg = getattr(voice, "config", None)
                    speakers = int(getattr(vcfg, "num_speakers", 0) or 0)
                    speaker_id = 0 if speakers else None
                    warm_start = time.perf_counter()
                    list(voice.synthesize("Test."))
                    warm_s = time.perf_counter() - warm_start
                    print(f"Load={load_s:.3f}s Warmup={warm_s:.3f}s RSS={after if after is not None else 'n/a'} MiB")
                    for i, text in enumerate(texts[:3]):
                        label = ["short", "medium", "long"][i]
                        wav = audio / iso / (iso + "_" + label + ".wav")
                        try:
                            synth_s, audio_s, sr, channels, peak = synthesize(voice, text, wav, speaker_id)
                            rtf = synth_s / audio_s if audio_s else math.inf
                            print(f"  {label:6s} synth={synth_s:.3f}s audio={audio_s:.3f}s RTF={rtf:.3f} PASS")
                            rows.append({
                                "model": member, "language": name, "iso": iso, "model_mb": size_mb,
                                "sha256": digest, "load_s": load_s, "warmup_s": warm_s,
                                "rss_before_mb": before, "rss_after_load_mb": after,
                                "test": label, "synth_s": synth_s, "audio_s": audio_s, "rtf": rtf,
                                "sample_rate": sr, "channels": channels, "speaker_count": speakers,
                                "peak_rss_mb": peak, "status": "PASS", "error": ""
                            })
                        except Exception as exc:
                            print(f"  {label:6s} FAIL: {exc}")
                            rows.append({
                                "model": member, "language": name, "iso": iso, "model_mb": size_mb,
                                "sha256": digest, "load_s": load_s, "warmup_s": warm_s,
                                "rss_before_mb": before, "rss_after_load_mb": after,
                                "test": label, "synth_s": "", "audio_s": "", "rtf": "",
                                "sample_rate": "", "channels": "", "speaker_count": speakers,
                                "peak_rss_mb": "", "status": "FAIL", "error": str(exc)
                            })
                except Exception as exc:
                    print(f"LOAD FAIL: {exc}")
                    rows.append({
                        "model": member, "language": name, "iso": iso, "model_mb": size_mb,
                        "sha256": digest, "load_s": "", "warmup_s": "",
                        "rss_before_mb": rss_mb(), "rss_after_load_mb": rss_mb(),
                        "test": "load", "synth_s": "", "audio_s": "", "rtf": "",
                        "sample_rate": "", "channels": "", "speaker_count": "",
                        "peak_rss_mb": "", "status": "FAIL", "error": str(exc)
                    })
                finally:
                    close = getattr(voice, "close", None)
                    if callable(close):
                        try:
                            close()
                        except Exception:
                            pass

    if rows:
        csv_path = out / "tts_results.csv"
        json_path = out / "tts_results.json"
        with csv_path.open("w", newline="", encoding="utf-8-sig") as f:
            writer = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
            writer.writeheader()
            writer.writerows(rows)
        json_path.write_text(json.dumps(rows, ensure_ascii=False, indent=2), encoding="utf-8")
        print("\nResults:")
        print(csv_path)
        print(json_path)
        print(audio)
    failed = [r for r in rows if r["status"] != "PASS"]
    return 1 if failed else 0

if __name__ == "__main__":
    raise SystemExit(main())
