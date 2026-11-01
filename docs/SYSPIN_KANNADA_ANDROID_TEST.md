# SYSPIN Kannada Android Integration Test

This adds the SYSPIN Kannada Female Coqui-VITS checkpoint as a separate local-only model path. Existing Bengali and Marathi paths remain separate and unchanged, as do English/Hindi MMS and the existing iTantra VITS models.

## Local model archive (not committed)

Create this local-only asset:

`app/src/main/assets/models/syspin-kannada.zip`

Archive layout:

```
syspin-kannada/
  kan/
    config.json
    model.onnx
```

Use the accepted quantized ONNX model from the Kannada test directory:

`F:\\rayyan\\TTS-test\\syspin-kannada\\onnx\\syspin_kannada_final_emb_int4.onnx`

and source config:

`F:\\rayyan\\TTS-test\\syspin-kannada\\model\\config.json`

Before packaging, check the config for bare JSON-invalid `Infinity` literals. If present, replace only those tokens with `null` in the staged copy. Keep the original config untouched.

A ready-to-run PowerShell packaging script is provided separately as `package_syspin_kannada.ps1`. Save it into the Kannada TTS test folder and run:

```powershell
.\package_syspin_kannada.ps1 -ProjectRoot "F:\rayyan\rayyan\itantra" -TtsRoot "F:\rayyan\TTS-test\syspin-kannada"
```

It validates the staged config as JSON, builds the ZIP with the expected directory layout, verifies both required ZIP entries, and refuses to overwrite an existing archive.

## Model/tokenizer contract

- Language model code: `kan`; ISO code: `kn`.
- The config-driven tokenizer mirrors Coqui VITS character IDs: padding token, configured punctuation, configured characters, and blank token. It preserves duplicate-symbol overwrite behavior.
- Kannada config has `add_blank=true`, 67 configured characters, 13 punctuation symbols, a padding token, and a blank token; vocabulary size is 82, blank ID 81.
- ONNX inputs: `input` (int64 [1, token_count]), `input_lengths` (int64 [1]), `scales` (float [3]).
- Scales: `[0.667, 1.0, 1.0]`.
- Sample rate: 22,050 Hz from `config.json -> audio.sample_rate`.

## Build and device test

From the local repository root, with the local ZIP asset present:

```powershell
.\gradlew.bat :app:assembleDebug
```

Install the generated debug APK on the Android test phone and choose Kannada in the TTS test/preview UI. Test the same six strings used by `test_syspin_kannada_onnx_audio.py`. Compare pronunciation, static/noise, pauses/cadence, and duration with the accepted desktop ONNX output. Desktop results do not substitute for an on-device test.

The model ZIP is excluded from Git intentionally; this branch contains application-side routing and tokenizer changes only.
