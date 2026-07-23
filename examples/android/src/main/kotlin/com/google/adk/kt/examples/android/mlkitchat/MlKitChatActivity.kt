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

package com.google.adk.kt.examples.android.mlkitchat

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.adk.kt.agents.Instruction
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.agents.RunConfig
import com.google.adk.kt.agents.StreamingMode
import com.google.adk.kt.examples.android.common.ScopedExampleActivity
import com.google.adk.kt.examples.android.common.foldTextParts
import com.google.adk.kt.examples.android.common.ui.AdkExamplesTheme
import com.google.adk.kt.examples.android.common.ui.ChatAuthor
import com.google.adk.kt.examples.android.common.ui.ChatMessage
import com.google.adk.kt.examples.android.common.ui.ChatScreen
import com.google.adk.kt.mlkit.GenaiPrompt
import com.google.adk.kt.mlkit.GenerativeModelHelpers
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.sessions.InMemorySessionService
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import kotlinx.coroutines.launch

/**
 * Minimal Android example: a multi-turn chat with an on-device ADK agent backed by ML Kit's Gemini
 * Nano ([GenaiPrompt]):
 * - **Multi-turn context:** every turn reuses the same [InMemorySessionService] session (same
 *   `userId`/`sessionId`), so the agent sees prior turns as history. Ask a question, then a
 *   follow-up that depends on it ("and how tall is it?") to see the context carried over.
 * - **Streaming vs non-streaming:** the "Stream" toggle chooses the [RunConfig.streamingMode].
 *   [StreamingMode.SSE] grows the reply bubble in place from partial chunks, then replaces it with
 *   the aggregated final text; [StreamingMode.NONE] appends just the one aggregated turn.
 *
 * The model runs fully on-device, so no API key or network is required (the first run may download
 * Gemini Nano). Contrast with the Skills example, which needs cloud Gemini because it uses tools.
 */
class MlKitChatActivity : ScopedExampleActivity() {

  // In-memory session: the conversation lives for as long as the process does, which is all we need
  // to demonstrate multi-turn context. (The Room-session example shows how to persist it to disk.)
  private val sessionService = InMemorySessionService()

  // Built lazily because initializing the on-device model is a suspend call.
  private var runner: InMemoryRunner? = null

  private val messages = mutableStateListOf<ChatMessage>()
  private var inputEnabled by mutableStateOf(false)
  private var streaming by mutableStateOf(true)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      AdkExamplesTheme {
        ChatScreen(
          title = "ML Kit chat (Gemini Nano)",
          messages = messages,
          inputEnabled = inputEnabled,
          onSend = ::sendToAgent,
          onBack = ::finish,
          streaming = streaming,
          onStreamingChange = { streaming = it },
        )
      }
    }

    scope.launch {
      addSystem("Preparing the on-device model (the first run may download Gemini Nano)…")
      initRunner()
    }
  }

  private suspend fun initRunner() {
    try {
      val agent =
        LlmAgent(
          name = AGENT_NAME,
          model =
            GenaiPrompt.create(GenerativeModelHelpers.initGenerativeModel(), name = "gemini-nano"),
          instruction = Instruction("You are a helpful assistant. Keep replies concise."),
        )
      runner = InMemoryRunner(agent = agent, appName = APP_NAME, sessionService = sessionService)
      addSystem(
        "Model ready. This is a multi-turn chat — try a question and then a follow-up. Toggle " +
          "\"Stream\" to compare streaming and non-streaming replies."
      )
      runOnUiThread { inputEnabled = true }
    } catch (e: Exception) {
      addSystem("On-device model unavailable on this device: ${e.message}")
    }
  }

  private fun sendToAgent(text: String) {
    val activeRunner = runner ?: return
    val useStreaming = streaming
    add(ChatAuthor.USER, text)
    // Lock the input for the duration of the turn so turns can't interleave in the shared session.
    runOnUiThread { inputEnabled = false }

    scope.launch {
      try {
        val events =
          activeRunner.runAsync(
            userId = USER_ID,
            sessionId = SESSION_ID,
            newMessage = Content(role = Role.USER, parts = listOf(Part(text = text))),
            runConfig =
              RunConfig(streamingMode = if (useStreaming) StreamingMode.SSE else StreamingMode.NONE),
          )

        if (useStreaming) {
          // SSE mode: grow one reply bubble from partial deltas, then replace it with the
          // authoritative aggregated text from the final (non-partial) event.
          val partial = StringBuilder()
          var index = -1
          events.collect { event ->
            if (event.author != AGENT_NAME) return@collect
            val chunk = event.foldTextParts()
            val isPartial = event.partial
            runOnUiThread {
              if (isPartial) {
                if (chunk.isNotEmpty()) {
                  partial.append(chunk)
                  index = upsertAgentBubble(index, partial.toString())
                }
              } else {
                val finalText = chunk.ifBlank { partial.toString() }.trim()
                if (finalText.isNotEmpty()) index = upsertAgentBubble(index, finalText)
              }
            }
          }
        } else {
          // Non-streaming mode: a single aggregated turn, no partial chunks.
          events.collect { event ->
            if (event.author == AGENT_NAME && !event.partial) {
              val reply = event.foldTextParts().trim()
              if (reply.isNotEmpty()) add(ChatAuthor.AGENT, reply)
            }
          }
        }
      } catch (e: Exception) {
        addSystem("Error: ${e.message ?: e::class.simpleName}")
      } finally {
        runOnUiThread { inputEnabled = true }
      }
    }
  }

  /**
   * Adds the streaming reply bubble on first use, or updates it in place afterwards. Returns the
   * bubble's index. Must run on the UI thread.
   */
  private fun upsertAgentBubble(index: Int, text: String): Int {
    if (index < 0) {
      messages.add(ChatMessage(ChatAuthor.AGENT, text, AGENT_NAME))
      return messages.lastIndex
    }
    messages[index] = messages[index].copy(text = text)
    return index
  }

  private fun add(author: ChatAuthor, text: String) {
    val label = if (author == ChatAuthor.AGENT) AGENT_NAME else ""
    runOnUiThread { messages.add(ChatMessage(author, text, label)) }
  }

  private fun addSystem(text: String) {
    runOnUiThread { messages.add(ChatMessage(ChatAuthor.SYSTEM, text)) }
  }

  private companion object {
    const val APP_NAME = "MlKitChatExample"
    const val USER_ID = "local-user"
    const val SESSION_ID = "local-session"
    const val AGENT_NAME = "mlkit_chat_agent"
  }
}
