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
import com.google.adk.kt.events.Event
import com.google.adk.kt.logging.LoggerFactory
import com.google.adk.kt.models.Gemini
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.testing.DummyModel
import com.google.adk.kt.testing.modelFunctionCallResponse
import com.google.adk.kt.testing.modelMessage
import com.google.adk.kt.testing.modelTransferToAgentResponse
import com.google.adk.kt.testing.textAgent
import com.google.adk.kt.testing.userMessage
import com.google.adk.kt.tools.mcp.McpToolset
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.nio.file.Files
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.fail
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
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

  @Test
  fun runAsync_serverAdvertisesTransferToAgent_adkStillTransfersToSubAgent(): Unit = runBlocking {
    val pidFile = Files.createTempFile("adk-mcp-agent-it-reserved-pid", ".txt")
    try {
      newToolset(pidFile = pidFile).use { toolset ->
        val rootRequests = mutableListOf<LlmRequest>()
        val root =
          LlmAgent(
            name = AGENT_NAME,
            model =
              DummyModel("root-model") { request ->
                rootRequests += request
                if (rootRequests.size == 1) {
                  flowOf(modelTransferToAgentResponse(HELPER_NAME, id = CALL_ID))
                } else {
                  emptyFlow()
                }
              },
            subAgents = listOf(textAgent(HELPER_NAME, HELPER_TEXT)),
            toolsets = listOf(toolset),
          )

        val events =
          InMemoryRunner(agent = root)
            .runAsync(
              userId = "user1",
              sessionId = "session1",
              newMessage = userMessage("hand this to the helper"),
            )
            .toList()

        // ADK's tool, not the server's, took the call: the transfer happened and the helper ran.
        assertThat(events.mapNotNull { it.actions.transferToAgent }).containsExactly(HELPER_NAME)
        assertThat(
            events
              .filter { it.author == HELPER_NAME }
              .flatMap { it.content?.parts.orEmpty() }
              .mapNotNull { it.text }
          )
          .containsExactly(HELPER_TEXT)
        // The model is offered ADK's transfer tool alone, not a second one from the server.
        val declaredNames =
          rootRequests
            .first()
            .config
            .tools
            .orEmpty()
            .flatMap { it.functionDeclarations.orEmpty() }
            .map { it.name }
        assertThat(declaredNames.filter { it == FakeMcpServer.TOOL_TRANSFER_TO_AGENT })
          .containsExactly(FakeMcpServer.TOOL_TRANSFER_TO_AGENT)
      }
    } finally {
      killIfRunning(pidFile)
      Files.deleteIfExists(pidFile)
    }
  }

  // Live SUCCESS half of the get_record pair (shared setup in runGetRecordAgent): the model fetches
  // an existing record and relays it.
  @Test
  fun gemini_getRecord_existingId_modelInvokesToolAndReportsRecord(): Unit = runBlocking {
    assumeGeminiItEnabled()
    val pidFile = Files.createTempFile("adk-mcp-agent-it-gemini-record-ok-pid", ".txt")
    try {
      newToolset(pidFile = pidFile).use { toolset ->
        val events = runGetRecordAgent(toolset, "Look up record $EXISTING_RECORD_ID.")

        // #1: the model actually called the tool (it can't know a record exists without asking).
        val response = singleFunctionResponse(events)
        assertThat(response.name).isEqualTo(FakeMcpServer.TOOL_GET_RECORD)

        // #2: a non-error result came back as a JSON-native map (McpTool.run's conversion): isError
        // is false and the record content is carried as plain text. Only isError + payload differ
        // from the error test.
        assertThat(response.response["isError"]).isEqualTo(false)
        assertThat(singleTextContent(response.response))
          .isEqualTo(FakeMcpServer.recordContent(EXISTING_RECORD_ID))

        // Best-effort: the model relayed the record rather than reporting a failure.
        val answerText = agentAnswerText(events).lowercase()
        assertWithMessage(
            "expected the model to report the fetched record, but got: %s",
            answerText,
          )
          .that(answerText.contains(FakeMcpServer.recordContent(EXISTING_RECORD_ID)))
          .isTrue()
      }
    } finally {
      killIfRunning(pidFile)
      Files.deleteIfExists(pidFile)
    }
  }

  // Live FAILURE half of the pair, and the reason it exists: an MCP tool error comes back in-band
  // as isError=true inside the JSON-native result, not as a transport failure, so a model has to
  // read the result to notice it -- does a real one report the failure rather than fabricate a
  // record? The neutral tool + arbitrary poison id mean nothing tips it off before the call. (A
  // DummyModel version would only re-assert marshalling the `add` characterization already pins.)
  @Test
  fun gemini_getRecord_missingId_modelInvokesToolAndReportsFailure(): Unit = runBlocking {
    assumeGeminiItEnabled()
    val pidFile = Files.createTempFile("adk-mcp-agent-it-gemini-record-missing-pid", ".txt")
    try {
      newToolset(pidFile = pidFile).use { toolset ->
        val events =
          runGetRecordAgent(toolset, "Look up record ${FakeMcpServer.MISSING_RECORD_ID}.")

        // #1: the model called the tool (the poison id is arbitrary, so it can't shortcut).
        val response = singleFunctionResponse(events)
        assertThat(response.name).isEqualTo(FakeMcpServer.TOOL_GET_RECORD)

        // #2: the error reached the model in the JSON-native map (McpTool.run's conversion):
        // isError=true and the message verbatim. A non-reaction below would be the model's, not ADK
        // dropping it.
        assertThat(response.response["isError"]).isEqualTo(true)
        assertThat(singleTextContent(response.response))
          .isEqualTo(FakeMcpServer.recordNotFoundMessage(FakeMcpServer.MISSING_RECORD_ID))

        // The behavioral check: the model reported the failure. Best-effort; widen FAILURE_TERMS
        // if a future model gets flaky.
        val answerText = agentAnswerText(events).lowercase()
        assertWithMessage(
            "expected the model's final answer to report the lookup failure, but got: %s",
            answerText,
          )
          .that(FAILURE_TERMS.any { it in answerText })
          .isTrue()
      }
    } finally {
      killIfRunning(pidFile)
      Files.deleteIfExists(pidFile)
    }
  }

  /**
   * Shared setup for the live get_record pair: runs [userText] through a real Gemini agent whose
   * only tool is the live `get_record` MCP tool, under the fixed [GET_RECORD_INSTRUCTION]. The two
   * tests differ only in the requested id and their assertions, isolating the tool's success/error
   * result as the single variable.
   */
  private suspend fun runGetRecordAgent(toolset: McpToolset, userText: String): List<Event> {
    val agent =
      LlmAgent(
        name = AGENT_NAME,
        model = Gemini(name = geminiModel(), apiKey = envOrNull(GOOGLE_API_KEY_ENV)),
        instruction = Instruction(GET_RECORD_INSTRUCTION),
        toolsets = listOf(toolset),
      )
    val events =
      InMemoryRunner(agent = agent)
        .runAsync(userId = "user1", sessionId = "session1", newMessage = userMessage(userText))
        .toList()
    log.info { "Gemini answer for \"$userText\": ${agentAnswerText(events)}" }
    return events
  }

  /** The single function-response the runner produced, or fails the test if no tool was called. */
  private fun singleFunctionResponse(events: List<Event>) =
    events.firstOrNull { it.functionResponses().isNotEmpty() }?.functionResponses()?.single()
      ?: fail(
        "expected the model to call the MCP tool, but no function-response event was produced; " +
          "events=$events"
      )

  /** Concatenates the text of every event authored by the agent, for a content-presence check. */
  private fun agentAnswerText(events: List<Event>): String =
    events
      .filter { it.author == AGENT_NAME }
      .flatMap { it.content?.parts ?: emptyList() }
      .mapNotNull { it.text }
      .joinToString(" ")

  /**
   * The text of the single content entry in the JSON-native map McpTool.run produces from a
   * `CallToolResult` carrying one text content (the SDK mapper renders it as `{"content":
   * [{"type": "text", "text": ...}], ...}`).
   */
  private fun singleTextContent(responseMap: Map<*, *>): String =
    ((responseMap["content"] as List<*>).single() as Map<*, *>)["text"] as String

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
    /** Logs the model's answer in the live Gemini tests (view with `--info`). */
    private val log = LoggerFactory.getLogger(McpAgentIntegrationTest::class)

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

    /** The sub-agent the root agent transfers to. */
    private const val HELPER_NAME = "helper"

    /** The helper's only answer. */
    private const val HELPER_TEXT = "The helper answered."

    /** An id `get_record` has a record for; the happy half of the pair (any id != poison). */
    private const val EXISTING_RECORD_ID = 7

    /**
     * Instruction shared by the live pair: forces the tool call and reports the outcome faithfully,
     * priming neither success nor failure.
     */
    private const val GET_RECORD_INSTRUCTION =
      "You are a records assistant. Your only way to look up a record is the `get_record` tool. " +
        "When the user asks for a record you MUST call `get_record` with the requested id; never " +
        "invent, guess, or judge the outcome yourself. After the tool returns, report the " +
        "record's contents to the user; if the lookup returned an error, tell the user plainly " +
        "that the lookup failed."

    /** Lowercase substrings a model might use to report a failure; the error test needs any one. */
    private val FAILURE_TERMS =
      listOf(
        "fail",
        "error",
        "could not",
        "couldn't",
        "cannot",
        "can't",
        "unable",
        "unsuccessful",
        "did not succeed",
        "didn't succeed",
        "went wrong",
        "problem",
        "not found",
        "no record",
        "no such",
        "doesn't exist",
        "does not exist",
      )
  }
}
