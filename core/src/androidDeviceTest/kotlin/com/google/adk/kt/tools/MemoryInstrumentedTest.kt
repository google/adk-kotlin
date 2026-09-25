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

package com.google.adk.kt.tools

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.events.Event
import com.google.adk.kt.ids.Uuid
import com.google.adk.kt.memory.InMemoryMemoryService
import com.google.adk.kt.memory.MemoryEntry
import com.google.adk.kt.memory.MemoryService
import com.google.adk.kt.memory.appsearch.AppSearchMemoryService
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.models.Model
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.sessions.room.RoomSessionService
import com.google.adk.kt.testing.modelFunctionCallResponse
import com.google.adk.kt.testing.modelMessage
import com.google.adk.kt.testing.userMessage
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Role
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device memory coverage: a real [LlmAgent] driven through an [InMemoryRunner] over a real Room
 * session database, against both Android memory services, with the model scripted so the assertions
 * stay deterministic.
 *
 * Every case goes through the whole flow - the model asks for the tool, the framework runs it, the
 * response is fed back, and the model answers from it - which is the shape a consumer hits. There
 * is deliberately no lower-level variant asserting the same payload straight off
 * `handleFunctionCalls`: the host tests already pin the tool's return value, and repeating it on a
 * device buys nothing.
 */
@RunWith(AndroidJUnit4::class)
class MemoryInstrumentedTest {

  private lateinit var context: Context
  private lateinit var sessionService: RoomSessionService
  private lateinit var model: ScriptedModel
  // Unique per test: AppSearch LocalStorage persists on disk, so the scope is what isolates runs.
  private val appName = "app-${Uuid.random()}"

  @Before
  fun setUp() {
    context = InstrumentationRegistry.getInstrumentation().targetContext
    context.deleteDatabase(TEST_DB_NAME)
    sessionService = RoomSessionService.fromContext(context, databaseName = TEST_DB_NAME)
  }

  @After
  fun tearDown() {
    runCatching { sessionService.close() }
    context.deleteDatabase(TEST_DB_NAME)
  }

  @Test
  fun run_inMemoryMemoryService_loadedMemoryReachesTheNextPrompt(): Unit = runBlocking {
    val memoryService = InMemoryMemoryService()
    memoryService.addMemory(appName, USER_ID, listOf(memoryEntry(MEMORY_TEXT)))

    val events = runTurn(memoryService, "session-in-memory")

    assertThat(memoryTextsFrom(events)).containsExactly(MEMORY_TEXT)
    assertThat(memoryTextsSentBackToTheModel()).containsExactly(MEMORY_TEXT)
    assertThat(finalTextOf(events)).isEqualTo(ANSWER)
    // The tool also injects an instruction; without this nothing notices if that stops happening.
    assertThat(instructionsSentToTheModel()).contains("You have memory")
  }

  @Test
  fun run_appSearchMemoryService_loadedMemoryReachesTheNextPrompt(): Unit = runBlocking {
    val memoryService =
      AppSearchMemoryService.fromContext(context, databaseName = APPSEARCH_DB_NAME)
    try {
      memoryService.addMemory(appName, USER_ID, listOf(memoryEntry(MEMORY_TEXT)))

      val events = runTurn(memoryService, "session-appsearch")

      assertThat(memoryTextsFrom(events)).containsExactly(MEMORY_TEXT)
      assertThat(memoryTextsSentBackToTheModel()).containsExactly(MEMORY_TEXT)
      assertThat(finalTextOf(events)).isEqualTo(ANSWER)
    } finally {
      runCatching { memoryService.close() }
    }
  }

  @Test
  fun run_severalStoredMemories_allMatchingOnesReachTheModel(): Unit = runBlocking {
    val memoryService = InMemoryMemoryService()
    memoryService.addMemory(
      appName,
      USER_ID,
      listOf(memoryEntry(MEMORY_TEXT), memoryEntry(SECOND_MEMORY_TEXT), memoryEntry(UNRELATED_TEXT)),
    )

    val events = runTurn(memoryService, "session-several")

    // The unrelated entry shares no word with the query, so it is filtered out rather than sent.
    assertThat(memoryTextsFrom(events)).containsExactly(MEMORY_TEXT, SECOND_MEMORY_TEXT)
    assertThat(memoryTextsSentBackToTheModel()).containsExactly(MEMORY_TEXT, SECOND_MEMORY_TEXT)
    assertThat(finalTextOf(events)).isEqualTo(ANSWER)
  }

