# Why Gemma 4 returned no answers on the iQOO 15

Status, 10 October 2026: **root cause narrowed, fix shipped, phone confirmation pending.** Nothing in this document was run on a phone. The evidence below comes from the LiteRT-LM 0.17.1 library itself and from the Gemma 4 model file on the laptop. Labels: **OBSERVED** means read directly from those files. **INFERRED** means reasoned from that evidence but not run. **NOT ESTABLISHED** means unknown.

## What failed

From [VERIFICATION.md](VERIFICATION.md), on Nitya's iQOO 15 (SM8850), with `gemma-4-E2B-it-gpu.litertlm` loaded on the GPU:

- Finding explanations came back empty 2 out of 2 times in English and 1 out of 1 in Hindi.
- All 6 consent-screen reviews fell back to the deterministic accessibility-tree auditor.
- The LiteRT-LM benchmark worked on the same phone: 40.1 tokens/s decode, 1287 tokens/s prefill, first token in 0.22 s.

**Important:** those runs used the code from before commit `9b8212b`. That code wrapped every model call in `runCatching { … }.getOrNull()`. So a thrown exception and a truly empty reply looked exactly the same: "no text". We do not know which one happened (NOT ESTABLISHED). We found no record that the later "Answer check" from `9b8212b` was ever run on the phone.

The benchmark goes through `nativeCreateBenchmark`, not through a `Conversation`. Our system prompt, chat template, sampler and thinking settings are not used there. So the benchmark shows that the GPU executor works. It says nothing about our conversation settings (INFERRED from the JNI signatures below).

## Evidence from the library (OBSERVED)

The artifact is `D:\DevTools\gradle-home\caches\modules-2\files-2.1\com.google.ai.edge.litertlm\litertlm-android\0.17.1\…\litertlm-android-0.17.1.aar`. We read it with `javap -p -c -l` from JDK 17. The AAR ships no sources jar. Its classes are Java 21 bytecode (class file version 65), so the JVM unit tests, which run on JDK 17, cannot load them. That is why the tests use mapped structures instead.

### Where the answer text lives

```text
public final class com.google.ai.edge.litertlm.Message {
  private final Role role;
  private final Contents contents;
  private final List<ToolCall> toolCalls;
  private final Map<String, String> channels;
```

`Conversation$Companion.jsonToMessage(JsonObject)` builds every reply, both from `sendMessage` and from each `sendMessageAsync` chunk:

- `content` is read with `getAsJsonArray("content")`. Only items whose `type == "text"` become `Content.Text(text)`.
- `tool_calls` becomes `ToolCall(name, arguments)`.
- `channels` is read with `getAsJsonObject("channels")`. Each entry becomes `Map<String, String>`.
- The result is `Message.model(Contents.of(list), toolCalls, channels)`.

So **the final answer is in `Message.contents`** and side channels such as thinking are in `Message.channels`. The old extraction, `reply.contents.contents.filterIsInstance<Content.Text>()`, already read the right field. The extraction code alone does not explain the failure.

### Conversation settings and their defaults

```text
ConversationConfig(systemInstruction: Contents? = null, initialMessages = [], tools = [],
  samplerConfig: SamplerConfig? = null, automaticToolCalling = true, channels: List<Channel>? = null,
  extraContext = {}, loraConfig = null, prefillPrefaceOnInit = false, maxOutputToken: Int? = null,
  thinkingConfig: ThinkingConfig? = null, enableResponseFormat = false)
ThinkingConfig(enableThinking: Boolean = true, thinkingTokenBudget: Int = -1)
SamplerConfig(topK: Int, topP: Double, temperature: Double, seed: Int)
Channel(channelName: String, start: String, end: String)
ResponseFormat(type: ResponseFormat.Type {REGEX, JSON_OBJECT}, schemaOrPattern: String); ResponseFormat.json(String|Map), regex(String)
```

- The `ThinkingConfig()` defaults **enable** thinking. Our old code sent `thinkingConfig = null` (no setting). What the native layer does with `null` is NOT ESTABLISHED.
- The `SamplerConfig` constructor checks `topK > 0`, `0 ≤ topP ≤ 1` and `temperature ≥ 0`. A temperature of exactly 0 passes the Kotlin check.
- `Engine.createConversation` passes these to `LiteRtLmJni.nativeCreateConversation(…)`:
  - the system instruction, as a `Role.SYSTEM` message in the messages JSON
  - the channels JSON. It is `null` when `channels` is `null`, so the model's own channels are used.
  - `ExperimentalFlags.enableConversationConstrainedDecoding`, which defaults to `false`
  - `filterChannelContentFromKvCache`, which defaults to `null`
  - `maxOutputToken`, sent as `-1` when `null`
  - `thinkingConfig` and `enableResponseFormat`

### The send call

```text
Message sendMessage(Contents contents, Map<String,?> extraContext, RepetitionPenaltyConfig, NoRepeatNgramConfig,
                    SuppressTokensConfig, Integer maxOutputToken, ThinkingConfig thinkingConfig, ResponseFormat responseFormat)
```

