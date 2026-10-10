# On-device AI model strategy

Written 10 October 2026. Labels: **MEASURED** means observed on Nitya's iQOO 15 (SM8850, Android 16), one device and single runs. **SOURCE** means a published page, read on the date given. **UNVERIFIED** means not checked by us. Nothing here is a benchmark average or a guarantee for another phone.

## 1. Deterministic rules decide; the model assists

- **Findings and scores** come from fixed Kotlin rules in `:core` (`RulesEngine`, `ScoreCalculator`). Network evidence (DNS lookups, timing, UID bytes) never goes through a model.
- **Consent screens:** the accessibility-tree auditor (`DeterministicScreenAuditor`) runs first. `ScreenAuditMerger` keeps every field the tree OBSERVED. The model's opinion is used only for fields the tree did not observe. A finding built on such a field is labelled INFERRED, counts half in the score, and any tree/model disagreement is shown in the evidence.
- **Explanations and Hindi summaries** are generated only from the finding and its cited DPDP passages. When the model is missing or silent, the app says so ("The model returned no text"). It never makes up a substitute.
- **Why:** an auditor's verdict must be repeatable and explainable. A 2B on-device model is not reliable enough to decide compliance, and the model was not answering at all on the test phone (see [AI_EMPTY_REPLY_DIAGNOSIS.md](AI_EMPTY_REPLY_DIAGNOSIS.md)). The rules keep working with no model installed.
- **Demo consequence:** now that the model may start answering, it can add INFERRED screen findings that move a live score. Measure this on the controlled demo apps before relying on 21/100 and 100/100 with AI loaded.

## 2. Current model: Gemma 4 E2B on LiteRT-LM

