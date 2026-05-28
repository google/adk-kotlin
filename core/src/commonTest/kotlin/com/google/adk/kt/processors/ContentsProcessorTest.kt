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
import com.google.adk.kt.agents.LlmAgent.IncludeContents
import com.google.adk.kt.events.Event
import com.google.adk.kt.events.EventActions
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.sessions.InMemorySessionService
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.testing.DummyAgent
import com.google.adk.kt.testing.DummyModel
import com.google.adk.kt.testing.modelMessage
import com.google.adk.kt.testing.testSession
import com.google.adk.kt.testing.userMessage
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.FunctionResponse
import com.google.adk.kt.types.Part
import com.google.common.truth.Truth.assertThat
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ContentsProcessorTest {

  @Test
  fun run_withContent_addsContentToRequest() = runTest {
    val agent = DummyAgent()
    val session = testSession()
    val context = InvocationContext(session = session, runConfig = null, agent = agent)

    var request = LlmRequest(contents = mutableListOf(userMessage("hello")))

    val processor = ContentsProcessor()
    request = processor.process(context, request)

    assertEquals(1, request.contents.size)
    assertEquals("hello", request.contents[0].parts.firstOrNull()?.text)
  }

  @Test
  fun run_mixedHistory_insertsInstructionsBeforeLastUserBlock() = runTest {
    val processor = ContentsProcessor()
    var request = LlmRequest(contents = mutableListOf(userMessage("instruction")))
    val context =
      createTestContext(userEvent("u1"), modelEvent("m1"), userEvent("u2"), userEvent("u3"))

    request = processor.process(context, request)

    // Expected: [User(u1), Model(m1), Instruction, User(u2), User(u3)]
    val resultContents = request.contents
    assertThat(resultContents).hasSize(5)
    assertThat(resultContents[0].parts[0].text).isEqualTo("u1")
    assertThat(resultContents[1].parts[0].text).isEqualTo("m1")
    assertThat(resultContents[2].parts[0].text).isEqualTo("instruction")
    assertThat(resultContents[3].parts[0].text).isEqualTo("u2")
    assertThat(resultContents[4].parts[0].text).isEqualTo("u3")
  }

  @Test
  fun run_allUserHistory_insertsInstructionsAtStart() = runTest {
    val processor = ContentsProcessor()
    var request = LlmRequest(contents = mutableListOf(userMessage("instruction")))
    val context = createTestContext(userEvent("u1"))

    request = processor.process(context, request)

    // Expected: [Instruction, User(u1)]
    val resultContents = request.contents
    assertThat(resultContents).hasSize(2)
    assertThat(resultContents[0].parts[0].text).isEqualTo("instruction")
    assertThat(resultContents[1].parts[0].text).isEqualTo("u1")
  }

  @Test
  fun run_historyWithFunctionResponse_insertsInstructionsAfterFunctionResponse() = runTest {
    val processor = ContentsProcessor()
    var request = LlmRequest(contents = listOf(userMessage("instruction")))
    val context =
      createTestContext(
        userEvent("u1"),
        functionCallEvent("foo" to "id1"),
        functionResponseEvent("foo", "id1", mapOf("a" to 1)),
      )

    request = processor.process(context, request)

    // Expected: [User(u1), Model(Call), FunctionResponse, Instruction]
    val resultContents = request.contents
    assertThat(resultContents).hasSize(4)
    assertThat(resultContents[0].parts[0].text).isEqualTo("u1")
    assertThat(resultContents[1].parts[0].functionCall!!.name).isEqualTo("foo")
    assertThat(resultContents[2].parts[0].functionResponse!!.name).isEqualTo("foo")
    assertThat(resultContents[3].parts[0].text).isEqualTo("instruction")
  }

  @Test
  fun run_userMessages_preserved() = runTest {
    val processor = ContentsProcessor()
    var request = LlmRequest()
    val context = createTestContext(userEvent("User message"), agentName = "current_agent")

    request = processor.process(context, request)

    assertThat(request.contents).hasSize(1)
    assertThat(request.contents[0].parts[0].text).isEqualTo("User message")
    assertThat(request.contents[0].role).isEqualTo("user")
  }

  @Test
  fun run_currentAgentMessages_notConverted() = runTest {
    val processor = ContentsProcessor()
    var request = LlmRequest()
    val context =
      createTestContext(
        modelEvent("My own message").copy(author = "current_agent"),
        modelEvent("Other agent message").copy(author = "other_agent"),
        agentName = "current_agent",
      )

    request = processor.process(context, request)

    assertThat(request.contents).hasSize(2)
    assertThat(request.contents[0].role).isEqualTo("model")
    assertThat(request.contents[0].parts[0].text).isEqualTo("My own message")
    assertThat(request.contents[1].role).isEqualTo("user")
    assertThat(request.contents[1].parts[1].text)
      .isEqualTo("[other_agent] said: Other agent message")
  }

  @Test
  fun run_multipleAgentsInConversation_formatsCorrectly() = runTest {
    val processor = ContentsProcessor()
    var request = LlmRequest()
    val context =
      createTestContext(
        userEvent("Hello everyone"),
        modelEvent("Hi from agent1").copy(author = "agent1"),
        modelEvent("Hi from agent2").copy(author = "agent2"),
        agentName = "current_agent",
      )

    request = processor.process(context, request)

    assertThat(request.contents).hasSize(3)
    assertThat(request.contents[0].role).isEqualTo("user")
    assertThat(request.contents[0].parts[0].text).isEqualTo("Hello everyone")
    assertThat(request.contents[1].role).isEqualTo("user")
    assertThat(request.contents[1].parts[1].text).isEqualTo("[agent1] said: Hi from agent1")
    assertThat(request.contents[2].role).isEqualTo("user")
    assertThat(request.contents[2].parts[1].text).isEqualTo("[agent2] said: Hi from agent2")
  }

  @Test
  fun run_otherAgentThoughts_areExcluded() = runTest {
    val processor = ContentsProcessor()
    var request = LlmRequest()
    val context =
      createTestContext(
        Event(
          author = "other_agent",
          content =
            Content(
              role = "model",
              parts =
                listOf(
                  Part(text = "Public message", thought = false),
                  Part(text = "Private thought", thought = true),
                  Part(text = "Another public message"),
                ),
            ),
        ),
        agentName = "current_agent",
      )

    request = processor.process(context, request)

    assertThat(request.contents).hasSize(1)
    assertThat(request.contents[0].role).isEqualTo("user")
    assertThat(request.contents[0].parts).hasSize(3)
    assertThat(request.contents[0].parts[0].text).isEqualTo("For context:")
    assertThat(request.contents[0].parts[1].text).isEqualTo("[other_agent] said: Public message")
    assertThat(request.contents[0].parts[2].text)
      .isEqualTo("[other_agent] said: Another public message")
  }

  @Test
  fun run_anotherAgentEventWithFunctionCall_formatsWithForContextAndAgentPrefix() = runTest {
    val processor = ContentsProcessor()
    var request = LlmRequest()
    val content =
      Content(
        role = "model",
        parts = listOf(Part(functionCall = FunctionCall(name = "test", args = mapOf("a" to "b")))),
      )
    val context =
      createTestContext(
        Event(author = "anotherAgent", content = content),
        agentName = "current_agent",
      )

    request = processor.process(context, request)

    assertThat(request.contents).hasSize(1)
    assertThat(request.contents[0].role).isEqualTo("user")
    assertThat(request.contents[0].parts).hasSize(2)
    assertThat(request.contents[0].parts[0].text).isEqualTo("For context:")
    assertThat(request.contents[0].parts[1].text)
      .isEqualTo("[anotherAgent] called tool `test` with parameters: {\"a\":\"b\"}")
  }

  @Test
  fun run_anotherAgentEventWithFunctionResponse_formatsWithForContextAndAgentPrefix() = runTest {
    val processor = ContentsProcessor()
    var request = LlmRequest()
    val content =
      Content(
        role = "model",
        parts =
          listOf(
            Part(functionResponse = FunctionResponse(name = "test", response = mapOf("c" to "d")))
          ),
      )
    val context =
      createTestContext(
        Event(author = "anotherAgent", content = content),
        agentName = "current_agent",
      )

    request = processor.process(context, request)

    assertThat(request.contents).hasSize(1)
    assertThat(request.contents[0].role).isEqualTo("user")
    assertThat(request.contents[0].parts).hasSize(2)
    assertThat(request.contents[0].parts[0].text).isEqualTo("For context:")
    assertThat(request.contents[0].parts[1].text)
      .isEqualTo("[anotherAgent] `test` tool returned result: {\"c\":\"d\"}")
  }

  @Test
  fun run_branchFiltering_childSeesParent() = runTest {
    val processor = ContentsProcessor()
    var request = LlmRequest()
    val context =
      createTestContext(
        userEvent("User message"),
        modelEvent("Parent agent response").copy(author = "parent_agent", branch = "parent_agent"),
        modelEvent("Child agent response")
          .copy(author = "child_agent", branch = "parent_agent.child_agent"),
        modelEvent("Excluded response 1")
          .copy(author = "child_agent", branch = "parent_agent.child_agent000"),
        modelEvent("Excluded response 2")
          .copy(author = "child_agent", branch = "parent_agent.child"),
        branch = "parent_agent.child_agent",
        agentName = "child_agent",
      )

    request = processor.process(context, request)

    val result = request.contents
    assertThat(result).hasSize(3)
    assertThat(result[0].role).isEqualTo("user")
    assertThat(result[0].parts[0].text).isEqualTo("User message")
    assertThat(result[1].role).isEqualTo("user")
    assertThat(result[1].parts[1].text).isEqualTo("[parent_agent] said: Parent agent response")
    assertThat(result[2].role).isEqualTo("model")
    assertThat(result[2].parts[0].text).isEqualTo("Child agent response")
  }

  @Test
  fun run_branchFiltering_excludesSiblingAgents() = runTest {
    val processor = ContentsProcessor()
    var request = LlmRequest()
    val context =
      createTestContext(
        userEvent("User message"),
        modelEvent("Parent response").copy(author = "parent_agent", branch = "parent_agent"),
        modelEvent("Child1 response")
          .copy(author = "child_agent1", branch = "parent_agent.child_agent1"),
        modelEvent("Sibling response")
          .copy(author = "child_agent2", branch = "parent_agent.child_agent2"),
        branch = "parent_agent.child_agent1",
        agentName = "child_agent1",
      )

    request = processor.process(context, request)

    val result = request.contents
    assertThat(result).hasSize(3)
    assertThat(result[0].role).isEqualTo("user")
    assertThat(result[0].parts[0].text).isEqualTo("User message")
    assertThat(result[1].role).isEqualTo("user")
    assertThat(result[1].parts[1].text).isEqualTo("[parent_agent] said: Parent response")
    assertThat(result[2].role).isEqualTo("model")
    assertThat(result[2].parts[0].text).isEqualTo("Child1 response")
  }

  @Test
  fun run_branchFiltering_noBranchAllowsAll() = runTest {
    val processor = ContentsProcessor()
    var request = LlmRequest()
    val context =
      createTestContext(
        userEvent("No branch message").copy(branch = null),
        modelEvent("Agent with branch").copy(author = "agent1", branch = "agent1"),
        userEvent("Another no branch").copy(branch = null),
        branch = null,
        agentName = "current_agent",
      )

    request = processor.process(context, request)

    val result = request.contents
    assertThat(result).hasSize(3)
    assertThat(result[0].role).isEqualTo("user")
    assertThat(result[0].parts[0].text).isEqualTo("No branch message")
    assertThat(result[1].role).isEqualTo("user")
    assertThat(result[1].parts[1].text).isEqualTo("[agent1] said: Agent with branch")
    assertThat(result[2].role).isEqualTo("user")
    assertThat(result[2].parts[0].text).isEqualTo("Another no branch")
  }

  @Test
  fun run_branchFiltering_grandchildSeesGrandparent() = runTest {
    val processor = ContentsProcessor()
    var request = LlmRequest()
    val context =
      createTestContext(
        modelEvent("Grandparent response")
          .copy(author = "grandparent_agent", branch = "grandparent_agent"),
        modelEvent("Parent response")
          .copy(author = "parent_agent", branch = "grandparent_agent.parent_agent"),
        modelEvent("Grandchild response")
          .copy(
            author = "grandchild_agent",
            branch = "grandparent_agent.parent_agent.grandchild_agent",
          ),
        modelEvent("Sibling response")
          .copy(author = "sibling_agent", branch = "grandparent_agent.parent_agent.sibling_agent"),
        branch = "grandparent_agent.parent_agent.grandchild_agent",
        agentName = "grandchild_agent",
      )

    request = processor.process(context, request)

    val result = request.contents
    assertThat(result).hasSize(3)
    assertThat(result[0].role).isEqualTo("user")
    assertThat(result[0].parts[1].text).isEqualTo("[grandparent_agent] said: Grandparent response")
    assertThat(result[1].role).isEqualTo("user")
    assertThat(result[1].parts[1].text).isEqualTo("[parent_agent] said: Parent response")
    assertThat(result[2].role).isEqualTo("model")
    assertThat(result[2].parts[0].text).isEqualTo("Grandchild response")
  }

  @Test
  fun run_branchFiltering_parentCannotSeeChild() = runTest {
    val processor = ContentsProcessor()
    var request = LlmRequest()
    val context =
      createTestContext(
        userEvent("User message"),
        modelEvent("Parent response").copy(author = "parent_agent", branch = "parent_agent"),
        modelEvent("Child response")
          .copy(author = "child_agent", branch = "parent_agent.child_agent"),
        modelEvent("Grandchild response")
          .copy(author = "grandchild_agent", branch = "parent_agent.child_agent.grandchild_agent"),
        branch = "parent_agent",
        agentName = "parent_agent",
      )

    request = processor.process(context, request)

    val result = request.contents
    assertThat(result).hasSize(2)
    assertThat(result[0].role).isEqualTo("user")
    assertThat(result[0].parts[0].text).isEqualTo("User message")
    assertThat(result[1].role).isEqualTo("model")
    assertThat(result[1].parts[0].text).isEqualTo("Parent response")
  }

  @Test
  fun run_confirmationEvents_areFiltered() = runTest {
    val processor = ContentsProcessor()
    var request = LlmRequest()
    val context =
      createTestContext(
        userEvent("Delete the file"),
        functionCallEvent("adk_request_confirmation" to "confirm_123"),
        functionResponseEvent(
          "adk_request_confirmation",
          "confirm_123",
          mapOf("response" to """{"confirmed": true}"""),
        ),
        userEvent("File deleted successfully"),
      )

    request = processor.process(context, request)

    assertThat(request.contents).hasSize(2)
    assertThat(request.contents[0].parts[0].text).isEqualTo("Delete the file")
    assertThat(request.contents[1].parts[0].text).isEqualTo("File deleted successfully")
  }

  @Test
  fun run_authEvent_filtersOut() = runTest {
    val processor = ContentsProcessor()
    var request = LlmRequest()
    val context =
      createTestContext(
        userEvent("Please authenticate"),
        functionCallEvent("adk_request_credential" to "auth_123").copy(author = "testAgent"),
        functionResponseEvent("adk_request_credential", "auth_123", mapOf("response" to "token")),
        userEvent("Continue after auth"),
      )

    request = processor.process(context, request)

    assertThat(request.contents).hasSize(2)
    assertThat(request.contents[0].parts[0].text).isEqualTo("Please authenticate")
    assertThat(request.contents[1].parts[0].text).isEqualTo("Continue after auth")
  }

  @Test
  fun run_eventsWithEmptyContent_areSkipped() = runTest {
    val processor = ContentsProcessor()
    var request = LlmRequest()
    val context =
      createTestContext(
        userEvent("Hello"),
        Event(author = "testAgent", content = null),
        Event(author = "testAgent", content = Content(role = "model", parts = emptyList())),
        Event(author = "user", content = Content(role = "user", parts = listOf(Part(text = "")))),
        Event(
          author = "user",
          content =
            Content(role = "user", parts = listOf(Part(text = ""), Part(text = "Mixed content"))),
        ),
        userEvent("How are you?"),
      )

    request = processor.process(context, request)

    assertThat(request.contents).hasSize(3)
    assertThat(request.contents[0].parts[0].text).isEqualTo("Hello")
    assertThat(request.contents[1].parts[1].text).isEqualTo("Mixed content")
    assertThat(request.contents[2].parts[0].text).isEqualTo("How are you?")
  }

  @Test
  fun run_functionCallWithThought_notFiltered() = runTest {
    val processor = ContentsProcessor()
    var request = LlmRequest()
    val context =
      createTestContext(
        userEvent("Call the tool"),
        Event(
          author = "testAgent",
          content =
            Content(
              role = "model",
              parts =
                listOf(
                  Part(text = "Let me think", thought = true),
                  Part(
                    functionCall =
                      FunctionCall(name = "test_tool", args = emptyMap(), id = "fc_123"),
                    thought = true,
                  ),
                  Part(text = "Planning next steps", thought = true),
                ),
            ),
        ),
      )

    request = processor.process(context, request)

    assertThat(request.contents).hasSize(2)
    assertThat(request.contents[1].parts).hasSize(3)
    assertThat(request.contents[1].parts[1].functionCall?.name).isEqualTo("test_tool")
  }

  @Test
  fun run_functionResponseWithThought_notFiltered() = runTest {
    val processor = ContentsProcessor()
    var request = LlmRequest()
    val context =
      createTestContext(
        userEvent("Call the tool"),
        Event(
          author = "testAgent",
          content =
            Content(
              role = "model",
              parts =
                listOf(
                  Part(
                    functionCall =
                      FunctionCall(name = "test_tool", args = emptyMap(), id = "fc_123")
                  )
                ),
            ),
        ),
        Event(
          author = "testAgent",
          content =
            Content(
              role = "function",
              parts =
                listOf(
                  Part(
                    functionResponse =
                      FunctionResponse(
                        name = "test_tool",
                        id = "fc_123",
                        response = mapOf("res" to "ok"),
                      ),
                    thought = true,
                  )
                ),
            ),
        ),
      )

    request = processor.process(context, request)

    assertThat(request.contents).hasSize(3)
    assertThat(request.contents[2].parts).hasSize(1)
    assertThat(request.contents[2].parts[0].functionResponse?.name).isEqualTo("test_tool")
  }

  // HistoryRewriterProcessor Tests

  @Test
  fun rearrangeEventsForLatestFunctionResponse_parallelResponses_mergesResponses() = runTest {
    val context =
      createTestContext(
        userEvent("call tools"),
        functionCallEvent("tool1" to "call_1", "tool2" to "call_2"),
        functionResponseEvent("tool1", "call_1", mapOf("res" to "1")),
        functionResponseEvent("tool2", "call_2", mapOf("res" to "2")),
      )
    val processor = ContentsProcessor()
    var request = LlmRequest()

    request = processor.process(context, request)

    val result = request.contents
    assertThat(result).hasSize(3)

    assertThat(result[2].parts).hasSize(2)
    assertThat(result[2].parts.map { it.functionResponse!!.name }).containsExactly("tool1", "tool2")
  }

  @Test
  fun rearrangeEventsForAsyncFunctionResponsesInHistory_asyncCalls_movesAndMergesResponses() =
    runTest {
      val context =
        createTestContext(
          userEvent("start"),
          functionCallEvent("toolA" to "adk-call_A"),
          userEvent("intermediate"),
          functionResponseEvent("toolA", "adk-call_A", mapOf("res" to "A")),
          userEvent("end"),
        )
      val processor = ContentsProcessor()
      var request = LlmRequest()

      request = processor.process(context, request)

      val result = request.contents
      assertThat(result).hasSize(5)

      assertThat(result[0].parts[0].text).isEqualTo("start")
      assertThat(result[1].parts[0].functionCall!!.id).isNull() // The id gets stripped!
      assertThat(result[2].parts[0].functionResponse!!.id).isNull() // The id gets stripped!
      assertThat(result[3].parts[0].text).isEqualTo("intermediate")
      assertThat(result[4].parts[0].text).isEqualTo("end")
    }

  @Test
  fun rearrangeEventsForLatestFunctionResponse_basicFunctionCall_noRearrangement() = runTest {
    val processor = ContentsProcessor()
    var request = LlmRequest()
    val context =
      createTestContext(
        userEvent("Search for test"),
        functionCallEvent("search_tool" to "call_123"),
        functionResponseEvent(
          "search_tool",
          "call_123",
          mapOf("results" to listOf("item1", "item2")),
        ),
      )

    request = processor.process(context, request)

    val result = request.contents
    assertThat(result).hasSize(3)
    assertThat(result[0].parts[0].text).isEqualTo("Search for test")
    assertThat(result[1].parts[0].functionCall!!.name).isEqualTo("search_tool")
    assertThat(result[2].parts[0].functionResponse!!.name).isEqualTo("search_tool")
  }

  @Test
  fun rearrangeEventsForLatestFunctionResponse_intermediateResponse_keepsLastResponse() = runTest {
    val processor = ContentsProcessor()
    var request = LlmRequest()
    val context =
      createTestContext(
        userEvent("Run long process"),
        functionCallEvent("long_running_tool" to "long_call_123"),
        functionResponseEvent(
          "long_running_tool",
          "long_call_123",
          mapOf("status" to "processing"),
        ),
        modelEvent("Still processing..."),
        functionResponseEvent("long_running_tool", "long_call_123", mapOf("status" to "completed")),
      )

    request = processor.process(context, request)

    val result = request.contents
    assertThat(result).hasSize(3)
    assertThat(result[0].parts[0].text).isEqualTo("Run long process")
    assertThat(result[1].parts[0].functionCall!!.name).isEqualTo("long_running_tool")
    assertThat(result[2].parts[0].functionResponse!!.response.get("status")).isEqualTo("completed")
  }

  @Test
  fun rearrangeEventsForLatestFunctionResponse_mixedLroAndNormalCalls_mergesLastResponse() =
    runTest {
      val processor = ContentsProcessor()
      var request = LlmRequest()
      val context =
        createTestContext(
          userEvent("Analyze data and search for info"),
          Event(
            author = "testAgent",
            content =
              Content(
                role = "model",
                parts =
                  listOf(
                    Part(
                      functionCall =
                        FunctionCall(
                          name = "long_running_tool",
                          args = emptyMap(),
                          id = "lro_call_456",
                        )
                    ),
                    Part(
                      functionCall =
                        FunctionCall(
                          name = "search_tool",
                          args = emptyMap(),
                          id = "normal_call_789",
                        )
                    ),
                  ),
              ),
          ),
          Event(
            author = "testAgent",
            content =
              Content(
                role = "function",
                parts =
                  listOf(
                    Part(
                      functionResponse =
                        FunctionResponse(
                          name = "long_running_tool",
                          id = "lro_call_456",
                          response = mapOf("status" to "processing"),
                        )
                    ),
                    Part(
                      functionResponse =
                        FunctionResponse(
                          name = "search_tool",
                          id = "normal_call_789",
                          response = mapOf("results" to "done"),
                        )
                    ),
                  ),
              ),
          ),
          modelEvent("Analysis in progress, search completed"),
          functionResponseEvent("long_running_tool", "lro_call_456", mapOf("status" to "completed")),
        )

      request = processor.process(context, request)

      val result = request.contents
      assertThat(result).hasSize(3)
      assertThat(result[2].parts).hasSize(2)
      assertThat(result[2].parts[0].functionResponse!!.name).isEqualTo("long_running_tool")
      assertThat(result[2].parts[0].functionResponse!!.response.get("status"))
        .isEqualTo("completed")
      assertThat(result[2].parts[1].functionResponse!!.name).isEqualTo("search_tool")
    }

  @Test
  fun rearrangeEventsForAsyncFunctionResponsesInHistory_intermediateResponse_keepsLastResponse() =
    runTest {
      val processor = ContentsProcessor()
      var request = LlmRequest()
      val context =
        createTestContext(
          userEvent("Start long process"),
          functionCallEvent("long_running_tool" to "history_call_123"),
          functionResponseEvent(
            "long_running_tool",
            "history_call_123",
            mapOf("status" to "processing"),
          ),
          modelEvent("Still processing..."),
          functionResponseEvent(
            "long_running_tool",
            "history_call_123",
            mapOf("status" to "completed"),
          ),
          modelEvent("Process completed successfully!"),
          userEvent("Great! What's next?"),
        )

      request = processor.process(context, request)

      val result = request.contents
      assertThat(result).hasSize(6)
      assertThat(result[0].parts[0].text).isEqualTo("Start long process")
      assertThat(result[1].parts[0].functionCall!!.name).isEqualTo("long_running_tool")
      assertThat(result[2].parts[0].functionResponse!!.response.get("status"))
        .isEqualTo("completed")
      assertThat(result[3].parts[0].text).isEqualTo("Still processing...")
      assertThat(result[4].parts[0].text).isEqualTo("Process completed successfully!")
      assertThat(result[5].parts[0].text).isEqualTo("Great! What's next?")
    }

  @Test
  fun rearrangeEventsForAsyncFunctionResponsesInHistory_mixedLroAndNormalCalls_mergesLastResponse() =
    runTest {
      val processor = ContentsProcessor()
      var request = LlmRequest()
      val context =
        createTestContext(
          userEvent("Analyze and search simultaneously"),
          Event(
            author = "testAgent",
            content =
              Content(
                role = "model",
                parts =
                  listOf(
                    Part(
                      functionCall =
                        FunctionCall(
                          name = "long_running_tool",
                          args = emptyMap(),
                          id = "history_lro_123",
                        )
                    ),
                    Part(
                      functionCall =
                        FunctionCall(
                          name = "search_tool",
                          args = emptyMap(),
                          id = "history_normal_456",
                        )
                    ),
                  ),
              ),
          ),
          Event(
            author = "testAgent",
            content =
              Content(
                role = "function",
                parts =
                  listOf(
                    Part(
                      functionResponse =
                        FunctionResponse(
                          name = "long_running_tool",
                          id = "history_lro_123",
                          response = mapOf("status" to "processing"),
                        )
                    ),
                    Part(
                      functionResponse =
                        FunctionResponse(
                          name = "search_tool",
                          id = "history_normal_456",
                          response = mapOf("results" to "done"),
                        )
                    ),
                  ),
              ),
          ),
          modelEvent("Analysis continuing, search done"),
          functionResponseEvent(
            "long_running_tool",
            "history_lro_123",
            mapOf("status" to "completed"),
          ),
          modelEvent("All done"),
          userEvent("Cool"),
        )

      request = processor.process(context, request)

      val result = request.contents
      assertThat(result).hasSize(6)
      assertThat(result[2].parts).hasSize(2)
      assertThat(result[2].parts[0].functionResponse!!.name).isEqualTo("long_running_tool")
      assertThat(result[2].parts[0].functionResponse!!.response.get("status"))
        .isEqualTo("completed")
      assertThat(result[2].parts[1].functionResponse!!.name).isEqualTo("search_tool")
      assertThat(result[3].parts[0].text).isEqualTo("Analysis continuing, search done")
      assertThat(result[4].parts[0].text).isEqualTo("All done")
      assertThat(result[5].parts[0].text).isEqualTo("Cool")
    }

  @Test
  fun process_includeContentsDefault_preservesFullHistory() = runTest {
    val processor = ContentsProcessor()
    var request = LlmRequest()
    val context =
      createLlmAgentTestContext(
        userEvent("u1"),
        modelEvent("m1"),
        userEvent("u2"),
        includeContents = IncludeContents.DEFAULT,
      )

    request = processor.process(context, request)

    val result = request.contents
    assertThat(result).hasSize(3)
    assertThat(result[0].parts[0].text).isEqualTo("u1")
    assertThat(result[1].parts[0].text).isEqualTo("m1")
    assertThat(result[2].parts[0].text).isEqualTo("u2")
  }

  @Test
  fun process_includeContentsNone_emptySession_returnsEmpty() = runTest {
    val processor = ContentsProcessor()
    var request = LlmRequest()
    val context = createLlmAgentTestContext(includeContents = IncludeContents.NONE)

    request = processor.process(context, request)

    assertThat(request.contents).isEmpty()
  }

  @Test
  fun process_includeContentsNone_singleUserEvent_returnsOnlyThatEvent() = runTest {
    val processor = ContentsProcessor()
    var request = LlmRequest()
    val context =
      createLlmAgentTestContext(userEvent("only one"), includeContents = IncludeContents.NONE)

    request = processor.process(context, request)

    val result = request.contents
    assertThat(result).hasSize(1)
    assertThat(result[0].role).isEqualTo("user")
    assertThat(result[0].parts[0].text).isEqualTo("only one")
  }

  @Test
  fun process_includeContentsNone_returnsLatestTurnOnly() = runTest {
    val processor = ContentsProcessor()
    var request = LlmRequest()
    val context =
      createLlmAgentTestContext(
        userEvent("a"),
        modelEvent("b"),
        userEvent("c"),
        includeContents = IncludeContents.NONE,
      )

    request = processor.process(context, request)

    val result = request.contents
    assertThat(result).hasSize(1)
    assertThat(result[0].role).isEqualTo("user")
    assertThat(result[0].parts[0].text).isEqualTo("c")
  }

  @Test
  fun process_includeContentsNone_currentTurnFunctionCallAndResponse_bothIncluded() = runTest {
    val processor = ContentsProcessor()
    var request = LlmRequest()
    val context =
      createLlmAgentTestContext(
        userEvent("old"),
        modelEvent("older response"),
        userEvent("current"),
        functionCallEvent("foo" to "id1"),
        functionResponseEvent("foo", "id1", mapOf("a" to 1)),
        includeContents = IncludeContents.NONE,
      )

    request = processor.process(context, request)

    val result = request.contents
    assertThat(result).hasSize(3)
    assertThat(result[0].parts[0].text).isEqualTo("current")
    assertThat(result[1].parts[0].functionCall!!.name).isEqualTo("foo")
    assertThat(result[2].parts[0].functionResponse!!.name).isEqualTo("foo")
  }

  @Test
  fun process_includeContentsNone_otherAgentReplyAfterUser_startsAtOtherAgentReply() = runTest {
    val processor = ContentsProcessor()
    var request = LlmRequest()
    val context =
      createLlmAgentTestContext(
        userEvent("orig"),
        modelEvent("Agent1 reply").copy(author = "agent1"),
        agentName = "current_agent",
        includeContents = IncludeContents.NONE,
      )

    request = processor.process(context, request)

    val result = request.contents
    assertThat(result).hasSize(1)
    assertThat(result[0].role).isEqualTo("user")
    assertThat(result[0].parts[0].text).isEqualTo("For context:")
    assertThat(result[0].parts[1].text).isEqualTo("[agent1] said: Agent1 reply")
  }

  @Test
  fun process_includeContentsNone_authEventAfterUser_findsRealUserBoundary() = runTest {
    val processor = ContentsProcessor()
    var request = LlmRequest()
    val authEvent =
      Event(
        author = "user",
        content =
          Content(
            role = "user",
            parts =
              listOf(
                Part(
                  functionResponse =
                    FunctionResponse(
                      name = "adk_request_credential",
                      id = "auth1",
                      response = mapOf("token" to "x"),
                    )
                )
              ),
          ),
      )
    val context =
      createLlmAgentTestContext(
        userEvent("a"),
        modelEvent("b"),
        userEvent("c"),
        authEvent,
        includeContents = IncludeContents.NONE,
      )

    request = processor.process(context, request)

    val result = request.contents
    assertThat(result).hasSize(1)
    assertThat(result[0].role).isEqualTo("user")
    assertThat(result[0].parts[0].text).isEqualTo("c")
  }

  @Test
  fun process_includeContentsNone_otherBranchUserIgnored_findsLatestSameBranchUser() = runTest {
    val processor = ContentsProcessor()
    var request = LlmRequest()
    val context =
      createLlmAgentTestContext(
        userEvent("same branch user").copy(branch = "parent.child"),
        userEvent("other branch user").copy(branch = "parent.sibling"),
        branch = "parent.child",
        includeContents = IncludeContents.NONE,
      )

    request = processor.process(context, request)

    val result = request.contents
    assertThat(result).hasSize(1)
    assertThat(result[0].role).isEqualTo("user")
    assertThat(result[0].parts[0].text).isEqualTo("same branch user")
  }

  @Test
  fun process_includeContentsNone_nonLlmAgent_defaultsToFullHistory() = runTest {
    val processor = ContentsProcessor()
    var request = LlmRequest()
    // Uses createTestContext (DummyAgent, not LlmAgent) so the cast falls back to DEFAULT.
    val context = createTestContext(userEvent("a"), modelEvent("b"), userEvent("c"))

    request = processor.process(context, request)

    val result = request.contents
    assertThat(result).hasSize(3)
    assertThat(result[0].parts[0].text).isEqualTo("a")
    assertThat(result[1].parts[0].text).isEqualTo("b")
    assertThat(result[2].parts[0].text).isEqualTo("c")
  }

  @Test
  fun process_rewindEvent_dropsRewoundInvocationAndRewindEvent() = runTest {
    val processor = ContentsProcessor()
    var request = LlmRequest()
    val context =
      createTestContext(
        userEvent("first user").copy(invocationId = "inv1"),
        modelEvent("first reply").copy(invocationId = "inv1"),
        userEvent("rewound user").copy(invocationId = "inv2"),
        modelEvent("rewound reply").copy(invocationId = "inv2"),
        Event(
          invocationId = "rewind_inv",
          author = "testAgent",
          actions = EventActions(rewindBeforeInvocationId = "inv2"),
        ),
        userEvent("third user").copy(invocationId = "inv3"),
      )

    request = processor.process(context, request)

    val result = request.contents
    assertThat(result).hasSize(3)
    assertThat(result[0].parts[0].text).isEqualTo("first user")
    assertThat(result[1].parts[0].text).isEqualTo("first reply")
    assertThat(result[2].parts[0].text).isEqualTo("third user")
  }

  @Test
  fun process_chainedRewindEvents_dropsAllRewoundInvocations() = runTest {
    val processor = ContentsProcessor()
    var request = LlmRequest()
    val context =
      createTestContext(
        userEvent("first user").copy(invocationId = "inv1"),
        modelEvent("first reply").copy(invocationId = "inv1"),
        userEvent("second user").copy(invocationId = "inv2"),
        modelEvent("second reply").copy(invocationId = "inv2"),
        Event(
          invocationId = "rewind_inv_a",
          author = "testAgent",
          actions = EventActions(rewindBeforeInvocationId = "inv2"),
        ),
        userEvent("third user").copy(invocationId = "inv3"),
        Event(
          invocationId = "rewind_inv_b",
          author = "testAgent",
          actions = EventActions(rewindBeforeInvocationId = "inv1"),
        ),
        userEvent("fourth user").copy(invocationId = "inv4"),
      )

    request = processor.process(context, request)

    val result = request.contents
    assertThat(result).hasSize(1)
    assertThat(result[0].parts[0].text).isEqualTo("fourth user")
  }

  @Test
  fun process_rewindEventTargetingUnknownInvocation_dropsOnlyRewindEvent() = runTest {
    val processor = ContentsProcessor()
    var request = LlmRequest()
    val context =
      createTestContext(
        userEvent("first user").copy(invocationId = "inv1"),
        modelEvent("first reply").copy(invocationId = "inv1"),
        Event(
          invocationId = "rewind_inv",
          author = "testAgent",
          actions = EventActions(rewindBeforeInvocationId = "nonexistent"),
        ),
        userEvent("second user").copy(invocationId = "inv2"),
      )

    request = processor.process(context, request)

    val result = request.contents
    assertThat(result).hasSize(3)
    assertThat(result[0].parts[0].text).isEqualTo("first user")
    assertThat(result[1].parts[0].text).isEqualTo("first reply")
    assertThat(result[2].parts[0].text).isEqualTo("second user")
  }

  @Test
  fun process_rewindEventWithNullBranch_appliesToBranchedEvents() = runTest {
    val processor = ContentsProcessor()
    var request = LlmRequest()
    // Rewind event has no branch; should still annul same-branch events as the consumer agent.
    val context =
      createTestContext(
        userEvent("first user").copy(invocationId = "inv1", branch = "parent.child"),
        modelEvent("first reply").copy(invocationId = "inv1", branch = "parent.child"),
        userEvent("rewound user").copy(invocationId = "inv2", branch = "parent.child"),
        Event(
          invocationId = "rewind_inv",
          author = "user",
          actions = EventActions(rewindBeforeInvocationId = "inv2"),
        ),
        branch = "parent.child",
      )

    request = processor.process(context, request)

    val result = request.contents
    assertThat(result).hasSize(2)
    assertThat(result[0].parts[0].text).isEqualTo("first user")
    assertThat(result[1].parts[0].text).isEqualTo("first reply")
  }

  @Test
  fun process_includeContentsNone_rewindEventBeforeCurrentTurn_doesNotAffectCurrentTurn() =
    runTest {
      val processor = ContentsProcessor()
      var request = LlmRequest()
      // The current-turn boundary is the newest user event; the rewind event lives entirely in the
      // prior turn and so does not interact with the IncludeContents.NONE slice.
      val context =
        createLlmAgentTestContext(
          userEvent("old user").copy(invocationId = "old_inv"),
          modelEvent("old reply").copy(invocationId = "old_inv"),
          Event(
            invocationId = "rewind_inv",
            author = "user",
            actions = EventActions(rewindBeforeInvocationId = "old_inv"),
          ),
          userEvent("current user").copy(invocationId = "current_inv"),
          includeContents = IncludeContents.NONE,
        )

      request = processor.process(context, request)

      val result = request.contents
      assertThat(result).hasSize(1)
      assertThat(result[0].parts[0].text).isEqualTo("current user")
    }

  @Test
  fun process_rewindEventTargetingFirstEvent_dropsEverything() = runTest {
    val processor = ContentsProcessor()
    var request = LlmRequest()
    val context =
      createTestContext(
        userEvent("first user").copy(invocationId = "inv1"),
        Event(
          invocationId = "rewind_inv",
          author = "testAgent",
          actions = EventActions(rewindBeforeInvocationId = "inv1"),
        ),
      )

    request = processor.process(context, request)

    val result = request.contents
    assertThat(result).isEmpty()
  }

  // Helpers

  private suspend fun createTestContext(
    vararg events: Event,
    branch: String? = null,
    agentName: String = "testAgent",
  ): InvocationContext {
    val sessionService = InMemorySessionService()
    val session = sessionService.createSession(SessionKey("app", "u1", "s1"))
    for (event in events) {
      val unused = sessionService.appendEvent(session, event)
    }
    return InvocationContext(
      session = session,
      branch = branch,
      agent = DummyAgent(name = agentName),
      runConfig = null,
      sessionService = sessionService,
    )
  }

  private fun userEvent(text: String) = Event(author = "user", content = userMessage(text))

  private fun modelEvent(text: String) = Event(author = "testAgent", content = modelMessage(text))

  private fun functionCallEvent(vararg calls: Pair<String, String>) =
    Event(
      author = "testAgent",
      content =
        Content(
          role = "model",
          parts =
            calls.map { (name, id) ->
              Part(functionCall = FunctionCall(name = name, args = emptyMap(), id = id))
            },
        ),
    )

  private fun functionResponseEvent(
    name: String,
    id: String,
    response: Map<String, Any?> = mapOf("res" to "ok"),
  ) =
    Event(
      author = "testAgent",
      content =
        Content(
          role = "function",
          parts =
            listOf(
              Part(functionResponse = FunctionResponse(name = name, id = id, response = response))
            ),
        ),
    )

  private suspend fun createLlmAgentTestContext(
    vararg events: Event,
    branch: String? = null,
    agentName: String = "testAgent",
    includeContents: IncludeContents = IncludeContents.DEFAULT,
  ): InvocationContext {
    val sessionService = InMemorySessionService()
    val session = sessionService.createSession(SessionKey("app", "u1", "s1"))
    for (event in events) {
      val unused = sessionService.appendEvent(session, event)
    }
    val agent =
      LlmAgent(
        name = agentName,
        model = DummyModel("test-model"),
        includeContents = includeContents,
      )
    return InvocationContext(
      session = session,
      branch = branch,
      agent = agent,
      runConfig = null,
      sessionService = sessionService,
    )
  }
}
