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

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.adk.firebase.models.Firebase
import com.google.adk.kt.agents.Instruction
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.events.Event
import com.google.adk.kt.logging.LoggerFactory
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.tools.FunctionTool
import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FunctionDeclaration
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type
import com.google.common.truth.Truth.assertThat
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.ai.FirebaseAI
import kotlin.runCatching
import kotlin.test.Test
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assume
import org.junit.Before
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FirebaseIntegrationTest {
  private lateinit var firebaseApp: FirebaseApp
  private lateinit var firebaseModel: Firebase

  companion object {
    private val log = LoggerFactory.getLogger(FirebaseIntegrationTest::class)

    /**
     * A dedicated, non-default FirebaseApp name for this test class so it cannot clash with the
     * default app or apps created by other test classes sharing the same JVM.
     */
    private const val FIREBASE_APP_NAME = "adk-firebase-integration-test"

    private object EnvVars {
      const val FIREBASE_API_KEY = "FIREBASE_API_KEY"
      const val FIREBASE_APP_ID = "FIREBASE_APP_ID"
      const val FIREBASE_PROJECT_ID = "FIREBASE_PROJECT_ID"

      const val FIREBASE_DISABLE_IT = "FIREBASE_DISABLE_IT"

      const val FIREBASE_MODEL_NAME = "FIREBASE_MODEL_NAME"
    }

    /**
     * Logs the number of [events] and a per-event summary, including any function calls/responses.
     */
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

    /**
     * Deletes the [FirebaseApp] this class created so it does not leak into other test classes
     * running in the same JVM, which would otherwise fail with firebase app already exists error.
     *
     * Unlike init (see [initFirebaseApp]), this is safe in @AfterClass: it only touches the global
     * FirebaseApp registry and never needs the Android context, so it works outside the per-method
     * Robolectric sandbox.
     */
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

  fun initFirebaseApp() {
    // This runs from @Before (once per test method) rather than @BeforeClass (once per class)
    // because initialization depends on ApplicationProvider.getApplicationContext(). Under
    // Robolectric (AndroidJUnit4 delegates to it for local unit tests), the Android environment
    // that backs that context is created per test method, inside the Robolectric sandbox.
    // @BeforeClass runs outside/before any per-method sandbox exists, so getApplicationContext()
    // has no context to return and touching Robolectric-backed APIs there corrupts the runner's
    // lifecycle (it fails the test with "TestEnvironment.resetState() ... getTestEnvironment() is
    // null"). So we initialize per method and dedupe via the global FirebaseApp registry below.
    // Teardown can still live in @AfterClass: it only calls FirebaseApp.getInstance()/delete(),
    // which don't need the Android context.
    //
    // JUnit creates a fresh test instance per method, so the named FirebaseApp created by an
    // earlier test in this JVM outlives `firebaseApp`. Reuse it instead of re-initializing, which
    // would throw firebase app already exists error.
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
  }

  @Test
  fun runAsync_userAsksAboutEarth_agentResponseMentionsEarth(): Unit = runBlocking {
    val agent =
      LlmAgent(
        name = "testAgent",
        model = firebaseModel,
        instruction =
          Instruction(
            text =
              "You are a helpful assistant. Answer the user's question in one or two sentences."
          ),
      )
    val runner = InMemoryRunner(agent, appName = "integration tests")

    val question = "What can you tell me about the planet Earth?"
    log.info { "Sending user question: $question" }

    val events =
      runner
        .runAsync(
          "test_user",
          "test_session",
          newMessage = Content.fromText(role = "user", text = question),
        )
        .toList()

    logEvents(events)
    assertThat(events).isNotEmpty()

    // Verify the model did not surface any error events during the turn.
    assertThat(modelErrors(events)).isEmpty()

    val text = aggregateText(events)
    log.info { "Aggregated response text: $text" }

    assertThat(text).isNotEmpty()
    assertThat(text.lowercase()).contains("earth")
  }

  @Test
  fun runAsync_userAsksForWeather_agentInvokesFunctionTool(): Unit = runBlocking {
    val temperatureToolName = "get_current_temperature"
    // A deterministic temperature the model cannot know on its own, forcing it to rely on the tool.
    val magicTemperatureCelsius = 42
    var invocationCount = 0
    var capturedLocation: String? = null

    val getCurrentTemperatureTool =
      object :
        FunctionTool(
          name = temperatureToolName,
          description = "Returns the current temperature in Celsius for a given location.",
        ) {
        override fun declaration(): FunctionDeclaration =
          FunctionDeclaration(
            name = name,
            description = description,
            parameters =
              Schema(
                type = Type.OBJECT,
                properties =
                  mapOf(
                    "location" to
                      Schema(
                        type = Type.STRING,
                        description = "The city to look up, e.g. 'Mountain View'.",
                      )
                  ),
                required = listOf("location"),
              ),
          )

        override suspend fun execute(context: ToolContext, args: Map<String, Any>): Any {
          invocationCount++
          capturedLocation = args["location"] as? String
          log.info { "Tool invoked with location=$capturedLocation" }
          return mapOf("temperature_celsius" to magicTemperatureCelsius)
        }
      }

    val weatherAgent =
      LlmAgent(
        name = "weatherAgent",
        model = firebaseModel,
        instruction =
          Instruction(
            text =
              "You are a helpful assistant. Use the available tools to answer the user's question " +
                "and state the exact temperature value returned by the tool."
          ),
        tools = listOf(getCurrentTemperatureTool),
      )
    val weatherRunner = InMemoryRunner(weatherAgent, appName = "integration tests")

    val question = "What is the current temperature in Mountain View? Use the available tool."
    log.info { "Sending user question: $question" }

    val events =
      weatherRunner
        .runAsync(
          "test_user",
          "test_session",
          newMessage = Content.fromText(role = "user", text = question),
        )
        .toList()

    logEvents(events)
    assertThat(events).isNotEmpty()

    // Verify the model did not surface any error events during the turn.
    assertThat(modelErrors(events)).isEmpty()

    // The model should have actually invoked our tool, extracting the location argument.
    assertThat(invocationCount).isAtLeast(1)
    assertThat(capturedLocation?.lowercase()).contains("mountain view")

    // The event stream should contain a function call and a matching function response.
    val functionCallNames = events.flatMap { it.functionCalls() }.map { it.name }
    assertThat(functionCallNames).contains(temperatureToolName)
    val functionResponseNames = events.flatMap { it.functionResponses() }.map { it.name }
    assertThat(functionResponseNames).contains(temperatureToolName)

    // The final answer should reflect the value returned by the tool.
    val text = aggregateText(events)
    log.info { "Aggregated response text: $text" }

    assertThat(text).isNotEmpty()
    assertThat(text).contains(magicTemperatureCelsius.toString())
  }
}