  @Test
  fun run_noStoredMemories_toolReturnsAnEmptyListAndTheTurnCompletes(): Unit = runBlocking {
    val events = runTurn(InMemoryMemoryService(), "session-empty")

    assertThat(memoriesFrom(events)).isEmpty()
    assertThat(memoriesSentBackToTheModel()).isEmpty()
    assertThat(finalTextOf(events)).isEqualTo(ANSWER)
  }

  @Test
  fun run_secondTurnAfterReopen_replaysTheDecodedResponseToTheModel(): Unit = runBlocking {
    val memoryService = InMemoryMemoryService()
    memoryService.addMemory(appName, USER_ID, listOf(memoryEntry(MEMORY_TEXT)))
    val sessionId = "session-persist"
    val unused = runTurn(memoryService, sessionId)

    // Reopen the on-disk database with a fresh service instance, so the second turn's prompt is
    // built from rows Room decoded rather than from the objects still held in memory. Without a
    // second turn the decode side of the converters is never exercised against a model.
    sessionService.close()
    sessionService = RoomSessionService.fromContext(context, databaseName = TEST_DB_NAME)
    val persisted = sessionService.getSession(SessionKey(appName, USER_ID, sessionId))
    assertThat(persisted).isNotNull()
    assertThat(memoryTextsFrom(persisted!!.events)).containsExactly(MEMORY_TEXT)

    val followUp = runFollowUpTurn(memoryService, sessionId)

    assertThat(memoryTextsIn(model.requests.single().contents)).containsExactly(MEMORY_TEXT)
    assertThat(finalTextOf(followUp)).isEqualTo(SECOND_ANSWER)
  }

  @Test
  fun run_noMemoryServiceConfigured_turnCompletesCarryingTheErrorPayload(): Unit = runBlocking {
    val events = runTurn(memoryService = null, sessionId = "session-unconfigured")

    val response =
      events.flatMap { it.functionResponses() }.single { it.name == "load_memory" }.response
    assertThat(response).containsEntry("error_code", "UNCONFIGURED")
    // The turn still finishes: an unwired memory service is a tool error, not a crash.
    assertThat(finalTextOf(events)).isEqualTo(ANSWER)
  }

  /**
   * Runs one turn against an agent whose model asks for `load_memory` and then answers, and returns
   * every event the runner emitted.
   */
  private suspend fun runTurn(memoryService: MemoryService?, sessionId: String): List<Event> {
    model =
      ScriptedModel(
        listOf(
          modelFunctionCallResponse("load_memory", mapOf("query" to QUERY), id = "call-1"),
          LlmResponse(content = modelMessage(ANSWER)),
        )
      )
    val agent = LlmAgent(name = AGENT_NAME, model = model, tools = listOf(LoadMemoryTool()))
    val runner =
      InMemoryRunner(
        agent = agent,
        appName = appName,
        sessionService = sessionService,
        memoryService = memoryService,
      )

    return runner
      .runAsync(userId = USER_ID, sessionId = sessionId, newMessage = userMessage(USER_QUESTION))
      .toList()
  }

  /** A second turn on the same session, whose model answers without calling the tool again. */
  private suspend fun runFollowUpTurn(
    memoryService: MemoryService,
    sessionId: String,
  ): List<Event> {
    model = ScriptedModel(listOf(LlmResponse(content = modelMessage(SECOND_ANSWER))))
    val agent = LlmAgent(name = AGENT_NAME, model = model, tools = listOf(LoadMemoryTool()))
    val runner =
      InMemoryRunner(
        agent = agent,
        appName = appName,
        sessionService = sessionService,
        memoryService = memoryService,
      )
    return runner
      .runAsync(userId = USER_ID, sessionId = sessionId, newMessage = userMessage(SECOND_QUESTION))
      .toList()
  }

