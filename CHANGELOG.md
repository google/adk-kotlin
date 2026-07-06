# Changelog

## [0.5.0](https://github.com/google/adk-kotlin/compare/v0.4.0...v0.5.0) (2026-07-03)


### Features

* add `addEventsToMemory` to MemoryService ([93e40d9](https://github.com/google/adk-kotlin/commit/93e40d9a5ad97df44813c6473bc58920c79988f4))
* add `addMemory` to MemoryService ([ebe280e](https://github.com/google/adk-kotlin/commit/ebe280ec3bee8f6fdaed39df15e501425232f1c0))
* add built-in request_input and get_user_choice human-in-the-loop tools ([7c0de10](https://github.com/google/adk-kotlin/commit/7c0de10705fac5cf9bdd21bbc288f5aa0b91012e))
* **agents:** support RunConfig.maxLlmCalls ([70f69c3](https://github.com/google/adk-kotlin/commit/70f69c3b6c9beb5568dadc0177dc5681f4d12778))
* **firebase:** map citation/usage/grounding fields and errorCode in Firebase conversions ([932e2e1](https://github.com/google/adk-kotlin/commit/932e2e14162c15d0c4d6cca894987066f4c4d78c))
* handle long-running input requests in the debug REPL ([1e0d96e](https://github.com/google/adk-kotlin/commit/1e0d96eaa4970f755f20f18882d42799062e31b5))
* implement bypassMultiToolsLimit for google_search ([1ed4481](https://github.com/google/adk-kotlin/commit/1ed44811f070a7fa6c689ff1062b68c639bb7d29))
* implement bypassMultiToolsLimit for vertex_ai_search ([d4e9353](https://github.com/google/adk-kotlin/commit/d4e93530b13f0f4a7ff5bd4d152cc07385b35603))
* **models:** add errorCode and customMetadata to LlmResponse ([9834ded](https://github.com/google/adk-kotlin/commit/9834ded59cc60ff94dc94331b03b53f695ae6e1a))
* **telemetry:** honor ADK_CAPTURE_MESSAGE_CONTENT_IN_SPANS env var ([23250e6](https://github.com/google/adk-kotlin/commit/23250e617e2bad7fb77c8c65a240264e0d7b628e))
* **telemetry:** propagate gcp.mcp.server.destination.id on execute_tool ([7b13efd](https://github.com/google/adk-kotlin/commit/7b13efd214d47975d433e80dea1edc4d3e6ef036))
* **telemetry:** record full token-usage and reasoning-budget attributes ([89cf418](https://github.com/google/adk-kotlin/commit/89cf41801f860ca3fbfef7d2352cdf6451b4dbd1))
* **types:** add avgLogprobs and logprobsResult to LlmResponse ([37a5fbf](https://github.com/google/adk-kotlin/commit/37a5fbf8c781a0da59a0472b2d50e3bb642e7119))
* **types:** add penalties, responseLogprobs, mediaResolution and serviceTier to GenerateContentConfig ([2bb2ac2](https://github.com/google/adk-kotlin/commit/2bb2ac2e761e7eca5377551b9ab7703da98f8a69))
* **types:** add safetySettings to GenerateContentConfig ([e6d7bce](https://github.com/google/adk-kotlin/commit/e6d7bce808056fc06b8fe1d766b7c5fb8fb2485e))
* **types:** add thoughts/tool-use token counts and modality details to UsageMetadata ([79330ef](https://github.com/google/adk-kotlin/commit/79330ef3d7194a0466d4d9889566f34ac9e55673))
* **types:** add toolConfig/FunctionCallingConfig to GenerateContentConfig ([8898fe2](https://github.com/google/adk-kotlin/commit/8898fe29d496bb0855b645789c21f419c6b0b926))
* **types:** add videoMetadata and partMetadata to Part ([40775ea](https://github.com/google/adk-kotlin/commit/40775ea7fe92ebc0a991bf0353ffdf96247ef283))
* **types:** carry grounding chunks/supports/search payload in GroundingMetadata ([af3c573](https://github.com/google/adk-kotlin/commit/af3c57343ef36bddc81854bfce9aac3e1997a04d))
* **types:** carry uri and span indices on Citation ([46ac924](https://github.com/google/adk-kotlin/commit/46ac924a8ec11a37a9148d87167f3e90ff8db5e0))


### Bug Fixes

* add artifact sharing in `AgentTool` between the parent and wrapped agent ([b26adbe](https://github.com/google/adk-kotlin/commit/b26adbeaa8a235c0e898123fe9c616cd175dc31e))
* **agents:** don't route the next user turn into a workflow agent's child ([1a686e9](https://github.com/google/adk-kotlin/commit/1a686e9e231551be5527636bed6ed55523af388e))
* **agents:** support resuming from a pending transfer_to_agent call ([ead993d](https://github.com/google/adk-kotlin/commit/ead993d2c21b0d8a9b0b7038e2b6ed36a26c4c94))
* **build:** avoid OOM in adk-kotlin gradle build ([0e61328](https://github.com/google/adk-kotlin/commit/0e61328e71f1eb6daecbeee30fad613809cd675f))
* fix agent transfer to return control to the parent after a sub-agent completes ([339deca](https://github.com/google/adk-kotlin/commit/339deca1883fa2c2bff7adef84be9dbecef6e577))
* fix long-running result replay ([895827e](https://github.com/google/adk-kotlin/commit/895827eb00fec744b296c07669d81bb13844d788))
* fix per-turn loop termination for tool confirmations ([8e31b5b](https://github.com/google/adk-kotlin/commit/8e31b5b93a04ed05916bb3309daeae79cc25f2b7))
* keep adk_request_input events in LLM context so the model sees the answer ([d28e7ed](https://github.com/google/adk-kotlin/commit/d28e7edf7600dec2ff34d7bd01462ab39f1e815a))
* make `AgentTool` run wrapped agent in an isolated session ([7b280cf](https://github.com/google/adk-kotlin/commit/7b280cfe0ed384904bf6a2ce12f1cace8719b91b))
* merge`stateDelta` into session state before `onUserMessage/agent` run ([faa9816](https://github.com/google/adk-kotlin/commit/faa98161e5a6c5e9373762d361a3d398cae8a2c2))
* pass plugins from parent to wrapped agent's runner ([e107906](https://github.com/google/adk-kotlin/commit/e107906af70b9f2e41575a2b19efbe288a99e5e8))
* scope ADK Kotlin docs release analyzer to a single language ([563a4f7](https://github.com/google/adk-kotlin/commit/563a4f72501b0cdecc35935f5dd042c24c792963))
* stop gating built-in tools on model name ([0858301](https://github.com/google/adk-kotlin/commit/0858301c26c680140b2d0e204f27d9ba8a55eee8))
* suppress function-response for long-running tools that return Unit ([1269fa6](https://github.com/google/adk-kotlin/commit/1269fa69ec668fad627f7308aeea146c1a38c60f))
* **telemetry:** drop inline_data and response_schema from traced llm_request ([553e5ed](https://github.com/google/adk-kotlin/commit/553e5ed566130aaeba3de839ace3d8ec6121588e))
* **types:** tolerate unknown FinishReason values when deserializing ([5bf2036](https://github.com/google/adk-kotlin/commit/5bf20365fe149ab68d924252dcb0af5e0589378d))
* widen ADK Kotlin Frontmatter.metadata type to Map&lt;String, Any?&gt; ([2f69ddd](https://github.com/google/adk-kotlin/commit/2f69ddd017695e58c9f3c5fd02e81bf386013e91))


### Documentation

* document the null default of Toolset.getTools readonlyContext ([f7f4e81](https://github.com/google/adk-kotlin/commit/f7f4e8136e5d54a846800e1130378daa62b0f965))

## [0.4.0](https://github.com/google/adk-kotlin/compare/v0.3.0...v0.4.0) (2026-06-22)


### Features

* add `AssetSkillSource` and introduce `SkillMdParsing` to reuse existing code ([cef3bad](https://github.com/google/adk-kotlin/commit/cef3bad84521fd5492d3cd38ba3b6d6839e75d6e))
* add `endInvocation()` to `CallbackContext` and `ToolContext` ([1bb5e3b](https://github.com/google/adk-kotlin/commit/1bb5e3ba0d38e8f2a08760769c209433d2d15065))
* add decoding of `response` wrapped confirmation response ([f123f6e](https://github.com/google/adk-kotlin/commit/f123f6e9bd3856f1520ad404a3f23b82b028e0e3))
* add filesystem-backed FileArtifactService ([affc9bf](https://github.com/google/adk-kotlin/commit/affc9bf4ace5fc385831d601facf18ea6af96658))
* add GitHub release-docs analyzer (Kotlin) ([d30f824](https://github.com/google/adk-kotlin/commit/d30f82451e736f9a4c1c06a13a045ca137587ca3))
* add LiteRT-LM model integration to Kotlin ADK ([2d9557c](https://github.com/google/adk-kotlin/commit/2d9557c047c9143debcebfb9adb299dedcb21494))
* Add maxSteps to Kotlin LlmAgent ([912379e](https://github.com/google/adk-kotlin/commit/912379ecdf2297ef69a502ffcae09455bc5b067f))
* Add outputKey to LlmAgent to save final text responses to session state ([1a16943](https://github.com/google/adk-kotlin/commit/1a169434b574946bbbf428b6e7671524e2770c2f))
* add Room storage primitives for Android sessions ([8b52926](https://github.com/google/adk-kotlin/commit/8b5292635337ec8fbccb75900e72139bde57cb2d))
* add Room-backed RoomSessionService for Android ([3371551](https://github.com/google/adk-kotlin/commit/3371551c31ed312015c815099401b69f2468118c))
* add Skills example to the examples module ([f1a88a4](https://github.com/google/adk-kotlin/commit/f1a88a4be3d571ce18154578b6ab5f0453bc5a21))
* add sliding-window event compaction strategy ([625e065](https://github.com/google/adk-kotlin/commit/625e065c16a7d6ef5771a4d2c5bc4a83f64efe16))
* Add support for outputSchema in LlmAgent ([1b94ec5](https://github.com/google/adk-kotlin/commit/1b94ec5e921a866f87b0c29a5b13bd82399e6047))
* Add UrlContextTool to ADK Kotlin ([3e7b94e](https://github.com/google/adk-kotlin/commit/3e7b94e01c06f29c83e08d7cce10f7f19e35a039))
* apply context-compaction summaries when building LLM request contents ([9c771ee](https://github.com/google/adk-kotlin/commit/9c771eef736f61c51f004729c0b91ec5c336db30))
* include thoughts and tool calls in compaction summaries, aligned with Python ([2c71902](https://github.com/google/adk-kotlin/commit/2c719026efcc2c9e4c6a5d1a7da586889201c6a7))
* move plugins and resumability configuration onto App ([c7ee0b7](https://github.com/google/adk-kotlin/commit/c7ee0b7408d99bf85284f5407fbaf87940848cfc))
* serialize the Event graph with kotlinx.serialization ([6146119](https://github.com/google/adk-kotlin/commit/614611985002e04390e5902a00e5e2cf56f75068))
* support constructing a Runner from an App ([e6f02e5](https://github.com/google/adk-kotlin/commit/e6f02e531230cd60a9635e7399023d665f5f9596))
* **telemetry:** align telemetry spans with Python ADK ([4035a3a](https://github.com/google/adk-kotlin/commit/4035a3ac0d0ff5a443dbe7a532096bb7c7f3594a))
* wire sliding-window context compaction into the runner ([65b383b](https://github.com/google/adk-kotlin/commit/65b383bf51f8932f67ae001ac533b8d3483c6b15))


### Bug Fixes

* add empty map as sentinel in `FunctionResponse` suppression in `LongRunningTool` ([ac8b9e6](https://github.com/google/adk-kotlin/commit/ac8b9e69452270ca8d2a2440e9eb720246728127))
* **agents:** emit all sub-agent events from LoopAgent before pausing or exiting ([ea10225](https://github.com/google/adk-kotlin/commit/ea102254d8573dfe7168de3d5c67a4c32366ed37))
* **agents:** keep the parent branch across agent runs and transfers ([8c41c8c](https://github.com/google/adk-kotlin/commit/8c41c8ce991c7786f7054084e74fa1e83d1aa1dd))
* exclude rewound invocations from sliding-window compaction ([fe96d97](https://github.com/google/adk-kotlin/commit/fe96d9761fcce62de01678013c7ae43b6c219eba))
* **models:** align Gemini tracking headers with other ADK SDKs ([1d61358](https://github.com/google/adk-kotlin/commit/1d613586571fe3e4f0e27838039330bab5da9785))
* **runners:** no-op when resuming an already-final invocation ([b8cb0bb](https://github.com/google/adk-kotlin/commit/b8cb0bbc3b68d49c2c3d92f2de3aa8adb3bd02bb))
* support custom name and description in @Tool annotation ([8bed997](https://github.com/google/adk-kotlin/commit/8bed9976a1b2874e515a97e1280f62b88f5f1097))
* **telemetry:** align execute_tool span name with Python/Java ADK ([501b618](https://github.com/google/adk-kotlin/commit/501b6183536588a0d5f41270bcd48c701c05f17e))
* **telemetry:** emit OpenTelemetry spans on Android ([0ccce75](https://github.com/google/adk-kotlin/commit/0ccce759be48b5afa0186c92f1acb543b8bd7e92))
* **telemetry:** set gen_ai.tool.type to the tool class name ([fe0cbff](https://github.com/google/adk-kotlin/commit/fe0cbff99d4d460a8abe5b5e6598cf56fd1aff4b))
* **telemetry:** use gcp.vertex.agent as the instrumentation scope name ([19de1c2](https://github.com/google/adk-kotlin/commit/19de1c2caca8600d4d052537fd88c073763d36a7))


### Documentation

* add a limitation remark about session persistence in Firebase ([8fc1d2e](https://github.com/google/adk-kotlin/commit/8fc1d2e35927353c7692a2ed2b38e0416de4738f))
* add an on-device Room session example app ([ce49924](https://github.com/google/adk-kotlin/commit/ce499247d1acfa3e02183786413446dc3879705c))
* **telemetry:** clarify captureMessageContent intentionally defaults off ([d7a5a14](https://github.com/google/adk-kotlin/commit/d7a5a148e52ba2eca3e40cd822bd449f33e2b882))

## [0.3.0](https://github.com/google/adk-kotlin/compare/v0.2.1-SNAPSHOT...v0.3.0) (2026-06-12)


### Features

* A2A Agent remote sample added ([ab34dd8](https://github.com/google/adk-kotlin/commit/ab34dd8507cb70aa89c13a173467abde71a28d57))
* Add AgentTransferDemoAgent for demonstrating agent-to-agent transfer ([00a02e2](https://github.com/google/adk-kotlin/commit/00a02e27b3cf875ba0cac42ed81ca11a749cf76c))
* Add GoogleSearchExample to the examples ([09bc61c](https://github.com/google/adk-kotlin/commit/09bc61c79e6755172948b7dcf308d3b7e8114577))
* Add HitlDemoAgent example ([dd75810](https://github.com/google/adk-kotlin/commit/dd758100d653a3c4098a295e139a0988d6592971))
* add Runner.rewindAsync to undo session state and artifacts ([96c6319](https://github.com/google/adk-kotlin/commit/96c63191fa43677a3ea2cd9b7b7467cd7befa098))
* Add TelemetryDemoAgent example ([d9ac998](https://github.com/google/adk-kotlin/commit/d9ac99877aa4d31ef32bf20a36d7ce56bc822561))
* introduce App data class for Kotlin ADK ([e86d9f6](https://github.com/google/adk-kotlin/commit/e86d9f68e0e78d7f7ef2711c6b4489198b9d1613))
* introduce EventCompaction and add it to EventActions ([aeb43b5](https://github.com/google/adk-kotlin/commit/aeb43b533c52299a59a815fc3d37021a7dd6d798))
* introduce EventSummarizer interface ([5fc6b14](https://github.com/google/adk-kotlin/commit/5fc6b147b090daa5cdd437df68a258400652015e))
* introduce LlmEventSummarizer ([9c50e12](https://github.com/google/adk-kotlin/commit/9c50e12ed108db0f47c4fef800394b8cde9449b9))


### Bug Fixes

* honor rewindBeforeInvocationId in HistoryRewriterProcessor ([33195bf](https://github.com/google/adk-kotlin/commit/33195bfe51828a8b7b6b49b7d85c57d96c7b80a6))
* make `NewFileSystemSource.kt` compatible with AndroidSDK 26+ ([04195c1](https://github.com/google/adk-kotlin/commit/04195c10ceb4da49ef7ae761365a75c68de9278d))
* Update tracing in GenaiPrompt: redact prompts and function calls ([a93e5d6](https://github.com/google/adk-kotlin/commit/a93e5d639c31c4dc760a308b53ec6e8a2d1d6310))

## [0.2.0](https://github.com/google/adk-kotlin/compare/v0.1.1...v0.2.0) (2026-05-26)


### Features

* Added structural agent demos: LoopAgentDemo, ParallelAgentDemo, and SequentialAgentDemo ([2a93053](https://github.com/google/adk-kotlin/commit/2a9305317766b094563cee13f7c3823c4738c62e))
* Add a new example agent demonstrating callbacks ([9183250](https://github.com/google/adk-kotlin/commit/918325061412ef62f32c1d958a5927328b9c9bfa))
* Add a new ReportGeneratorAgent example ([c6ea965](https://github.com/google/adk-kotlin/commit/c6ea96552ca259ff047a63cd04eac177ea8daac4))
* Add input schema validation to AgentTool ([3df572f](https://github.com/google/adk-kotlin/commit/3df572f86ef795682b92461e774dca5e8b837f5b))


### Bug Fixes

* exclude protobuf-java transitive dependency in android ([3b2100c](https://github.com/google/adk-kotlin/commit/3b2100c2efd67489f372ad1336cefd5ae7ee0ec0))
* **processor:** support Map&lt;String, Any&gt; and List&lt;Any&gt; as @Tool return types ([26c3f9d](https://github.com/google/adk-kotlin/commit/26c3f9d8231828c323e0329cb0d969f3670e322b))

## 0.1.0 (2026-05-19)

ADK Kotlin 0.1 release. Provides core features for building AI agents on JVM and Android, including:
* LLM agents, custom agents
* Multi - agent orchestration
* Function tools, Agent Skills, and long-running operations
* In-memory session and memory services
* Model integrations: 
  * Gemini on JVM/Android (Google GenAI SDK, Firebase AI),
  * On-device Gemini Nano and Gemma (ML Kit)
* ADK web UI interface

For full details, please visit the official documentation at https://adk.dev.

**Full Changelog**: https://github.com/google/adk-kotlin/commits/v0.1.0
