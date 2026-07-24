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
import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import kotlin.test.BeforeTest
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
  fun runAsync_modelCallsAddTool_marshalsResultIntoJsonNativeFunctionResponse(): Unit =
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

          // The tool's output "5" is retrievable as the single text content of the converted
          // result.
          // That the whole payload is JSON-native (serializable by a persistent backend) is proven
          // end-to-end by McpResultSerializationIntegrationTest, so it is not re-asserted here.
          assertThat(textOf(response.response)).isEqualTo("5")

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
