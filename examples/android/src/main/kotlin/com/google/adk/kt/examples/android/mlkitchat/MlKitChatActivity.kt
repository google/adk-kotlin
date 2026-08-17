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
import com.google.adk.kt.examples.android.common.foldThoughtParts
import com.google.adk.kt.examples.android.common.ui.AdkExamplesTheme
import com.google.adk.kt.examples.android.common.ui.ChatAuthor
import com.google.adk.kt.examples.android.common.ui.ChatMessage
import com.google.adk.kt.examples.android.common.ui.ChatScreen
import com.google.adk.kt.mlkit.GenaiPrompt
import com.google.adk.kt.mlkit.GenerativeModelHelpers
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.sessions.InMemorySessionService
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.GenerateContentConfig
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.google.adk.kt.types.ThinkingConfig
import com.google.mlkit.genai.prompt.GenerativeModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
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
 * - **Thinking:** the "Show the model's thinking" toggle rebuilds the agent with a
 *   [ThinkingConfig], so the model reasons before answering and returns that reasoning as parts
 *   marked as thoughts, shown here in their own bubble. Switching it off drops the config again, so
 *   the model stops reasoning too. It needs a Gemini Nano that supports thinking mode.
 *
 * The model runs fully on-device, so no API key or network is required (the first run may download
 * Gemini Nano). Contrast with the Skills example, which needs cloud Gemini because it uses tools.
 */
class MlKitChatActivity : ScopedExampleActivity() {

  // In-memory session: the conversation lives for as long as the process does, which is all we need
  // to demonstrate multi-turn context. (The Room-session example shows how to persist it to disk.)
  private val sessionService = InMemorySessionService()

  // Built once and reused: creating the ML Kit client is expensive, and thinking is a per-request
  // field, so changing it does not need a new client.
  private var generativeModel: GenerativeModel? = null
  private var runner: InMemoryRunner? = null

  private val messages = mutableStateListOf<ChatMessage>()
  private var modelReady by mutableStateOf(false)
  private var busy by mutableStateOf(false)
  private var streaming by mutableStateOf(true)
  private var thinking by mutableStateOf(false)

