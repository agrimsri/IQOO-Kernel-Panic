# NPU candidate for the connected iQOO 15

Decision, 10 October 2026: recommend a separate **text-only Gemma 3 1B IT INT4 SM8850** experiment. Do not claim working NPU inference yet, and do not replace the GPU vision model for consent screenshots.

## What was actually checked

- Nitya's connected I2501 phone reports `ro.soc.model=SM8850`.
- Its app model folder contains only `gemma-4-E2B-it-gpu.litertlm` (2,008,432,640 bytes); no NPU bundle is installed.
- The app uses `com.google.ai.edge.litertlm:litertlm-android:0.17.1` and has `Backend.NPU(nativeLibraryDir)` wiring.
- The inspected APK's arm64 native entries are only `liblitertlm_jni.so` and `libandroidx.graphics.path.so`. It does not package Qualcomm dispatch/QNN libraries. Backend UI wiring is not evidence of successful NPU execution.

## Exact candidate and evidence

Candidate: [`Gemma3-1B-IT_q4_ekv1280_sm8850.litertlm`](https://huggingface.co/litert-community/Gemma3-1B-IT/blob/main/Gemma3-1B-IT_q4_ekv1280_sm8850.litertlm), approximately 694 MB in the [model repository listing](https://huggingface.co/litert-community/Gemma3-1B-IT/tree/main). The filename indicates a 1280-context compiled variant; configure and verify the actual supported token budget rather than inheriting our current 4096-token setting blindly.

Google's [Qualcomm Gemma sample](https://github.com/google-ai-edge/litert-samples/blob/main/samples/litert/qualcomm/gemma3/npu/README.md) uses Gemma 3 1B with LiteRT-LM for text-only NPU inference, linking this model repository. Its example is SM8750, not our phone. The [general NPU guide](https://developers.google.com/edge/litert/next/litert_lm_npu) still lists older Qualcomm SoCs; the separately published SM8850 artifact is a candidate to validate, not a guarantee of iQOO compatibility.

Google's [QAIRT SDK guide](https://github.com/google-ai-edge/LiteRT/blob/main/litert/vendors/qualcomm/doc/QAIRT_SDK.md) identifies SM8850 as Hexagon V81 and lists the required QNN HTP libraries. The [LiteRT-LM NPU guide](https://developers.google.com/edge/litert/next/litert_lm_npu) also requires Qualcomm dispatch and associated runtime libraries. Pin mutually compatible model, LiteRT-LM, dispatch, QAIRT and firmware versions; copying arbitrary `.so` files from another APK or renaming an SM8750 model is not a supported installation method.

## Ownership and gates before implementation

Yashvi owns `ai/**`; Nitya mounts and validates the integration. No AI file or model dependency was changed by the Preview work.

1. Have a team member review and accept the gated repository's [Gemma Terms](https://ai.google.dev/gemma/terms) and [Prohibited Use Policy](https://ai.google.dev/gemma/prohibited_use_policy) through their own account. No terms were accepted and no model/QAIRT SDK was downloaded by this investigation. Gemma 3 is not licensed under Gemma 4's licence; record the actual model terms and applicable distribution notices in `ATTRIBUTIONS.md` before distributing it.
2. Obtain the exact SM8850 bundle through its authorised download flow, record its published revision and SHA-256, keep the model outside Git, and budget storage for model, cache and runtime assets. Review QAIRT SDK terms separately before packaging/distributing its libraries.
3. Build/package the compatible Qualcomm dispatch and QNN HTP/V81 assets using the official instructions. Source-build examples use Linux/macOS and Android NDK r28b or newer. Decide an isolated reproducible build route before installing large toolchains or modifying the existing Windows setup.
4. Fix selection guards in Yashvi's AI module: `ModelFiles.npuModel` currently accepts both SM8750 and SM8850 filenames, and `LlmEngine.load(NPU)` falls back to the main GPU bundle when no NPU file exists. Fail closed for an absent/wrong-SoC NPU model; do not label a GPU fallback as NPU success.
5. Keep text and vision capabilities explicit. The proposed Gemma 3 1B is text-only; the existing `auditConsentScreen` sends image inputs. Do not route screenshots to that model or silently replace the current multimodal engine. Use it first for an isolated text benchmark/grounded explanation experiment, not score computation or consent automation.
6. On the actual iQOO, verify backend initialisation, non-empty correct text, cold/warm latency, actual backend evidence, memory, cancellation and thermal behaviour. Record a failed attempt as failed. For fair backend speed comparisons use the same model/task where supported; comparing 1B NPU with 2B GPU measures both model and backend differences.
7. Only after the experiment passes, propose and approve the product integration. Preserve deterministic scoring, citations, offline question-chip availability and existing GPU camera/screen review.

## Alternative for later

[Qualcomm AI Hub's Qwen3 0.6B](https://aihub.qualcomm.com/models/qwen3_0_6b) is a small multilingual candidate, but its Genie/QAIRT integration is not a drop-in LiteRT-LM `.litertlm` model. It needs a separate runtime/licence/device validation design. Do not introduce that migration just to claim NPU use before a mentor demo.

NPU acceleration cannot shorten the DNS observation windows required for stronger evidence. It may improve model latency only after real measurement. Jovi in Office Kit is a desktop assistant, not a verified embeddable NPU model for TraceVeil.
