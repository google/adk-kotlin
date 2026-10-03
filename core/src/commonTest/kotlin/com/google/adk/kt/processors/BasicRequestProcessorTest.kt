/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.adk.kt.processors

import com.google.adk.kt.agents.InvocationContext
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.agents.RunConfig
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.testing.DummyModel
import com.google.adk.kt.testing.DummyTool
import com.google.adk.kt.testing.testSession
import com.google.adk.kt.types.ActivityHandling
import com.google.adk.kt.types.AudioTranscriptionConfig
import com.google.adk.kt.types.ContextWindowCompressionConfig
import com.google.adk.kt.types.GenerateContentConfig
import com.google.adk.kt.types.LiveConnectConfig
import com.google.adk.kt.types.MediaResolution
import com.google.adk.kt.types.Modality
import com.google.adk.kt.types.ProactivityConfig
import com.google.adk.kt.types.RealtimeInputConfig
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.SessionResumptionConfig
import com.google.adk.kt.types.SpeechConfig
import com.google.adk.kt.types.TranslationConfig
import com.google.adk.kt.types.Type
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import org.junit.Test

class BasicRequestProcessorTest {

  private val outputSchema =
    Schema(type = Type.OBJECT, properties = mapOf("answer" to Schema(type = Type.STRING)))

  @Test
  fun run_withAgentFields_setsModelAndConfigOnRequest() = runBlocking {
    val model = DummyModel("gemini")
    val config = GenerateContentConfig()
    val agent = LlmAgent(name = "test", model = model, generateContentConfig = config)
    val session = testSession()
    val context = InvocationContext(session = session, runConfig = null, agent = agent)
    var request = LlmRequest()

    val processor = BasicRequestProcessor()
    request = processor.process(context, request)

    assertEquals(model, request.model)
    assertEquals(config, request.config)
  }

  @Test
  fun run_withOutputSchemaAndNoTools_setsResponseSchema() = runBlocking {
    val agent =
      LlmAgent(name = "test", model = DummyModel("gemini-2.0-flash"), outputSchema = outputSchema)
    val context = InvocationContext(session = testSession(), runConfig = null, agent = agent)

    val request = BasicRequestProcessor().process(context, LlmRequest())

    assertEquals(outputSchema, request.config.responseSchema)
    assertEquals("application/json", request.config.responseMimeType)
  }

  @Test
  fun run_withOutputSchemaAndTools_gemini2Model_doesNotSetResponseSchema() = runBlocking {
    // Gemini 2.x cannot use a response schema together with tools, so the schema is left for the
    // OutputSchemaProcessor workaround instead of being set on the request config here.
    val agent =
      LlmAgent(
        name = "test",
        model = DummyModel("gemini-2.0-flash"),
        tools = listOf(DummyTool("my_tool")),
        outputSchema = outputSchema,
      )
    val context = InvocationContext(session = testSession(), runConfig = null, agent = agent)

    val request = BasicRequestProcessor().process(context, LlmRequest())

    assertNull(request.config.responseSchema)
    assertNull(request.config.responseMimeType)
  }

  @Test
  fun run_withOutputSchemaAndTools_supportedModel_setsResponseSchema() = runBlocking {
    // Gemini 3.x supports a response schema together with tools, so the schema is applied directly.
    val agent =
      LlmAgent(
        name = "test",
        model = DummyModel("gemini-3.0-pro"),
        tools = listOf(DummyTool("my_tool")),
        outputSchema = outputSchema,
      )
    val context = InvocationContext(session = testSession(), runConfig = null, agent = agent)

    val request = BasicRequestProcessor().process(context, LlmRequest())

    assertEquals(outputSchema, request.config.responseSchema)
    assertEquals("application/json", request.config.responseMimeType)
  }