(The parameter names come from the class's LocalVariableTable.)

- If `responseFormat != null` and the conversation was created without `enableResponseFormat`, the call throws `IllegalArgumentException("response_format cannot be used unless enableResponseFormat=True was passed to ConversationConfig.")`. Our constrained call already set it, so this is not the cause.
- `thinkingConfig` is forwarded only on the first iteration of the tool-call loop. If the native JSON has no `content`, the call throws `IllegalStateException(<json>)`.
- `sendMessageAsync(…)` comes in a callback form and a `Flow<Message>` form. Both feed each native chunk through the same `jsonToMessage`, so the two calls parse replies the same way. Whether a chunk is a delta or the whole reply so far is INFERRED to be a delta; AI lab's streaming row will show it.

### Model capabilities API

`Capabilities(modelPath)` is marked `@ExperimentalApi`. It offers:

- `supportsThinking()`
- `inputModalities()`, which returns `SupportedModalities(text, vision, audio, video)`
- `defaultSamplerParams()`, which returns `SamplerParameters(type, temperature, topK, topP)`

### Strings inside the native library

These come from the printable strings of `jni/arm64-v8a/liblitertlm_jni.so`:

- `Creating Gemma4DataProcessor`, `gemma4_data_processor.cc`, `enable_thinking`, `thought`
- `Thinking budget is set but thinking_end_token_ids is empty. Ignoring thinking budget constraint.`
- `Annealing temperature cannot be zero.`, `Created OpenCL GPU TemperatureAnnealer`, `Falling back to CPU TemperatureAnnealer`, `Temperature must be >= 0, but got …`
- `TopKOpenClSampler successfully initialized on GPU with k=`, `Sampler will handle decode input tensors.`, `options.TOP_P must be positive and <= 1.0`
- `Custom channel must have a non-empty channel_name.`, `History is empty after SendMessage`
- These constrained-decoding components: `llg_constraint_provider.cc` (llguidance), `fst_constraint_provider.cc`, `Unsupported logits type for ConstrainedDecoder::ProcessLogits.`, `… backend constraint is not set.`

## Evidence from the model file (OBSERVED, token names INFERRED)

The file is `D:\DevTools\models\gemma-4-E2B-it-gpu.litertlm`, 2,008,432,640 bytes. That matches the size of the phone's copy given in [NPU_RECOMMENDATION.md](NPU_RECOMMENDATION.md). We only read its metadata header, around byte offsets 16,350 to 35,000. These are the decoded LlmMetadata protobuf fields:

| Field | Bytes / text | Meaning |
| --- | --- | --- |
| start token | `0a 05 0a 03 0a 01 02` | token 2 (`<bos>`) |
| stop tokens | `0a 01 01`, `0a 01 32`, `0a 01 6a` | tokens 1, 50, 106: `<eos>`, `<\|tool_response>`, `<turn\|>` (names INFERRED from the vocabulary order in the same file) |
| sampler | `22 0e 08 02 10 40 1d 33 33 73 3f 25 00 00 80 3f` | type 2 (top-p), **k = 64, p = 0.95, temperature = 1.0** |
| model type | `Gemma4` message with `<\|image>`, `<image\|>`, `<\|audio>`, `<audio\|>`, tool-call tokens | multimodal Gemma 4 data processor |
| channels | name `thought`, start `<\|channel>thought\n`, end `<channel\|>` | the one channel this bundle declares |
| chat template | Jinja, about 12 KB | see below |

Template lines that matter, quoted from the file:

```jinja
{%- if (enable_thinking is defined and enable_thinking) or tools or messages[0]['role'] in ['system', 'developer'] -%}
    {{- '<|turn>system\n' -}}
    {%- if enable_thinking is defined and enable_thinking -%}
        {{- '<|think|>' -}}
    {%- endif -%}
...
{%- if add_generation_prompt -%}
    {%- if ns.prev_message_type != 'tool_response' -%}
        {{- '<|turn>model\n' -}}
```

What the template shows:

- The bundle handles a native system turn, so `systemInstruction` is a supported input.
- Thinking is switched on only by a truthy `enable_thinking`.
- The template has a `strip_thinking` macro that removes `<|channel>…<channel|>` from earlier model turns.

Google's [Gemma thinking page](https://ai.google.dev/gemma/docs/capabilities/thinking) agrees. With thinking off, E2B/E4B are prompted with no empty thought block (only the larger models get one), and thoughts must be stripped from the conversation history.

## Diagnosis

1. **Most likely: a sampler temperature of 0.0 (INFERRED).** The app sent `SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0)`, while the bundle ships a top-p sampler at temperature 1.0. The Kotlin check allows 0. But the native GPU sampling path has a temperature annealer whose own error says `Annealing temperature cannot be zero.`, and temperature scaling divides by it. Google's samples use temperatures above 0. Through the old `runCatching → null` code, this failure would look exactly like the observed "no text", both in explanations and in both screen-review attempts. NOT ESTABLISHED: whether this annealer is on the Gemma 4 GPU path, and whether a zero temperature throws or gives an empty reply.
2. **Possible: thinking left unset (INFERRED, less likely for E2B).** The library can carry thinking: `ThinkingConfig()` defaults to on, and there is `enable_thinking`. If the runtime ever turned it on, Gemma 4 would write `<|channel>thought…` first, and the runtime would route that to `channels["thought"]`. Contents would stay empty if the token budget ran out while thinking. Google documents that E2B with thinking off produces no thought block. What the runtime defaults to is NOT ESTABLISHED.
3. **Screen reviews only: constrained JSON on the GPU (INFERRED).** The native library has constrained-decoding error paths that look backend-sensitive, as listed above. A failed constrained attempt makes the app retry without constraints, and that retry would also hit cause 1.
4. **Ruled out by the API evidence:**
   - Reading the wrong field: the answer is in `contents` and the old code read `contents`.
   - `sendMessage` versus `sendMessageAsync`: both use the same parser.
   - A missing `enableResponseFormat`: it was set.
   - The system role: the template supports it.

## What changed (this branch)

- **Settings for every app task (`AskOptions`):**
  - Thinking is explicitly **off** with `ThinkingConfig(enableThinking = false)`, sent both in `ConversationConfig` and per message.
  - The sampler is `SamplerChoice.NEAR_GREEDY`: top-k 1 at **temperature 1.0**. That is deterministic in practice, and it never sends a zero temperature.
  - Output is bounded by `maxOutputToken`: 512 for explanations and 768 for screen-review JSON, so a bad setting cannot block the model lock for minutes.
- **`LlmOutcome.fromReply` / `ReplyExtraction`:**
  - The answer is the visible `contents`.
  - Thinking-channel text is counted (`hiddenThinkingChars`) and never shown.
  - A reply that has only thinking text is `Empty(…, thinkingChars)`, never an answer.
  - Inline `<|channel>name\n…<channel|>` markup is removed by the same rule as the template's `strip_thinking`. An unclosed block (budget ran out) is treated as thinking. A close marker with no open marker means the text before it was thinking.
  - An answer-named channel (`final`, `answer`, `response`, `output`) is used only when the contents are empty, and it is recorded as `channel:<name>`. Unknown channels are never shown.
- **AI lab "Answer check" (one tap):**
  - It runs fixed, data-free prompts:
    - the app's settings
    - the model's own sampler
    - no system instruction
    - streaming
    - constrained JSON
    - a generated image card, for image-capable models only
    - thinking on
    - each old setting on its own
    - the exact old settings, run last so a native error cannot spoil the earlier rows
  - It prints plain conclusions, for example "Temperature 0 is the breaking setting".
  - It reads the bundle's own metadata through `Capabilities` (user-triggered only) and prints inputs, thinking support and the packaged sampler.
- **NPU fail-closed and capability routing.** See [AI_MODEL_STRATEGY.md](AI_MODEL_STRATEGY.md).
  - An NPU bundle must name exactly this phone's `Build.SOC_MODEL`. An SM8750 bundle is never accepted on an SM8850 phone. Phones below Android 12 have no `SOC_MODEL` and are refused.
  - `load(NPU)` never falls back to the GPU bundle.
  - Text-only bundles are never sent screenshots or camera photos.

## What the team must check on the phone

1. Install this build. Open **AI lab**, tap **GPU** to load, then tap **Run answer check**.
   - Expected: the first row, **App settings**, is green and shows `"OK"`.
   - Photograph every row, the conclusion lines above the rows and the metadata block below them, and add them to VERIFICATION.md.
   - The block should report: inputs text, image, audio; packaged sampler top-k 64, top-p 0.95, temperature 1.0.
2. Compare the old rows (labelled "Old…") with **App settings**. "Fixed on this phone" plus a named setting confirms the diagnosis. "Does not reproduce" means the earlier failure came from something else; say so in VERIFICATION.md.
   - Also read the **image** and **constrained JSON** rows.
   - **Thinking on** should show "thinking text hidden" or "only thinking text". It must never show the thinking itself.
3. Open a finding, tap **Explain** in English and in Hindi, and time both. If either is empty, read **Last problem** in AI lab.
4. Run **Camera Check** on a consent screenshot.
5. Run one Full LeakyShop audit (leaky and fixed). Record whether the screen review was merged with the model or tree-only.
   - **Demo impact:** once the model answers, it can fill screen fields the tree did not observe. Rules label those INFERRED, and they count half in the score (`ScoreCalculator`). Live demo scores can therefore differ from 21/100 and 100/100, which until now ran without any model opinion.
   - Decide before the demo. One option is turning off **Load AI when an audit starts** in Settings for the controlled run. The JVM regression is unaffected.
6. Tap **NPU** in AI lab.
   - Expected: "NPU (Hexagon): no NPU bundle compiled for SM8850 is in the models folder. The GPU model was not loaded in its place."
   - The NPU benchmark shows the same reason. Tap **GPU** to reload.

If **App settings** still fails while **Model's own sampler** works, change the default in `AskOptions` to `SamplerChoice.MODEL_DEFAULT`; the conclusion line says so. If every row fails, keep AI explanations out of the demo. Deterministic findings do not need the model.
