# iTantra TTS model selection and quality-preserving quantization

## Reusable procedure for each language

The goal is offline Android speech on a low-power device. **Speech quality is the hard gate; model size is optimized only after a clean reference has been established.** Do not change the normal app bundle while a candidate is experimental.

1. **Search for a SYSPIN model first.** Check the official SYSPIN repository and Hugging Face model catalog for a matching language and architecture, preferably a monolingual Coqui VITS checkpoint with downloadable weights and config. Record the exact repository, speaker, architecture, sample rate, tokenizer/character inventory, file sizes, and model-weight license. Do not infer the model license from the code repository's license.
2. **Confirm usage rights before selecting a fallback.** Read the checkpoint's own model card and license. Distinguish permissively licensed weights (e.g. Apache-2.0/MIT) from non-commercial-only weights and from gated/unclear licenses.
3. **Test the native checkpoint first.** Generate at least 7–10 meaningful sentences (short statement, question, longer sentence, urgent/emergency phrase, names/numbers, punctuation). Save WAVs and listen. If the native model is unclear, do not attempt to rescue it with quantization.
4. **Export FP32 ONNX before quantizing.** Use the model's correct tokenizer and configuration. Compare original-framework audio against FP32 ONNX using the exact same token IDs. Confirm sample rate, duration, input/output names, no dropped characters, and listen to the audio. The export itself must pass before quantization begins.
5. **Quantize in separate, reversible experiments.** Keep an untouched FP32 reference. Start conservatively (e.g. selected MatMul weights or small groups of the largest eligible layers). Then test selected Conv weights; keep ConvTranspose/vocoder layers FP32 initially where quality is sensitive. Only move to more aggressive INT8/INT4 if previous audio stays clean. Change one operation group at a time.
6. **Validate every candidate by listening, not statistics alone.** Use identical text/token IDs and save audio per stage. Record model size, loadability, runtime, peak/RMS/clipping and spectral indicators as supporting diagnostics; none proves perceptual quality. Listen to several sentences, including Odia native-script samples and long/urgent text.
7. **Integrate only after quality and size both pass.** Package the selected model and required sidecars with verified ZIP paths. Keep the test asset Git-ignored, gate the route behind an explicit local test marker, run the desktop comparison, compile, then test on a device. Never overwrite the known-good bundle or commit large model binaries.

## Odia research (checked 21 October 2026)

### SYSPIN availability

The currently published [SYSPIN model list](https://github.com/labspire/Syspin) lists VITS models for Hindi, Chhattisgarhi, Kannada, Maithili, Telugu, Bengali, Marathi, Bhojpuri, Magahi, English and Gujarati, but **does not list Odia**. Searches of the public SYSPIN model catalog did not locate an Odia VITS checkpoint. Therefore we should not invent a SYSPIN Odia variant.

### Candidates found

- **Existing MMS Odia VITS — `facebook/mms-tts-ory`.** The original PyTorch checkpoint and original FP32 ONNX have already sounded clear in our tests. The FP32 ONNX is about 109.12 MiB. The current bundled mixed-INT8 export has static, while the experimental MatMul-only INT8 model sounded as clear as FP32 but remains about 100 MiB, so it is a quality diagnostic rather than an acceptable final size. Important license caveat: the Hugging Face model card says **CC-BY-NC-4.0**; this is not a permissive OSI-style open-source license and restricts commercial use. Use only if those terms fit the project. [Model card](https://huggingface.co/facebook/mms-tts-ory)
- **AI4Bharat Indic-TTS.** The project says it releases TTS models for Odia and describes a FastPitch acoustic model plus a HiFi-GAN vocoder. Its public GitHub `or.zip` checkpoint asset is 1,516,409,500 bytes (about 1.41 GiB compressed). The repository's MIT `LICENSE.txt` is clearly the code license; the checkpoint asset's own applicable terms should be verified before redistribution. This is a plausible open checkpoint to evaluate, but it is a two-model architecture and not a drop-in VITS ONNX; extracting only inference checkpoints and converting both components may be required. [Project](https://github.com/AI4Bharat/Indic-TTS) · [Checkpoint release](https://github.com/AI4Bharat/Indic-TTS/releases/tag/v1-checkpoints-release)
- **AI4Bharat Indic-Parler-TTS.** The model is Apache-2.0 and lists 18 languages, but the published FP32 weight file is about 3.75 GB and the repository is gated behind contact-information consent. At 0.9B parameters, it is not an appropriate first target for this small offline Android app. [Model card](https://huggingface.co/ai4bharat/indic-parler-tts)
- **Kenpath Svara-TTS v1.** Apache-2.0 and 19 languages including Odia, but the model is 3B parameters and the sharded weights total about 13.2 GB. Not suitable for our device-size and latency constraints. [Model card](https://huggingface.co/kenpath/svara-tts-v1)
- **Praha-Labs RIMA-TTS v1.** It has an Odia adapter but is not standalone, needs the Chatterbox base model, and its model card labels the license as `other`. Do not choose it without resolving licensing and measuring device feasibility. [Model card](https://huggingface.co/Praha-Labs/RIMA-TTS-v1)

## Current decision

There is no public SYSPIN Odia VITS checkpoint in the catalog we checked. No alternative found so far simultaneously satisfies all three requirements: clearly permissive checkpoint license, small offline/mobile footprint, and already-demonstrated clean Odia audio.

For strict permissive licensing, the next research candidate is AI4Bharat Indic-TTS after confirming model-weight terms and inspecting its archive to identify just the inference checkpoints. First validate native FastPitch + HiFi-GAN quality, then export both components and optimize carefully. Do not assume quantization will preserve quality; every quantization stage must be auditioned. The clear MMS FP32/MatMul-INT8 outputs remain useful baselines, but the MMS license caveat must be respected.
