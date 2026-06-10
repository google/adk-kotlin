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
import com.google.adk.kt.logging.LoggerFactory
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.runners.Runner
import com.google.adk.kt.types.Content
import com.google.common.truth.Truth.assertThat
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.ai.FirebaseAI
import kotlin.test.Test
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assume
import org.junit.Before
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FirebaseIntegrationTest {
  private lateinit var firebaseApp: FirebaseApp
  private lateinit var firebaseModel: Firebase
  private lateinit var agent: LlmAgent
  private lateinit var runner: Runner

  companion object {
    private val log = LoggerFactory.getLogger(FirebaseIntegrationTest::class)

    private const val USER_QUESTION = "What can you tell me about the planet Earth?"

    private object EnvVars {
      const val FIREBASE_API_KEY = "FIREBASE_API_KEY"
      const val FIREBASE_APP_ID = "FIREBASE_APP_ID"
      const val FIREBASE_PROJECT_ID = "FIREBASE_PROJECT_ID"

      const val FIREBASE_MODEL_NAME = "FIREBASE_MODEL_NAME"
    }
  }

  fun initFirebaseApp() {
    if (this::firebaseApp.isInitialized) {
      return
    }

    val apiKey = System.getenv(EnvVars.FIREBASE_API_KEY)
    val appId = System.getenv(EnvVars.FIREBASE_APP_ID)
    val projectId = System.getenv(EnvVars.FIREBASE_PROJECT_ID)

    if (apiKey != null && appId != null && projectId != null) {
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
        )
    }
  }

  @Before
  fun setUp() {
    initFirebaseApp()
    Assume.assumeTrue("unable to initialize firebase app", this::firebaseApp.isInitialized)
    val modelName = System.getenv(EnvVars.FIREBASE_MODEL_NAME) ?: "gemini-3.5-flash"
    firebaseModel = Firebase.create(modelName, FirebaseAI.getInstance(firebaseApp))
    agent =
      LlmAgent(
        name = "testAgent",
        model = firebaseModel,
        instruction =
          Instruction(
            text =
              "You are a helpful assistant. Answer the user's question in one or two sentences."
          ),
      )
    runner = InMemoryRunner(agent, appName = "integration tests")
  }

  @Test
  fun runAsync_userAsksAboutEarth_agentResponseMentionsEarth() = runTest {
    log.info { "Sending user question: $USER_QUESTION" }

    val events =
      runner
        .runAsync(
          "test_user",
          "test_session",
          newMessage = Content.fromText(role = "user", text = USER_QUESTION),
        )
        .toList()

    log.info { "Received ${events.size} event(s) from the runner" }
    assertThat(events).isNotEmpty()

    events.forEachIndexed { index, event ->
      val eventText = event.content?.parts?.mapNotNull { p -> p.text }?.joinToString().orEmpty()
      log.info {
        "Event[$index]: author=${event.author}, finishReason=${event.finishReason}, " +
          "partial=${event.partial}, errorCode=${event.errorCode}, " +
          "errorMessage=${event.errorMessage}, text=$eventText"
      }
    }

    // Verify the model did not surface any error events during the turn.
    val modelErrors =
      events
        .filter { it.errorCode != null || it.errorMessage != null }
        .map { "author=${it.author}, code=${it.errorCode}, message=${it.errorMessage}" }
    assertThat(modelErrors).isEmpty()

    val text =
      events
        .flatMap { e -> e.content?.parts?.mapNotNull { p -> p.text } ?: emptyList() }
        .joinToString()
    log.info { "Aggregated response text: $text" }

    assertThat(text).isNotEmpty()
    assertThat(text.lowercase()).contains("earth")
  }
}
