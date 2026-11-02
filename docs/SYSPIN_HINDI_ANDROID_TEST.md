# SYSPIN Hindi Android integration

Hindi now uses the compact SYSPIN Coqui VITS model instead of the legacy MMS Hindi entry.

## Local model asset (not committed)

The app expects this local-only archive:

`app/src/main/assets/models/syspin-hindi.zip`

Archive entries must be:

- `syspin-hindi/hin/config.json`
- `syspin-hindi/hin/model.onnx`

Use the clean-audio 16.70 MiB model produced at `onnx_quantized/hindi_int4_final.onnx`, renamed to `model.onnx` inside the ZIP. Keep the original Coqui `config.json` beside it.

PowerShell packaging commands:

```powershell
$repoRoot = "F:\rayyan\rayyan\itantra"
$workDir = "F:\rayyan\TTS-test\syspin-hindi-male"
$stageRoot = Join-Path $workDir "android-package"
$stageLanguage = Join-Path $stageRoot "syspin-hindi\hin"
$assetDir = Join-Path $repoRoot "app\src\main\assets\models"

New-Item -ItemType Directory -Force -Path $stageLanguage, $assetDir | Out-Null
Copy-Item (Join-Path $workDir "config.json") (Join-Path $stageLanguage "config.json") -Force
Copy-Item (Join-Path $workDir "onnx_quantized\hindi_int4_final.onnx") (Join-Path $stageLanguage "model.onnx") -Force

Compress-Archive -Path (Join-Path $stageRoot "syspin-hindi") `
    -DestinationPath (Join-Path $assetDir "syspin-hindi.zip") -Force
```

Confirm the ZIP entries:

```powershell
$zip = [System.IO.Compression.ZipFile]::OpenRead((Join-Path $assetDir "syspin-hindi.zip"))
try { $zip.Entries | Select-Object -ExpandProperty FullName }
finally { $zip.Dispose() }
```

Expected entries are exactly `syspin-hindi/hin/config.json` and `syspin-hindi/hin/model.onnx`. The ZIP is ignored by Git and must not be committed.

The English archive at `app/src/main/assets/models/syspin-english-test.zip` is also local-only and ignored by Git. Both model ZIPs must exist locally when building an APK that includes those languages.

## Build and device validation

```powershell
.\gradlew.bat :app:assembleDebug
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
adb logcat -c
adb logcat -s MmsTtsEngine
```

Trigger Hindi TTS using the previously desktop-verified samples:

1. `कुछ समय बाद वे असहाबे सुफ़्फ़ाह के नाम से प्रसिद्ध हो गए।`
2. `नमस्ते, आप कैसे हैं? यह हिंदी आवाज़ की जाँच है।`
3. `यह संदेश बिना इंटरनेट कनेक्शन के बनाया गया है।`

The expected model-load log is `Loaded SYSPIN Hindi Coqui VITS model`, with model inputs `input`, `input_lengths`, and `scales`. The engine reads the sample rate and inference scales from the original Coqui config. Only the selected language's ONNX session remains resident.

## Validation result

Desktop ONNX audio was reported clear after compacting the model and stripping ONNX docstrings. Hindi playback and audio quality were subsequently confirmed on the target Android phone using the local ZIP.

The tokenizer instrumentation tests are included in the project. No CI workflow/status checks were reported for this PR.

## Legacy MMS Hindi archive

The model store no longer selects `models/quantized_models.zip`. English and Hindi now have dedicated Coqui VITS model routes; all other supported language routes remain on their existing profiles.

The tracked legacy `quantized_models.zip` archive has been removed from this branch. It is not required by the current Hindi or English routes and should not be included in release APKs.
