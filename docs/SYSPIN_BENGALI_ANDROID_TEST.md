# SYSPIN Bengali Android Test

This branch adds an isolated experimental inference path for the SYSPIN Bengali Coqui-VITS checkpoint. It does not replace English/Hindi MMS or the existing iTantra VITS models for other languages.

## Required local model asset

The model archive is intentionally excluded from Git. Create this file locally:

`app/src/main/assets/models/syspin-bengali.zip`

The ZIP must contain exactly this relevant layout:

```
syspin-bengali/
  ben/
    config.json
    model.onnx
```

Use the existing checkpoint config and the validated weight-only Conv-INT8 ONNX model:
- Config: `F:\\rayyan\\TTS-test\\syspin-bengali\\model\\config.json`
- Model: `F:\\rayyan\\TTS-test\\syspin-bengali\\onnx\\syspin_bengali_conv_weightonly_int8.onnx`

PowerShell (run from the repository root; adjust paths only if your test directory differs):

```powershell
$project = (Get-Location).Path
$stage = Join-Path $env:TEMP "syspin-bengali-package"
$payload = Join-Path $stage "syspin-bengali\ben"
$config = "F:\rayyan\TTS-test\syspin-bengali\model\config.json"
$model = "F:\rayyan\TTS-test\syspin-bengali\onnx\syspin_bengali_conv_weightonly_int8.onnx"
$asset = Join-Path $project "app\src\main\assets\models\syspin-bengali.zip"

if (!(Test-Path $config)) { throw "Config not found: $config" }
if (!(Test-Path $model)) { throw "ONNX model not found: $model" }
if (Test-Path $asset) { throw "Asset already exists; inspect it before replacing: $asset" }

New-Item -ItemType Directory -Force -Path $payload | Out-Null
New-Item -ItemType Directory -Force -Path (Split-Path $asset) | Out-Null
Copy-Item $config (Join-Path $payload "config.json")
Copy-Item $model (Join-Path $payload "model.onnx")
Compress-Archive -Path (Join-Path $stage "syspin-bengali") -DestinationPath $asset -CompressionLevel Optimal
Get-Item $asset | Select-Object FullName, Length
```

## Model contract

- Tokenizer: `SyspinBengaliTokenizer` (character-level, checkpoint vocabulary, `add_blank=true`, blank ID 84).
- ONNX inputs: `input` (int64 [1, token_count]), `input_lengths` (int64 [1]), `scales` (float [3]).
- Scales: `[0.667, 1.0, 1.0]`, matching the config and Python ONNX test.
- Output: float waveform at 22,050 Hz (`config.json -> audio.sample_rate`).
- Only Bengali (`ben`) uses this experimental profile; other languages retain their current model paths.

## Build and test

After creating the local archive, from the repository root run:

```powershell
.\gradlew.bat :app:assembleDebug
```

Install the debug APK and run the app's existing Bengali TTS preview/test path. Listen for pronunciation, noise, clipping, and sentence duration. This is an integration candidate, not yet device-verified.
