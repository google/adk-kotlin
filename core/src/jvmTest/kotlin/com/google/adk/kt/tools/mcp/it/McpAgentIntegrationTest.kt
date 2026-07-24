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

package com.google.adk.kt.tools.mcp.it

import com.google.adk.kt.agents.Instruction
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.models.Gemini
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.testing.DummyModel
import com.google.adk.kt.testing.modelFunctionCallResponse
import com.google.adk.kt.testing.modelMessage
import com.google.adk.kt.testing.userMessage
import com.google.adk.kt.tools.BaseTool
import com.google.common.truth.Truth.assertThat
import io.modelcontextprotocol.spec.McpSchema
import java.nio.file.Files
import kotlin.test.BeforeTest
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.fail
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assume

/**
 * End-to-end integration test that drives a real [FakeMcpServer] subprocess **through the full ADK
 * agent loop**: a [DummyModel] (or a real Gemini model) emits a tool-call and an [InMemoryRunner]
 * dispatches it to a live `McpToolset`.
 *
 * Unlike [McpToolsetIntegrationTest], which calls `tool.run(...)` directly, this reaches the
 * result-marshalling boundary between the foreign MCP SDK and ADK's conversation types: how a
 * `CallToolResult` becomes the `FunctionResponse` event the runner persists (the data the model's
 * next turn sees).
 *
 * Shared subprocess/PID/toolset helpers live in [McpIntegrationTestSupport].
 */
class McpAgentIntegrationTest {

  /** Skips the suite when [DISABLE_IT_ENV] is truthy (e.g. sandboxes that forbid subprocesses). */
  @BeforeTest fun skipIfDisabled() = assumeMcpItEnabled()

  @Test
  fun runAsync_modelCallsAddTool_marshalsRawCallToolResultIntoFunctionResponse(): Unit =
    runBlocking {
      val pidFile = Files.createTempFile("adk-mcp-agent-it-pid", ".txt")
      try {
        newToolset(pidFile = pidFile).use { toolset ->
          // A two-turn script: turn 1 calls the server's `add` tool with typed integer args; turn 2
          // is the final text the model produces after seeing the tool response.
          val agent =
            LlmAgent(
              name = AGENT_NAME,
              model =
                DummyModel.createSequential(
                  "mock-model",
                  listOf(
                    modelFunctionCallResponse(
                      FakeMcpServer.TOOL_ADD,
                      mapOf("a" to 2, "b" to 3),
                      id = CALL_ID,
                    ),
                    LlmResponse(content = modelMessage(FINAL_TEXT)),
                  ),
                ),
              toolsets = listOf(toolset),
            )
          val runner = InMemoryRunner(agent = agent)

          val events =
            runner
              .runAsync(
                userId = "user1",
                sessionId = "session1",
                newMessage = userMessage("add 2 and 3"),
              )
              .toList()

          // The single function-response event the runner merged and persisted -- byte-for-byte the
          // payload the next LLM turn is handed.
          val response =
            events
              .firstOrNull { it.functionResponses().isNotEmpty() }
              ?.functionResponses()
              ?.single() ?: fail("expected a function-response event, got: $events")

          // Correlated back to the model's FunctionCall by name and id.
          assertThat(response.name).isEqualTo(FakeMcpServer.TOOL_ADD)
          assertThat(response.id).isEqualTo(CALL_ID)

          // THE MARSHALLING FINDING: McpTool.run returns the raw SDK CallToolResult, and ADK's
          // generic wrapping (InvocationContext.toFinalResponseMap) puts any non-Map result under a
          // single "result" key (BaseTool.RESULT_KEY). So the model receives the foreign
          // CallToolResult object itself, the answer "5" buried inside rather than JSON-native. We
          // pin this exact (buggy) shape.
          assertThat(response.response.keys).containsExactly(BaseTool.RESULT_KEY)
          val raw = response.response[BaseTool.RESULT_KEY]
          assertThat(raw).isInstanceOf(McpSchema.CallToolResult::class.java)
          val callResult = raw as McpSchema.CallToolResult
          assertThat((callResult.content().single() as McpSchema.TextContent).text()).isEqualTo("5")

          // The agent loop ran to completion: after seeing the tool response it emitted its final
          // text.
          val finalEvent = events.last()
          assertThat(finalEvent.author).isEqualTo(AGENT_NAME)
          assertThat(finalEvent.content?.parts?.singleOrNull()?.text).isEqualTo(FINAL_TEXT)
        }
      } finally {
        // McpToolset.close() is fire-and-forget (closeGracefully().subscribe()), so a fast worker
        // JVM can exit before the async SIGTERM reaches the child, orphaning it. Known lifecycle
        // gap
        // (the direct-call tests leak the same way); reap the child deterministically via its
        // recorded PID.
        killIfRunning(pidFile)
        Files.deleteIfExists(pidFile)
      }
    }

