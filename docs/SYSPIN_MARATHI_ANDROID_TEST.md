# SYSPIN Marathi Android Integration Test

This adds Marathi SYSPIN Coqui-VITS as a separate model path on the existing \`syspin-bengali-android-test\` branch. Bengali remains on its existing tokenizer and asset. English/Hindi MMS models and the other iTantra VITS paths are unchanged.

## Local model archive (not committed)

Create this local-only file:

\`app/src/main/assets/models/syspin-marathi.zip\`

Its ZIP layout must be:

\`\`\`
syspin-marathi/
  mar/
    config.json
    model.onnx
\`\`\`

Use the tested, quantized model:

\`F:\rayyan\TTS-test\syspin-marathi\onnx\syspin_marathi_conv_int4_final_emb_int4.onnx\`

and config:

\`F:\rayyan\TTS-test\syspin-marathi\model\config.json\`

The original config contains Python-style \`Infinity\` literals in \`max_audio_len\` and \`max_text_len\`. Those are accepted by the Python training stack but are not valid strict JSON for Android's \`JSONObject\`. The packaging command below replaces only those bare \`Infinity\` tokens with \`null\` in the staged config; it leaves the original config untouched.

Run from the local repository root:

\`\`\`powershell
$project = (Get-Location).Path
$marathiRoot = "F:\rayyan\TTS-test\syspin-marathi"
$sourceConfig = Join-Path $marathiRoot "model\config.json"
$sourceModel = Join-Path $marathiRoot "onnx\syspin_marathi_conv_int4_final_emb_int4.onnx"
$asset = Join-Path $project "app\src\main\assets\models\syspin-marathi.zip"

if (!(Test-Path $sourceConfig)) { throw "Config not found: $sourceConfig" }
if (!(Test-Path $sourceModel)) { throw "Tested Marathi ONNX model not found: $sourceModel" }
if (Test-Path $asset) { throw "Archive already exists; inspect it before replacing: $asset" }

$stage = Join-Path $env:TEMP ("syspin-marathi-package-" + [guid]::NewGuid().ToString("N"))
$packageRoot = Join-Path $stage "syspin-marathi"
$payload = Join-Path $packageRoot "mar"
$stagedConfig = Join-Path $payload "config.json"
New-Item -ItemType Directory -Force -Path $payload | Out-Null
New-Item -ItemType Directory -Force -Path (Split-Path $asset) | Out-Null

$configText = [System.IO.File]::ReadAllText($sourceConfig)
$configText = [regex]::Replace($configText, '\bInfinity\b', 'null')
[System.IO.File]::WriteAllText(
    $stagedConfig,
    $configText,
    [System.Text.UTF8Encoding]::new($false)
)
Copy-Item $sourceModel (Join-Path $payload "model.onnx")
Compress-Archive -Path $packageRoot -DestinationPath $asset -CompressionLevel Optimal

Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($asset)
try {
    $zip.Entries | Select-Object FullName, Length
} finally {
    $zip.Dispose()
}
Get-Item $asset | Select-Object FullName, Length
\`\`\`

Expected entries include \`syspin-marathi/mar/config.json\` and \`syspin-marathi/mar/model.onnx\`.

## Model/tokenizer contract

- Config-driven character mapping mirrors Coqui's \`VitsCharacters\`: pad, configured punctuation, configured characters, then the blank token. Duplicate characters override their earlier punctuation IDs, as in Coqui's dictionary construction.
- \`add_blank=true\`; this Marathi config yields blank ID 145 and a 146-symbol vocabulary.
- ONNX inputs: \`input\` (int64 [1, token_count]), \`input_lengths\` (int64 [1]), \`scales\` (float [3]).
- Scales: \`[0.667, 1.0, 1.0]\`, matching the successful Python ONNX comparison.
- Sample rate: 22,050 Hz from \`config.json -> audio.sample_rate\`.
- Marathi has its own tokenizer; the existing Bengali tokenizer/model routing is not changed.

## Build and device test

From the repository root:

\`\`\`powershell
.\gradlew.bat :app:assembleDebug
\`\`\`

Install the debug APK and use the existing TTS preview/test UI to generate the same six Marathi test sentences from \`test_syspin_marathi_onnx_audio.py\`. Listen for pronunciation, static/noise, cadence, and duration. Desktop RTF was around 0.26–0.29 for this candidate, but Android performance has to be measured on-device.

This remains an integration candidate until the APK builds and Marathi has been tested on an Android device.