  /** Input and the thinking switch are live only once the model is ready and no turn is running. */
  private val idle: Boolean
    get() = modelReady && !busy

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      AdkExamplesTheme {
        ChatScreen(
          title = "ML Kit chat (Gemini Nano)",
          messages = messages,
          inputEnabled = idle,
          onSend = ::sendToAgent,
          onBack = ::finish,
          streaming = streaming,
          onStreamingChange = { streaming = it },
          thinking = thinking,
          onThinkingChange = ::rebuildRunner,
          thinkingEnabled = idle,
        )
      }
    }

    addSystem("Preparing the on-device model (the first run may download Gemini Nano)…")
    rebuildRunner(thinking)
  }

  override fun onDestroy() {
    // Release only once the scope has finished: cancelling it does not wait, so a turn may still
    // be inside ML Kit on another thread.
    val closing = runner
    val closingModel = generativeModel
    scope.coroutineContext.job.invokeOnCompletion {
      closing?.close()
      closingModel?.close()
    }
    scope.cancel()
    super.onDestroy()
  }

  /**
   * Replaces the runner with one whose agent asks for [includeThoughts]. Thinking is fixed on the
   * agent's request config when the agent is built, so changing it needs a new agent. The session
   * is kept, so the conversation survives the switch.
   */
  private fun rebuildRunner(includeThoughts: Boolean) {
    busy = true
    scope.launch {
      try {
        val model =
          generativeModel
            ?: GenerativeModelHelpers.initGenerativeModel().also { generativeModel = it }
        val agent =
          LlmAgent(
            name = AGENT_NAME,
            model = GenaiPrompt.create(model, name = "gemini-nano"),
            instruction = Instruction("You are a helpful assistant. Keep replies concise."),
            generateContentConfig =
              GenerateContentConfig(
                // A null config leaves thinking off; asking for thoughts turns it on.
                thinkingConfig =
                  if (includeThoughts) ThinkingConfig(includeThoughts = true) else null
              ),
          )
        val previous = runner
        runner = InMemoryRunner(agent = agent, appName = APP_NAME, sessionService = sessionService)
        previous?.close()
        if (previous == null) {
          addSystem(
            "Model ready. This is a multi-turn chat — try a question and then a follow-up. " +
              "Toggle \"Stream\" to compare streaming and non-streaming replies, and \"Show " +
              "the model's thinking\" to make it reason first."
          )
        }
        // Only now does the switch move: on a failed rebuild it stays on the agent still in use.
        runOnUiThread {
          thinking = includeThoughts
          modelReady = true
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        // Once the model exists the device is fine, so only the agent rebuild can have failed.
        val what =
          if (generativeModel == null) "On-device model unavailable on this device"
          else "Could not switch thinking mode"
        addSystem("$what: ${e.message}")
      } finally {
        runOnUiThread { busy = false }
      }
    }
  }

  private fun sendToAgent(text: String) {
    val activeRunner = runner ?: return
    val useStreaming = streaming
    add(ChatAuthor.USER, text)
    // Lock the input for the duration of the turn so turns can't interleave in the shared session.
    // The same flag gates the thinking switch, so a rebuild cannot land mid-turn either.
    runOnUiThread { busy = true }

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
          // SSE mode: grow the thought and reply bubbles from partial deltas, then replace them
          // with the authoritative aggregated text from the final (non-partial) event.
          val partialThought = StringBuilder()
          val partialReply = StringBuilder()
          var thoughtIndex = -1
          var replyIndex = -1
          events.collect { event ->
            if (event.author != AGENT_NAME) return@collect
            val thoughtChunk = event.foldThoughtParts()
            val replyChunk = event.foldTextParts()
            val isPartial = event.partial
            runOnUiThread {
              if (isPartial) {
                if (thoughtChunk.isNotEmpty()) {
                  partialThought.append(thoughtChunk)
                  thoughtIndex =
                    upsertBubble(thoughtIndex, ChatAuthor.THOUGHT, partialThought.toString())
                }
                if (replyChunk.isNotEmpty()) {
                  partialReply.append(replyChunk)
                  replyIndex = upsertBubble(replyIndex, ChatAuthor.AGENT, partialReply.toString())
                }
              } else {
                val finalThought = thoughtChunk.ifBlank { partialThought.toString() }.trim()
                if (finalThought.isNotEmpty()) {
                  thoughtIndex = upsertBubble(thoughtIndex, ChatAuthor.THOUGHT, finalThought)
                }
                val finalText = replyChunk.ifBlank { partialReply.toString() }.trim()
                if (finalText.isNotEmpty()) {
                  replyIndex = upsertBubble(replyIndex, ChatAuthor.AGENT, finalText)
                }
              }
            }
          }
        } else {
          // Non-streaming mode: a single aggregated turn, no partial chunks.
          events.collect { event ->
            if (event.author == AGENT_NAME && !event.partial) {
              val thought = event.foldThoughtParts().trim()
              if (thought.isNotEmpty()) add(ChatAuthor.THOUGHT, thought)
              val reply = event.foldTextParts().trim()
              if (reply.isNotEmpty()) add(ChatAuthor.AGENT, reply)
            }
          }
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        addSystem("Error: ${e.message ?: e::class.simpleName}")
      } finally {
        runOnUiThread { busy = false }
      }
    }
  }

  /**
   * Adds a streaming bubble on first use, or updates it in place afterwards. Returns the bubble's
   * index. Must run on the UI thread.
   */
  private fun upsertBubble(index: Int, author: ChatAuthor, text: String): Int {
    if (index < 0) {
      messages.add(ChatMessage(author, text, labelFor(author)))
      return messages.lastIndex
    }
    messages[index] = messages[index].copy(text = text)
    return index
  }

  private fun add(author: ChatAuthor, text: String) {
    runOnUiThread { messages.add(ChatMessage(author, text, labelFor(author))) }
  }

  private fun labelFor(author: ChatAuthor): String =
    when (author) {
      ChatAuthor.AGENT -> AGENT_NAME
      ChatAuthor.THOUGHT -> "thinking"
      else -> ""
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