  /** The text of each memory a `load_memory` response inside [contents] carried. */
  private fun memoryTextsIn(contents: List<Content>): List<String?> =
    contents
      .flatMap { it.parts }
      .mapNotNull { it.functionResponse }
      .single { it.name == "load_memory" }
      .response
      .let { (it[BaseTool.RESULT_KEY] as Map<*, *>)["memories"] as List<*> }
      .map { textOfEntry(it) }

  /**
   * Every entry the second prompt carried back to the model.
   *
   * Asserting on the tool result alone would pass even if the response never reached the model.
   */
  private fun memoriesSentBackToTheModel(): List<*> {
    assertThat(model.requests).hasSize(2)
    return model.requests[1]
      .contents
      .flatMap { it.parts }
      .mapNotNull { it.functionResponse }
      .single { it.name == "load_memory" }
      .response
      .let { (it[BaseTool.RESULT_KEY] as Map<*, *>)["memories"] as List<*> }
  }

  /** The text of each memory the second prompt carried back to the model. */
  private fun memoryTextsSentBackToTheModel(): List<String?> =
    memoriesSentBackToTheModel().map { textOfEntry(it) }

  /** Every entry the `load_memory` function response carried, across [events]. */
  private fun memoriesFrom(events: List<Event>): List<*> =
    events
      .flatMap { it.functionResponses() }
      .single { it.name == "load_memory" }
      .response
      .let { (it[BaseTool.RESULT_KEY] as Map<*, *>)["memories"] as List<*> }

  /** The text of each memory the `load_memory` function response carried. */
  private fun memoryTextsFrom(events: List<Event>): List<String?> =
    memoriesFrom(events).map { textOfEntry(it) }

  private fun textOfEntry(entry: Any?): String? {
    val content = (entry as Map<*, *>)["content"] as Map<*, *>
    return ((content["parts"] as List<*>).single() as Map<*, *>)["text"] as String?
  }

  /** The last text the agent produced, rather than whatever event happened to come last. */
  private fun finalTextOf(events: List<Event>): String =
    events.flatMap { it.content?.parts.orEmpty() }.mapNotNull { it.text }.lastOrNull().orEmpty()

  /** The system instruction the first prompt carried, which `processLlmRequest` appends to. */
  private fun instructionsSentToTheModel(): String =
    model.requests.first().config.systemInstruction?.parts.orEmpty().joinToString("") {
      it.text.orEmpty()
    }

  /**
   * Replays [responses] one per call, so a turn is deterministic.
   *
   * Declared here rather than reused from `commonTest`, which the `androidDeviceTest` source set
   * does not see.
   */
  private class ScriptedModel(private val responses: List<LlmResponse>) : Model {
    override val name = "scripted-model"

    /** Every prompt the flow built, so a test can assert what the model was actually shown. */
    val requests = mutableListOf<LlmRequest>()

    override fun generateContent(request: LlmRequest, stream: Boolean): Flow<LlmResponse> {
      requests += request
      return flowOf(
        responses.getOrElse(requests.size - 1) { error("unscripted model call #${requests.size}") }
      )
    }
  }

  private fun memoryEntry(text: String): MemoryEntry =
    MemoryEntry(content = userMessage(text), author = Role.USER)

  private companion object {
    const val TEST_DB_NAME = "adk_load_memory_e2e_instrumented_test"
    const val APPSEARCH_DB_NAME = "adk_load_memory_e2e_instrumented_memory"
    const val AGENT_NAME = "memory_agent"
    const val USER_ID = "user-1"
    const val USER_QUESTION = "What is my favourite city?"
    const val QUERY = "favourite city"
    const val ANSWER = "Your favourite city is Reykjavik."
    const val SECOND_QUESTION = "Are you sure?"
    const val SECOND_ANSWER = "Yes, Reykjavik."
    const val MEMORY_TEXT = "My favourite city is Reykjavik."
    const val SECOND_MEMORY_TEXT = "I visit my favourite city every summer."
    const val UNRELATED_TEXT = "The dog is called Bruno."
  }
}