  // Regression guard. It FAILS today: McpTool.run returns the raw CallToolResult, so the
  // FunctionResponse the model receives is {"result": <SDK object>} rather than a clean,
  // JSON-native payload carrying the tool's output. Python ADK converts the result
  // (CallToolResult.model_dump(exclude_none=True, mode="json")); the Kotlin port omitted that step.
  // Drop @Ignore once McpTool.run returns a converted map. The companion characterization test
  // above pins the current (buggy) shape and must be updated at the same time.
  @Ignore
  @Test
  fun runAsync_modelCallsAddTool_returnsCleanJsonNativeResponse(): Unit = runBlocking {
    val pidFile = Files.createTempFile("adk-mcp-agent-it-clean-pid", ".txt")
    try {
      newToolset(pidFile = pidFile).use { toolset ->
        val agent =
          LlmAgent(
            name = AGENT_NAME,
            model =
              DummyModel.createSequential(
                "mock-model",
                listOf(
                  modelFunctionCallResponse(
                    FakeMcpServer.TOOL_ADD,
                    mapOf("a" to 2, "b" to 3),
                    id = CALL_ID,
                  ),
                  LlmResponse(content = modelMessage(FINAL_TEXT)),
                ),
              ),
            toolsets = listOf(toolset),
          )
        val events =
          InMemoryRunner(agent = agent)
            .runAsync(
              userId = "user1",
              sessionId = "session1",
              newMessage = userMessage("add 2 and 3"),
            )
            .toList()

        val response =
          events.firstOrNull { it.functionResponses().isNotEmpty() }?.functionResponses()?.single()
            ?: fail("expected a function-response event, got: $events")

        // The binding invariant any reasonable fix must satisfy. Mirroring the close-leak guard, we
        // deliberately do NOT pin the exact keys/structure a fix chooses:
        //  1. the response holds no foreign SDK object -- it is JSON-native throughout, so it is
        //     intelligible to the model and serializable by any session backend; and
        //  2. the tool's actual output ("5") is present as plain data, not buried inside an opaque
        //     object.
        // A fix mirroring Python ADK would also surface isError at the top level, but we leave the
        // exact shape to the implementer.
        assertThat(isJsonNative(response.response)).isTrue()
        assertThat(flattenScalars(response.response)).contains("5")
      }
    } finally {
      killIfRunning(pidFile)
      Files.deleteIfExists(pidFile)
    }
  }

  // Live variant of the happy-path test, driven by a REAL Gemini model instead of DummyModel (gated
  // by assumeGeminiItEnabled). The real model actually receives the prior turn's FunctionResponse,
  // so this exercises live serialization of {"result": <CallToolResult>} out to the Gemini API.
  @Test
  fun gemini_runAsync_modelCallsAddTool_invokesMcpToolAndAnswers(): Unit = runBlocking {
    assumeGeminiItEnabled()
    val pidFile = Files.createTempFile("adk-mcp-agent-it-gemini-pid", ".txt")
    try {
      newToolset(pidFile = pidFile).use { toolset ->
        // A strong instruction makes the tool call deterministic: the model must delegate addition
        // to the `add` tool. We assert the tool was genuinely invoked, and best-effort that the
        // answer shows "5".
        val agent =
          LlmAgent(
            name = AGENT_NAME,
            model = Gemini(name = geminiModel(), apiKey = envOrNull(GOOGLE_API_KEY_ENV)),
            instruction =
              Instruction(
                "You are a calculator. To add two numbers you MUST call the `add` tool; never " +
                  "compute the sum yourself. After the tool returns, state the result."
              ),
            toolsets = listOf(toolset),
          )

        val events =
          InMemoryRunner(agent = agent)
            .runAsync(
              userId = "user1",
              sessionId = "session1",
              newMessage = userMessage("What is 2 + 3?"),
            )
            .toList()

        // Primary, model-behavior-robust assertion: the real model decided to call the MCP tool,
        // and ADK routed it to the live server and fed a response back.
        val response =
          events.firstOrNull { it.functionResponses().isNotEmpty() }?.functionResponses()?.single()
            ?: fail(
              "expected the model to call the MCP tool, but no function-response event was " +
                "produced; events=$events"
            )
        assertThat(response.name).isEqualTo(FakeMcpServer.TOOL_ADD)

        // Best-effort: the model produced a final answer that reflects the tool's result. If this
        // ever proves flaky on a given model, relax it -- the binding proof is the tool call above.
        val answerText =
          events
            .filter { it.author == AGENT_NAME }
            .flatMap { it.content?.parts ?: emptyList() }
            .mapNotNull { it.text }
            .joinToString(" ")
        assertThat(answerText).contains("5")
      }
    } finally {
      killIfRunning(pidFile)
      Files.deleteIfExists(pidFile)
    }
  }