  @Test
  fun run_withOutputSchemaAndSubAgents_gemini2Model_doesNotSetResponseSchema() = runBlocking {
    // An agent with sub-agents gets a `transfer_to_agent` tool injected later by
    // AgentTransferProcessor. On Gemini 2.x that tool is incompatible with a response schema, so
    // the
    // schema must be left for the OutputSchemaProcessor workaround even though no explicit tools
    // are
    // declared.
    val agent =
      LlmAgent(
        name = "parent",
        model = DummyModel("gemini-2.0-flash"),
        outputSchema = outputSchema,
        subAgents = listOf(LlmAgent(name = "child", model = DummyModel("gemini-2.0-flash"))),
      )
    val context = InvocationContext(session = testSession(), runConfig = null, agent = agent)

    val request = BasicRequestProcessor().process(context, LlmRequest())

    assertNull(request.config.responseSchema)
    assertNull(request.config.responseMimeType)
  }

  @Test
  fun run_withOutputSchemaAndSubAgents_supportedModel_setsResponseSchema() = runBlocking {
    // Gemini 3.x supports a response schema together with tools (including transfer_to_agent), so
    // the schema is applied directly even though sub-agents are present.
    val agent =
      LlmAgent(
        name = "parent",
        model = DummyModel("gemini-3.0-pro"),
        outputSchema = outputSchema,
        subAgents = listOf(LlmAgent(name = "child", model = DummyModel("gemini-3.0-pro"))),
      )
    val context = InvocationContext(session = testSession(), runConfig = null, agent = agent)

    val request = BasicRequestProcessor().process(context, LlmRequest())

    assertEquals(outputSchema, request.config.responseSchema)
    assertEquals("application/json", request.config.responseMimeType)
  }

  @Test
  fun run_withDefaultRunConfig_deliversTranscriptionToLiveConnectConfig() = runBlocking {
    // Asserts arrival, not the default's value: without the copy it never reaches the connection.
    val agent = LlmAgent(name = "test", model = DummyModel("gemini-2.0-flash-live"))
    val context = InvocationContext(session = testSession(), runConfig = RunConfig(), agent = agent)

    val request = BasicRequestProcessor().process(context, LlmRequest())

    assertEquals(AudioTranscriptionConfig(), request.liveConnectConfig.outputAudioTranscription)
    assertEquals(AudioTranscriptionConfig(), request.liveConnectConfig.inputAudioTranscription)
  }

  @Test
  fun run_withPopulatedRunConfig_copiesEveryLiveField() = runBlocking {
    val runConfig =
      RunConfig(
        responseModalities = listOf(Modality.AUDIO),
        speechConfig = SpeechConfig(languageCode = "en-US"),
        outputAudioTranscription = AudioTranscriptionConfig(languageCodes = listOf("en-US")),
        inputAudioTranscription = AudioTranscriptionConfig(languageCodes = listOf("fr-FR")),
        realtimeInputConfig =
          RealtimeInputConfig(activityHandling = ActivityHandling.NO_INTERRUPTION),
        explicitVadSignal = true,
        translationConfig = TranslationConfig(targetLanguageCode = "es"),
        enableAffectiveDialog = true,
        proactivity = ProactivityConfig(proactiveAudio = true),
        sessionResumption = SessionResumptionConfig(handle = "resumption-handle"),
        contextWindowCompression = ContextWindowCompressionConfig(triggerTokens = 1024L),
      )
    val agent = LlmAgent(name = "test", model = DummyModel("gemini-2.0-flash-live"))
    val context = InvocationContext(session = testSession(), runConfig = runConfig, agent = agent)

    val live = BasicRequestProcessor().process(context, LlmRequest()).liveConnectConfig

    assertEquals(runConfig.responseModalities, live.responseModalities)
    assertEquals(runConfig.speechConfig, live.speechConfig)
    assertEquals(runConfig.outputAudioTranscription, live.outputAudioTranscription)
    assertEquals(runConfig.inputAudioTranscription, live.inputAudioTranscription)
    assertEquals(runConfig.realtimeInputConfig, live.realtimeInputConfig)
    assertEquals(runConfig.explicitVadSignal, live.explicitVadSignal)
    assertEquals(runConfig.translationConfig, live.translationConfig)
    assertEquals(runConfig.enableAffectiveDialog, live.enableAffectiveDialog)
    assertEquals(runConfig.proactivity, live.proactivity)
    assertEquals(runConfig.sessionResumption, live.sessionResumption)
    assertEquals(runConfig.contextWindowCompression, live.contextWindowCompression)
  }