| Property | Fact | Basis |
| --- | --- | --- |
| Weights and licence | Open weights, Apache 2.0 | SOURCE: [litert-community/gemma-4-E2B-it-litert-lm](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm) lists `apache-2.0` (read 10 Oct 2026). Earlier Gemma generations use the separate [Gemma Terms](https://ai.google.dev/gemma/terms). |
| Inputs | Text, image, audio; output is text | SOURCE: same Hugging Face page ("Vision and Audio models are loaded on demand"). OBSERVED in our bundle's metadata: `Gemma4` data processor with image and audio tokens. |
| Runtime | LiteRT-LM `com.google.ai.edge.litertlm:litertlm-android:0.17.1` with `Backend.GPU()`, `CPU()` and `NPU(nativeLibraryDir)` | OBSERVED in the AAR |
| Bundle | `gemma-4-E2B-it-gpu.litertlm`, 2,008,432,640 bytes. Packaged sampler: top-p, k 64, p 0.95, temperature 1.0. Declares a `thought` channel. | OBSERVED in the file header |
| Speed on iQOO 15 GPU | 40.1 tokens/s decode, 1287 tokens/s prefill, 0.22 s first token, about 5.5 s load | MEASURED once, by the LiteRT-LM benchmark |
| Answer quality on the phone | Empty before this fix; not yet measured after it | MEASURED (failure) / UNVERIFIED (fix) |
| NPU | Google's page reports an NPU run on a Qualcomm Dragonwing IQ8 IoT board, not on a phone SoC. No phone NPU bundle for Gemma 4 E2B is known to us. | SOURCE (same page); UNVERIFIED for SM8850 |

Why this model:

- It is the only option we checked that has open weights we can pin and ship instructions for.
- It reads images, which consent screenshots and Camera Check need, and it reads audio.
- It runs through one runtime on GPU, CPU and (in principle) NPU.
- Its licence allows redistribution.

The costs:

- about 2 GB of storage
- a GPU load of several seconds
- a 2B model's reasoning limits, which is why it only assists

## 3. Alternatives and what each would be for

| Option | What it is | Possible use in TraceVeil | Why not now | Status |
| --- | --- | --- | --- | --- |
| **Gemini Nano via the ML Kit GenAI Prompt API** | Closed model managed and updated by Android AICore. Google's device table lists **iQOO 15 under nano-v3** (SOURCE: [ML Kit GenAI overview](https://developers.google.com/ml-kit/genai), read 10 Oct 2026). Inference only while the app is in the foreground; AICore enforces per-app and battery quotas. The Prompt API was announced as alpha ([Android Developers Blog, Oct 2025](https://android-developers.googleblog.com/2025/10/ml-kit-genai-prompt-api-alpha-release.html)). | Optional second engine for text explanations on listed phones, with no model download by the user. | Weights are closed and their version is chosen by AICore, so answers cannot be pinned or reproduced. It covers only listed phones. It would add a new dependency and terms review. | UNVERIFIED on the team's phones: whether AICore has the model ready, image input on nano-v3, latency. |
| **Gemma 3 1B IT, SM8850 NPU bundle** (`Gemma3-1B-IT_q4_ekv1280_sm8850.litertlm`, about 694 MB) | Text-only model compiled for the Hexagon NPU of this exact chip | Isolated text-explanation experiment and an NPU speed comparison | Needs the Gemma Terms accepted by a team member, Qualcomm dispatch/QNN V81 libraries that our APK does not package, and a check of its 1280-token context. Text-only, so it must never get screenshots. | Candidate only; see [NPU_RECOMMENDATION.md](NPU_RECOMMENDATION.md). Not downloaded or tested. |
| **Qwen3 0.6B via Qualcomm AI Hub / Genie** | Small multilingual text model on Qualcomm's own runtime | Possible small multilingual text helper | Not a `.litertlm` bundle: it needs a second runtime (Genie/QAIRT), its own licence review and device validation | UNVERIFIED; licence not re-read today |
| **EmbeddingGemma** (about 308M) | Text embedding model, Gemma Terms | Semantic retrieval of DPDP passages (`EmbeddingRetriever` already loads any `*embed*` bundle) | Optional: BM25 keyword search works offline without it. On the phone, retrieval used the BM25 fallback. | Wired, not installed or measured |

## 4. Routing rules in the code (this branch)

- **Capabilities per loaded bundle** (`ModelCapabilities`, `CapabilityRouting`):
  - Gemma 4 and Gemma 3n GPU/CPU bundles are text + image + audio.
  - NPU-compiled bundles and unknown families are **text-only** until a vision bundle is verified on the phone.
  - AI lab's Answer check reads the bundle's own metadata (`Capabilities.inputModalities()`). That can only narrow the routing, never widen it.
- **Screen review and Camera Check** call the model only when the loaded bundle reads images. A text-only model is not called, no screenshot or photo is sent, and the tree rules decide. Camera Check says plainly that the loaded model reads text only.
- **NPU selection fails closed** (`ModelSelection.npuModel`):
  - The bundle's file name must carry exactly this phone's `Build.SOC_MODEL` chip id (for example `sm8850`). An SM8750 bundle, a bundle naming two chips, a generic "npu" file, or a phone below Android 12 that cannot report its chip are all refused with a reason.
  - `LlmEngine.load(NPU)` never loads the GPU bundle in its place, so a GPU run is never labelled NPU.
  - NPU bundles get no vision or audio encoder unless they declare one, and they use their compiled context instead of the forced 4096 tokens.
- **Every app call** sets thinking off, uses top-k 1 at temperature 1.0 and has a bounded output length. Thinking text is never shown as an answer (see the diagnosis).

## 5. Measured versus not measured

- **Measured on one iQOO 15:**
  - SoC SM8850
  - the GPU bundle loads
  - benchmark speeds as above
  - empty explanations (3 of 3) and tree fallback for screen reviews (6 of 6) before this fix
- **Not measured yet:**
  - answer rate, quality and latency after this fix
  - image accuracy on real consent screens
  - memory use and thermal behaviour during a full audit with the model
  - any NPU execution
  - Gemini Nano availability on the team's phones
  - EmbeddingGemma retrieval quality
  - behaviour on Agrim's and Yashvi's phones

Record each of these in VERIFICATION.md as it is measured, including failures.