  /**
   * Gates the live Gemini tests: skips unless [GOOGLE_API_KEY_ENV] is present (a real key is
   * required to call the API) and [GEMINI_DISABLE_IT_ENV] is not truthy. The class-wide
   * [skipIfDisabled] gate ([DISABLE_IT_ENV]) still applies on top of this.
   */
  private fun assumeGeminiItEnabled() {
    Assume.assumeTrue(
      "Live Gemini MCP tests require $GOOGLE_API_KEY_ENV",
      envOrNull(GOOGLE_API_KEY_ENV) != null,
    )
    Assume.assumeFalse(
      "Live Gemini MCP tests disabled via $GEMINI_DISABLE_IT_ENV",
      isEnvTruthy(GEMINI_DISABLE_IT_ENV),
    )
  }

  /** The Gemini model id to drive the live tests; overridable via [GEMINI_MODEL_ENV]. */
  private fun geminiModel(): String = envOrNull(GEMINI_MODEL_ENV) ?: DEFAULT_GEMINI_MODEL

  /**
   * True iff [value] and everything it transitively contains are plain JSON-native types (null,
   * String, Number, Boolean, or a Map/Collection of the same) -- i.e. it holds no foreign SDK
   * object such as [McpSchema.CallToolResult]. JSON-nativeness is what makes a tool response
   * intelligible to the model and serializable by a persistent session backend.
   */
  private fun isJsonNative(value: Any?): Boolean =
    when (value) {
      null,
      is String,
      is Number,
      is Boolean -> true
      is Map<*, *> -> value.keys.all { it is String } && value.values.all { isJsonNative(it) }
      is Collection<*> -> value.all { isJsonNative(it) }
      else -> false
    }

  /** Concatenates every scalar leaf reachable from [value], for a content-presence check. */
  private fun flattenScalars(value: Any?): String =
    when (value) {
      null -> ""
      is Map<*, *> -> value.values.joinToString(" ") { flattenScalars(it) }
      is Collection<*> -> value.joinToString(" ") { flattenScalars(it) }
      else -> value.toString()
    }

  private companion object {
    /** Env var that must be present (a real API key) for the live Gemini tests to run. */
    private const val GOOGLE_API_KEY_ENV = "GOOGLE_API_KEY"

    /** Env var that, when truthy, skips only the live Gemini tests. */
    private const val GEMINI_DISABLE_IT_ENV = "ADK_MCP_GEMINI_DISABLE_IT"

    /** Optional override for the live Gemini model id. */
    private const val GEMINI_MODEL_ENV = "ADK_MCP_GEMINI_MODEL"

    /**
     * Default live Gemini model. A `*-latest` alias is used deliberately so the test does not 404
     * as concrete model ids rotate; it supports function calling, and the lenient assertions
     * tolerate any current flash model. Override with [GEMINI_MODEL_ENV] to pin a specific id.
     */
    private const val DEFAULT_GEMINI_MODEL = "gemini-flash-latest"

    private const val AGENT_NAME = "test-agent"

    /** The id we stamp on the model's FunctionCall; the FunctionResponse must echo it back. */
    private const val CALL_ID = "call_1"

    /** The model's turn-2 answer once it has seen the tool response. */
    private const val FINAL_TEXT = "Done."
  }
}