  @Test
  fun run_withGemini3XLiveModel_dropsAffectiveDialogAndProactivity() = runBlocking {
    val runConfig =
      RunConfig(
        enableAffectiveDialog = true,
        proactivity = ProactivityConfig(proactiveAudio = true),
        explicitVadSignal = true,
        translationConfig = TranslationConfig(targetLanguageCode = "es"),
      )
    val agent = LlmAgent(name = "test", model = DummyModel("gemini-3.0-flash-live-preview"))
    val context = InvocationContext(session = testSession(), runConfig = runConfig, agent = agent)

    val live = BasicRequestProcessor().process(context, LlmRequest()).liveConnectConfig

    assertNull(live.enableAffectiveDialog)
    assertNull(live.proactivity)
    // Everything else still crosses, so the drop is targeted rather than a bail-out.
    assertEquals(AudioTranscriptionConfig(), live.outputAudioTranscription)
    assertEquals(true, live.explicitVadSignal)
    assertEquals(runConfig.translationConfig, live.translationConfig)
  }

  @Test
  fun run_withGemini3XLiveModel_replacesUnanswerableResponseModalitiesWithAudio() = runBlocking {
    // A client in video mode asks for VIDEO here, which kills the setup; TEXT closes with 1007.
    val runConfig = RunConfig(responseModalities = listOf(Modality.VIDEO, Modality.TEXT))
    val agent = LlmAgent(name = "test", model = DummyModel("gemini-3.0-flash-live-preview"))
    val context = InvocationContext(session = testSession(), runConfig = runConfig, agent = agent)

    val live = BasicRequestProcessor().process(context, LlmRequest()).liveConnectConfig

    assertEquals(listOf(Modality.AUDIO), live.responseModalities)
  }

  @Test
  fun run_withGemini3XLiveModel_keepsAudioAmongRequestedResponseModalities() = runBlocking {
    val runConfig = RunConfig(responseModalities = listOf(Modality.AUDIO, Modality.VIDEO))
    val agent = LlmAgent(name = "test", model = DummyModel("gemini-3.0-flash-live-preview"))
    val context = InvocationContext(session = testSession(), runConfig = runConfig, agent = agent)

    val live = BasicRequestProcessor().process(context, LlmRequest()).liveConnectConfig

    assertEquals(listOf(Modality.AUDIO), live.responseModalities)
  }

  @Test
  fun run_withEarlierLiveModel_keepsRequestedResponseModalities() = runBlocking {
    // Earlier live models do answer in text, so the drop must not reach them.
    val runConfig = RunConfig(responseModalities = listOf(Modality.TEXT))
    val agent = LlmAgent(name = "test", model = DummyModel("gemini-2.0-flash-live"))
    val context = InvocationContext(session = testSession(), runConfig = runConfig, agent = agent)

    val live = BasicRequestProcessor().process(context, LlmRequest()).liveConnectConfig

    assertEquals(listOf(Modality.TEXT), live.responseModalities)
  }

  @Test
  fun run_withNullRunConfig_appliesNoRunConfigSettings() = runBlocking {
    val agent = LlmAgent(name = "test", model = DummyModel("gemini-2.0-flash-live"))
    val context = InvocationContext(session = testSession(), runConfig = null, agent = agent)

    val request = BasicRequestProcessor().process(context, LlmRequest())

    assertEquals(LiveConnectConfig(), request.liveConnectConfig)
  }

  @Test
  fun run_withGemini3XLiveModel_leavesEmptyResponseModalitiesUnset() = runBlocking {
    // Empty means no preference, so it stays unset even on 3.x; the server's audio default applies.
    val runConfig = RunConfig(responseModalities = emptyList())
    val agent = LlmAgent(name = "test", model = DummyModel("gemini-3.0-flash-live-preview"))
    val context = InvocationContext(session = testSession(), runConfig = runConfig, agent = agent)

    val live = BasicRequestProcessor().process(context, LlmRequest()).liveConnectConfig

    assertNull(live.responseModalities)
  }

