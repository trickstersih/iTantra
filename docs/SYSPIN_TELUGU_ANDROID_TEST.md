# SYSPIN Telugu VITS Android test

This adds an isolated, test-only Telugu adapter using the validated Coqui VITS candidate from the SYSPIN Telugu Female checkpoint. The chosen candidate is `06_embedding_int4_optional.onnx` (approximately 16.70 MiB), which the project owner confirmed remained clear and accurate in desktop ONNX Runtime tests.

## Local model archive

Model binaries are intentionally not committed to Git. From PowerShell, run the packager script, pointing `-TtsRoot` at the extracted `syspin_telugu_tts_kit` directory:

```powershell
.	oolspackage_syspin_telugu_model.ps1 -TtsRoot "F:\rayyan\TTS-test\syspin-telugu\syspin_telugu_tts_kit"
```

The script creates:

`app\src\main\assets\models\syspin-telugu.zip`

The ZIP layout is:

- `syspin-telugu/tel/config.json`
- `syspin-telugu/tel/model.onnx`

The script uses the tested 06 embedding-INT4 candidate. It creates a staging copy of the upstream `config.json` and replaces bare `Infinity`, `-Infinity`, and `NaN` tokens with JSON `null` **only outside quoted strings**, then validates strict JSON before archiving. The original downloaded config is never modified. This is also backed by `TtsConfigReader`, which defensively normalizes non-finite bare tokens when reading model configs at runtime.

## Build and install

```powershell
git fetch origin
git switch syspin-bengali-android-test
# Run the packaging command above before building.
.gradlew.bat clean :app:assembleDebug
.gradlew.bat :app:installDebug
```

The archive is listed in `.gitignore`; do not commit it.

## Android smoke test

1. Open the app and select Telugu (`te`) as the speech language.
2. Create/send a short Telugu PTT voice message, then use the playback button on its resulting transcript. The test follows the same engine path as normal message playback.
3. Try these reference sentences:
   - `నమస్కారం.`
   - `మీరు ఎలా ఉన్నారు?`
   - `ఇది ఒక పరీక్ష సందేశం.`
   - `దయచేసి వెంటనే వెనక్కి వెళ్లండి.`
   - `నాకు సహాయం కావాలి.`
   - `దయచేసి నీరు తీసుకురండి.`
   - `రేపు కలుద్దాం!`
4. Check Logcat for `MmsTtsEngine`, especially model-load, unsupported operator/input, and dropped-character warnings.
5. Compare speech with the previously approved desktop samples. Confirm Telugu script characters are not being dropped and that pronunciation, static/noise, cadence, and sentence endings remain acceptable.

## Implementation notes and limitations

- Telugu uses a dedicated `SyspinTeluguTokenizer` built from the bundled Coqui config, preserving the source character inventory and blank-token convention.
- Telugu model loading is isolated in `MmsTtsModelStore` and routed through the Coqui VITS inputs `input`, `input_lengths`, and `scales`. Other languages' model routes are not replaced.
- The model was tested with desktop ONNX Runtime 1.31.0. Android uses the project's bundled ONNX Runtime, so successful APK build, session load, and on-device audio playback must still be verified on the target phone. Desktop testing is not proof of Android operator compatibility or audio quality.
