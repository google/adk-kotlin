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

import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.events.Event
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.serialization.adkJson
import com.google.adk.kt.sessions.InMemorySessionService
import com.google.adk.kt.sessions.Session
import com.google.adk.kt.sessions.SessionService
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

/**
 * Regression guard on the **persistence axis** for the result-marshalling fix: [McpTool.run]
 * converts the foreign MCP SDK [io.modelcontextprotocol.spec.McpSchema.CallToolResult] into a
 * JSON-native map, so the `FunctionResponse` [Event] it produces can be serialized by any
 * [SessionService] that persists events on append. Left as the raw SDK object, ADK would wrap it
 * verbatim as `{"result": <CallToolResult>}` — not JSON-native, so a serializing backend throws.
 *
 * The sibling tests in [McpAgentIntegrationTest] can't catch a regression here:
 * [InMemorySessionService] stores events by reference and never serializes.
 * [SerializingSessionService] reproduces just the serialize-on-append step a real backend performs,
 * on top of an in-memory delegate.
 *
 * Shared subprocess/PID/toolset helpers live in [McpIntegrationTestSupport].
 */
class McpResultSerializationIntegrationTest {

  /** Skips the suite when [DISABLE_IT_ENV] is truthy (e.g. sandboxes that forbid subprocesses). */
  @BeforeTest fun skipIfDisabled() = assumeMcpItEnabled()

  // If McpTool.run regressed to returning the raw CallToolResult, ADK would wrap it as
  // {"result": <SDK object>} and this serializing append would throw — AnySerializer rejects the
  // non-JSON-native value. The conversion in McpTool.run keeps the appended event serializable.
  @Test
  fun runAsync_modelCallsAddTool_serializingBackendPersistsEvent(): Unit = runBlocking {
    val pidFile = Files.createTempFile("adk-mcp-serialize-it-pid", ".txt")
    try {
      newToolset(pidFile = pidFile).use { toolset ->
        // Same two-turn script as the marshalling characterization test: turn 1 calls the server's
        // `add` tool; turn 2 is the final text.
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

        // A regression would surface here, on the function-RESPONSE event: the user-message and
        // function-CALL events are always JSON-native, but the tool response carries the converted
        // CallToolResult.
        val events =
          InMemoryRunner(agent = agent, sessionService = SerializingSessionService())
            .runAsync(
              userId = "user1",
              sessionId = "session1",
              newMessage = userMessage("add 2 and 3"),
            )
            .toList()

        // The function-response event was persisted by the serializing backend (it didn't throw).
        val response =
          events.firstOrNull { it.functionResponses().isNotEmpty() }?.functionResponses()?.single()
            ?: fail("expected a function-response event, got: $events")
        assertThat(response.name).isEqualTo(FakeMcpServer.TOOL_ADD)

        // And the loop reached its second turn, proving the run completed end-to-end.
        assertThat(events.last().content?.parts?.singleOrNull()?.text).isEqualTo(FINAL_TEXT)
      }
    } finally {
      // McpToolset.close() is fire-and-forget, so reap the child deterministically via its PID --
      // see the long-form rationale in McpAgentIntegrationTest.
      killIfRunning(pidFile)
      Files.deleteIfExists(pidFile)
    }
  }

  /**
   * An [InMemorySessionService] that additionally serializes each appended [Event] with [adkJson]
   * (`adkJson.encodeToString(Event.serializer(), event)`), reproducing the one persistence step a
   * real serializing backend performs before storing. Serializing *before* delegating means a
   * non-serializable event throws without being recorded, just as a persistent write would fail.
   * Partial events are skipped.
   */
  private class SerializingSessionService(
    private val delegate: InMemorySessionService = InMemorySessionService()
  ) : SessionService by delegate {
    @OptIn(FrameworkInternalApi::class)
    override suspend fun appendEvent(session: Session, event: Event): Event {
      if (event.partial != true) {
        // The result is intentionally discarded: we only need the serialization side effect -- a
        // non-JSON-native event would make AnySerializer throw here, which is exactly the
        // regression
        // this test guards against.
        val unused = adkJson.encodeToString(Event.serializer(), event)
      }
      return delegate.appendEvent(session, event)
    }
  }

  private companion object {
    private const val AGENT_NAME = "test-agent"

    /** The id we stamp on the model's FunctionCall; the FunctionResponse must echo it back. */
    private const val CALL_ID = "call_1"

    /** The model's turn-2 answer once it has seen the tool response. */
    private const val FINAL_TEXT = "Done."
  }
}
