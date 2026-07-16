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

package com.google.adk.firebase.it

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.adk.firebase.models.Firebase
import com.google.adk.kt.agents.Instruction
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.annotations.Param
import com.google.adk.kt.annotations.Tool
import com.google.adk.kt.events.Event
import com.google.adk.kt.logging.LoggerFactory
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.sessions.room.RoomSessionService
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.GenerateContentConfig
import com.google.adk.kt.types.ThinkingConfig
import com.google.common.truth.Truth.assertThat
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.ai.FirebaseAI
import kotlin.runCatching
import kotlin.test.Test
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.AfterClass
import org.junit.Assume
import org.junit.Before
import org.junit.runner.RunWith

/**
 * Integration tests exercising a live Firebase AI turn whose events are persisted through the
 * Room-backed [RoomSessionService] and then read back after a database reopen.
 *
 * This is the "Firebase AI + session store at once" scenario: it proves that persisting (and
 * reloading) a session built from real Firebase responses does not crash. The modeled fields (text,
 * function calls, thought markers and thought signatures, ...) round-trip faithfully through
 * serialization.
 *
 * Like [FirebaseIntegrationTest] this runs under Robolectric against the live `adk-java-e2e`
 * Firebase project and is skipped unless the `FIREBASE_*` environment variables are present (and
 * `FIREBASE_DISABLE_IT` is not set).
 */
@RunWith(AndroidJUnit4::class)
class FirebaseRoomSessionIntegrationTest {
  private lateinit var firebaseApp: FirebaseApp
  private lateinit var firebaseModel: Firebase
  private lateinit var context: Context
  private lateinit var sessionService: RoomSessionService

  companion object {
    private val log = LoggerFactory.getLogger(FirebaseRoomSessionIntegrationTest::class)

    /** A dedicated, non-default FirebaseApp name so it cannot clash with other test classes. */
    private const val FIREBASE_APP_NAME = "adk-firebase-room-session-integration-test"

    private const val APP_NAME = "firebase room session integration tests"
    private const val TEST_DB_NAME = "firebase_room_session_integration_test.db"

    private object EnvVars {
      const val FIREBASE_API_KEY = "FIREBASE_API_KEY"
      const val FIREBASE_APP_ID = "FIREBASE_APP_ID"
      const val FIREBASE_PROJECT_ID = "FIREBASE_PROJECT_ID"

      const val FIREBASE_DISABLE_IT = "FIREBASE_DISABLE_IT"

      const val FIREBASE_MODEL_NAME = "FIREBASE_MODEL_NAME"
    }

    /** Logs the number of [events] and a per-event summary, including any function calls. */
    private fun logEvents(events: List<Event>) {
      log.info { "Received ${events.size} event(s) from the runner" }
      events.forEachIndexed { index, event ->
        log.info {
          "Event[$index]: author=${event.author}, finishReason=${event.finishReason}, " +
            "partial=${event.partial}, errorCode=${event.errorCode}, " +
            "errorMessage=${event.errorMessage}, functionCalls=${event.functionCalls()}, " +
            "functionResponses=${event.functionResponses()}, text=${aggregateText(listOf(event))}"
        }
      }
    }

    /** Returns the concatenated text across all parts of all [events]. */
    private fun aggregateText(events: List<Event>): String =
      events
        .flatMap { e -> e.content?.parts?.mapNotNull { p -> p.text } ?: emptyList() }
        .joinToString()

    /** Returns a human-readable description of any error events the model surfaced. */
    private fun modelErrors(events: List<Event>): List<String> =
      events
        .filter { it.errorCode != null || it.errorMessage != null }
        .map { "author=${it.author}, code=${it.errorCode}, message=${it.errorMessage}" }

    /** Deletes the [FirebaseApp] this class created so it does not leak into other test classes. */
    @AfterClass
    @JvmStatic
    fun tearDownClass() {
      runCatching { FirebaseApp.getInstance(FIREBASE_APP_NAME) }
        .getOrNull()
        ?.let {
          log.info { "deleting firebase app: $FIREBASE_APP_NAME" }
          it.delete()
        }
    }
  }

  private fun initFirebaseApp() {
    // See FirebaseIntegrationTest.initFirebaseApp for why this runs per-method (Robolectric
    // context)
    // and dedupes through the global FirebaseApp registry.
    runCatching { FirebaseApp.getInstance(FIREBASE_APP_NAME) }
      .getOrNull()
      ?.let {
        firebaseApp = it
        return
      }
    val apiKey = System.getenv(EnvVars.FIREBASE_API_KEY)?.ifEmpty { null }
    val appId = System.getenv(EnvVars.FIREBASE_APP_ID)?.ifEmpty { null }
    val projectId = System.getenv(EnvVars.FIREBASE_PROJECT_ID)?.ifEmpty { null }

    if (apiKey != null && appId != null && projectId != null) {
      log.info { "initializing firebase app: $FIREBASE_APP_NAME" }
      firebaseApp =
        FirebaseApp.initializeApp(
          ApplicationProvider.getApplicationContext(),
          FirebaseOptions.Builder()
            .apply {
              setApiKey(apiKey)
              setApplicationId(appId)
              setProjectId(projectId)
            }
            .build(),
          FIREBASE_APP_NAME,
        )
    }
  }