  @Test
  fun run_withEarlierLiveModel_leavesEmptyResponseModalitiesUnset() = runBlocking {
    // Empty maps back to unset on the genai config, as ADK Python's None does.
    val runConfig = RunConfig(responseModalities = emptyList())
    val agent = LlmAgent(name = "test", model = DummyModel("gemini-2.0-flash-live"))
    val context = InvocationContext(session = testSession(), runConfig = runConfig, agent = agent)

    val live = BasicRequestProcessor().process(context, LlmRequest()).liveConnectConfig

    assertNull(live.responseModalities)
  }

  @Test
  fun run_withAgentSampling_foldsItIntoLiveConnectConfig() = runBlocking {
    // Folded in like ADK Python: the agent's values are used when the live config has none.
    val config =
      GenerateContentConfig(
        temperature = 0.25f,
        topP = 0.9f,
        topK = 40,
        maxOutputTokens = 256,
        mediaResolution = MediaResolution.MEDIA_RESOLUTION_LOW,
        seed = 7,
      )
    val agent =
      LlmAgent(
        name = "test",
        model = DummyModel("gemini-2.0-flash-live"),
        generateContentConfig = config,
      )
    val context = InvocationContext(session = testSession(), runConfig = RunConfig(), agent = agent)

    val live = BasicRequestProcessor().process(context, LlmRequest()).liveConnectConfig

    assertEquals(0.25f, live.temperature)
    assertEquals(0.9f, live.topP)
    assertEquals(40, live.topK)
    assertEquals(256, live.maxOutputTokens)
    assertEquals(MediaResolution.MEDIA_RESOLUTION_LOW, live.mediaResolution)
    assertEquals(7, live.seed)
  }

  @Test
  fun run_withSamplingOnTheLiveConnectConfig_keepsItOverTheAgents() = runBlocking {
    // Every field the rule covers, not a sample: the live config's own value wins.
    val config =
      GenerateContentConfig(
        temperature = 0.9f,
        topP = 0.8f,
        topK = 99,
        maxOutputTokens = 4096,
        mediaResolution = MediaResolution.MEDIA_RESOLUTION_HIGH,
        seed = 1,
      )
    val agent =
      LlmAgent(
        name = "test",
        model = DummyModel("gemini-2.0-flash-live"),
        generateContentConfig = config,
      )
    val context = InvocationContext(session = testSession(), runConfig = RunConfig(), agent = agent)
    val request =
      LlmRequest()
        .copy(
          liveConnectConfig =
            LiveConnectConfig(
              temperature = 0.1f,
              topP = 0.2f,
              topK = 1,
              maxOutputTokens = 16,
              mediaResolution = MediaResolution.MEDIA_RESOLUTION_LOW,
              seed = 2,
            )
        )

    val live = BasicRequestProcessor().process(context, request).liveConnectConfig

    assertEquals(0.1f, live.temperature)
    assertEquals(0.2f, live.topP)
    assertEquals(1, live.topK)
    assertEquals(16, live.maxOutputTokens)
    assertEquals(MediaResolution.MEDIA_RESOLUTION_LOW, live.mediaResolution)
    assertEquals(2, live.seed)
  }

  @Test
  fun run_withNullRunConfigAndAgentSampling_stillFoldsSampling() = runBlocking {
    // The fold is unconditional, as in ADK Python, so a null run config still gets it.
    val agent =
      LlmAgent(
        name = "test",
        model = DummyModel("gemini-2.0-flash-live"),
        generateContentConfig = GenerateContentConfig(seed = 7),
      )
    val context = InvocationContext(session = testSession(), runConfig = null, agent = agent)

    val live = BasicRequestProcessor().process(context, LlmRequest()).liveConnectConfig

    assertEquals(7, live.seed)
  }
}
