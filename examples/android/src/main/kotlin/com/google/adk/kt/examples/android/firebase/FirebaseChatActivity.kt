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

package com.google.adk.kt.examples.android.firebase

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.adk.kt.examples.android.common.ScopedExampleActivity
import com.google.adk.kt.examples.android.common.foldTextParts
import com.google.adk.kt.examples.android.common.ui.AdkExamplesTheme
import com.google.adk.kt.examples.android.common.ui.ChatAuthor
import com.google.adk.kt.examples.android.common.ui.ChatMessage
import com.google.adk.kt.examples.android.common.ui.ChatScreen
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.sessions.InMemorySessionService
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import kotlinx.coroutines.launch

/**
 * Minimal Android example: an [com.google.adk.kt.agents.LlmAgent] backed by the Firebase AI
 * (Gemini) model from the `:google-adk-kotlin-firebase` module. The chat UI exercises both plain
 * conversation and tool calling (via [WeatherTools]).
 *
 * The Firebase-setup plumbing lives in [FirebaseAppResolver]; the agent wiring lives in
 * [FirebaseChatAgent]. What remains here is the typical ADK usage: build an [InMemoryRunner] around
 * an agent and drive it with [InMemoryRunner.runAsync].
 *
 * Unlike the on-device examples, this one talks to the cloud Firebase AI (Gemini) backend, so it
 * needs a Firebase configuration and network access; see the app README.md for setup.
 */
class FirebaseChatActivity : ScopedExampleActivity() {

  private val sessionService = InMemorySessionService()
  private var runner: InMemoryRunner? = null

  private val messages = mutableStateListOf<ChatMessage>()
  private var inputEnabled by mutableStateOf(false)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      AdkExamplesTheme {
        ChatScreen(
          title = "Firebase AI chat",
          messages = messages,
          inputEnabled = inputEnabled,
          onSend = ::sendToAgent,
          onBack = ::finish,
        )
      }
    }

    val firebaseApp = FirebaseAppResolver.resolve(applicationContext)
    if (firebaseApp == null) {
      messages.add(ChatMessage(ChatAuthor.SYSTEM, FirebaseAppResolver.NO_CONFIG_MESSAGE))
      return
    }

    runner =
      try {
        InMemoryRunner(
          agent = FirebaseChatAgent.create(firebaseApp),
          appName = APP_NAME,
          sessionService = sessionService,
        )
      } catch (e: Throwable) {
        messages.add(
          ChatMessage(
            ChatAuthor.SYSTEM,
            "Failed to build agent: ${e.message ?: e::class.simpleName}",
          )
        )
        return
      }

    messages.add(
      ChatMessage(
        ChatAuthor.SYSTEM,
        "Ready. Try: \"Tell me about the planet Earth\" or " +
          "\"What is the current temperature in Mountain View?\"",
      )
    )
    inputEnabled = true
  }

  private fun sendToAgent(text: String) {
    val activeRunner = runner ?: return
    messages.add(ChatMessage(ChatAuthor.USER, text))
    scope.launch {
      try {
        activeRunner
          .runAsync(
            userId = USER_ID,
            sessionId = SESSION_ID,
            newMessage = Content(role = Role.USER, parts = listOf(Part(text = text))),
          )
          .collect { event ->
            val reply = event.foldTextParts()
            if (event.author == FirebaseChatAgent.NAME && reply.isNotBlank()) {
              runOnUiThread {
                messages.add(ChatMessage(ChatAuthor.AGENT, reply, FirebaseChatAgent.NAME))
              }
            }
          }
      } catch (e: Exception) {
        runOnUiThread {
          messages.add(ChatMessage(ChatAuthor.SYSTEM, "Error: ${e.message ?: e::class.simpleName}"))
        }
      }
    }
  }

  private companion object {
    const val APP_NAME = "FirebaseChatExample"
    const val USER_ID = "local-user"
    const val SESSION_ID = "local-session"
  }
}