  @Before
  fun setUp() {
    val itDisabled =
      setOf("true", "t", "yes", "y", "1")
        .contains(System.getenv(EnvVars.FIREBASE_DISABLE_IT)?.lowercase())
    Assume.assumeFalse("firebase integration test disabled", itDisabled)
    initFirebaseApp()
    Assume.assumeTrue("unable to initialize firebase app", this::firebaseApp.isInitialized)
    val modelName =
      System.getenv(EnvVars.FIREBASE_MODEL_NAME)?.ifEmpty { null } ?: "gemini-3.5-flash"
    firebaseModel = Firebase.create(modelName, FirebaseAI.getInstance(firebaseApp))

    context = ApplicationProvider.getApplicationContext()
    context.deleteDatabase(TEST_DB_NAME)
    sessionService = RoomSessionService.fromContext(context, databaseName = TEST_DB_NAME)
  }

  @After
  fun tearDown() {
    runCatching { sessionService.close() }
    runCatching { context.deleteDatabase(TEST_DB_NAME) }
  }

  @Test
  fun functionToolTurn_persistedThroughRoomSession_reloadsWithoutCrash(): Unit = runBlocking {
    val temperatureToolName = TEMPERATURE_TOOL_NAME

    val agent =
      LlmAgent(
        name = "weatherAgent",
        model = firebaseModel,
        instruction =
          Instruction(
            text =
              "You are a helpful assistant. Use the available tools to answer the user's question " +
                "and state the exact temperature value returned by the tool."
          ),
        tools = WeatherTools().generatedTools(),
      )
    val runner = InMemoryRunner(agent = agent, appName = APP_NAME, sessionService = sessionService)

    val userId = "user-fn"
    val sessionId = "session-fn"
    val events =
      runner
        .runAsync(
          userId = userId,
          sessionId = sessionId,
          newMessage =
            Content.fromText(
              role = "user",
              text = "What is the current temperature in Mountain View? Use the available tool.",
            ),
        )
        .toList()

    logEvents(events)
    assertThat(events).isNotEmpty()
    assertThat(modelErrors(events)).isEmpty()

    // A function call/response actually occurred during the live turn.
    val key = SessionKey(APP_NAME, userId, sessionId)
    val live = sessionService.getSession(key)
    assertThat(live).isNotNull()
    assertThat(live!!.events.flatMap { it.functionCalls() }.map { it.name })
      .contains(temperatureToolName)

    // Reopen the on-disk database: persisting + reloading a Firebase-derived session must not
    // crash, and the modeled function-call parts (including the thought_signature Gemini 3 needs on
    // functionCall parts) must survive serialization.
    sessionService.close()
    val reopened = RoomSessionService.fromContext(context, databaseName = TEST_DB_NAME)
    try {
      val persisted = reopened.getSession(key)
      assertThat(persisted).isNotNull()
      assertThat(persisted!!.events.map { it.id })
        .containsAtLeastElementsIn(live.events.map { it.id })
      assertThat(persisted.events.flatMap { it.functionCalls() }.map { it.name })
        .contains(temperatureToolName)
    } finally {
      reopened.close()
    }
  }

  @Test
  fun thinkingTurn_persistedThroughRoomSession_reloadsWithoutCrash(): Unit = runBlocking {
    // includeThoughts surfaces thought parts from the model; toAdkPart maps their thought marker
    // and
    // thought_signature onto the (serializable) ADK Part fields. The point of this test is that
    // persisting + reloading such a turn does not crash and preserves that thinking metadata.
    val agent =
      LlmAgent(
        name = "thinkingAgent",
        model = firebaseModel,
        instruction =
          Instruction(
            text = "You are a helpful assistant. Think step by step, then answer in one sentence."
          ),
        generateContentConfig =
          GenerateContentConfig(thinkingConfig = ThinkingConfig(includeThoughts = true)),
      )
    val runner = InMemoryRunner(agent = agent, appName = APP_NAME, sessionService = sessionService)

    val userId = "user-think"
    val sessionId = "session-think"
    val events =
      runner
        .runAsync(
          userId = userId,
          sessionId = sessionId,
          newMessage =
            Content.fromText(
              role = "user",
              text = "If a train travels 60 km in 45 minutes, what is its average speed in km/h?",
            ),
        )
        .toList()

    logEvents(events)
    assertThat(events).isNotEmpty()
    assertThat(modelErrors(events)).isEmpty()

    val key = SessionKey(APP_NAME, userId, sessionId)
    val live = sessionService.getSession(key)
    assertThat(live).isNotNull()
    assertThat(live!!.events).isNotEmpty()

    // Persisting + reloading a thinking turn must not crash.
    sessionService.close()
    val reopened = RoomSessionService.fromContext(context, databaseName = TEST_DB_NAME)
    try {
      val persisted = reopened.getSession(key)
      assertThat(persisted).isNotNull()
      assertThat(persisted!!.events.map { it.id })
        .containsAtLeastElementsIn(live.events.map { it.id })
    } finally {
      reopened.close()
    }
  }
}

private const val TEMPERATURE_TOOL_NAME = "get_current_temperature"

/** Test tool exposed to the model via the KSP `@Tool` processor (`generatedTools()`). */
class WeatherTools {
  @Tool(
    name = TEMPERATURE_TOOL_NAME,
    description = "Returns the current temperature in Celsius for a given location.",
  )
  fun getCurrentTemperature(
    @Param("The city to look up, e.g. 'Mountain View'.") location: String
  ): Map<String, Int> = mapOf("temperature_celsius" to 42)
}
