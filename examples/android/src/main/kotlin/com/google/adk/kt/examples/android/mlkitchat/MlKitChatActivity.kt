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
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.google.adk.kt.agents.Instruction
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.agents.RunConfig
import com.google.adk.kt.agents.StreamingMode
import com.google.adk.kt.examples.android.common.ScopedExampleActivity
import com.google.adk.kt.examples.android.common.foldTextParts
import com.google.adk.kt.examples.android.common.setExampleContentView
import com.google.adk.kt.models.mlkit.GenaiPrompt
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.sessions.InMemorySessionService
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.google.adk.kt.utils.mlkit.GenerativeModelHelpers
import kotlinx.coroutines.launch

/**
 * Minimal Android example: a multi-turn chat with an on-device ADK agent backed by ML Kit's Gemini
 * Nano ([GenaiPrompt]):
 *
 * - **Multi-turn context:** every turn reuses the same [InMemorySessionService] session (same
 *   `userId`/`sessionId`), so the agent sees the prior turns as history. Ask a question, then a
 *   follow-up that depends on it ("and how tall is it?") to see the context carried over.
 * - **Streaming vs non-streaming:** the "Stream" switch chooses the [RunConfig.streamingMode].
 *   [StreamingMode.SSE] yields incremental partial chunks (rendered here as a typewriter effect)
 *   followed by a single aggregated final response; [StreamingMode.NONE] returns just the one
 *   aggregated turn.
 *
 * The model runs fully on-device, so no API key or network is required (the first run may download
 * Gemini Nano). Contrast with the Skills example, which needs cloud Gemini because it uses tools.
 */
// Hardcoded UI strings are intentional in this minimal example; a real app would use resources.
@Suppress("SetTextI18n")
class MlKitChatActivity : ScopedExampleActivity() {

  // In-memory session: the conversation lives for as long as the process does, which is all we need
  // to demonstrate multi-turn context. (The Room-session example shows how to persist it to disk.)
  private val sessionService = InMemorySessionService()

  // Built lazily because initializing the on-device model is a suspend call.
  private var runner: InMemoryRunner? = null

  private lateinit var transcript: TextView
  private lateinit var scroll: ScrollView
  private lateinit var input: EditText
  private lateinit var sendButton: Button
  private lateinit var streamingSwitch: Switch

  // The transcript is rendered as [committed] (all finalized lines) plus an optional in-progress
  // streaming line, so a streaming reply can grow in place before being committed.
  private val committed = StringBuilder()
  private var streamingLine: String? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setExampleContentView("ML Kit chat (Gemini Nano)", buildContent())

    // Disable input until the on-device model is ready.
    setInputEnabled(false)
    scope.launch {
      appendLine("Preparing the on-device model (the first run may download Gemini Nano)...")
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
      appendLine(
        "Model ready. This is a multi-turn chat — try a question and then a follow-up. Toggle " +
          "\"Stream\" to compare streaming and non-streaming replies."
      )
      runOnUiThread { setInputEnabled(true) }
    } catch (e: Exception) {
      appendLine("On-device model unavailable on this device: ${e.message}")
    }
  }

  private fun sendToAgent(text: String) {
    val activeRunner = runner
    if (activeRunner == null) {
      appendLine("Model is not ready yet.")
      return
    }
    val streaming = streamingSwitch.isChecked
    appendLine("you: $text")
    // Lock the input for the duration of the turn so turns can't interleave in the shared session.
    setInputEnabled(false)

    scope.launch {
      try {
        val events =
          activeRunner.runAsync(
            userId = USER_ID,
            sessionId = SESSION_ID,
            newMessage = Content(role = Role.USER, parts = listOf(Part(text = text))),
            runConfig =
              RunConfig(streamingMode = if (streaming) StreamingMode.SSE else StreamingMode.NONE),
          )

        if (streaming) {
          // SSE mode: append each partial delta to the live line for a typewriter effect, then
          // replace it with the authoritative aggregated text from the final (non-partial) event.
          val partialText = StringBuilder()
          events.collect { event ->
            if (event.author != AGENT_NAME) return@collect
            val chunk = event.foldTextParts()
            if (event.partial) {
              if (chunk.isNotEmpty()) {
                partialText.append(chunk)
                updateStreamingLine("$AGENT_NAME: $partialText")
              }
            } else {
              val finalText = chunk.ifBlank { partialText.toString() }.trim()
              commitStreamingLine("$AGENT_NAME: $finalText")
            }
          }
          // Guard against a stream that never delivered a final event.
          flushStreamingLine()
        } else {
          // Non-streaming mode: a single aggregated turn, no partial chunks.
          events.collect { event ->
            if (event.author == AGENT_NAME && !event.partial) {
              val reply = event.foldTextParts().trim()
              if (reply.isNotEmpty()) appendLine("$AGENT_NAME: $reply")
            }
          }
        }
      } catch (e: Exception) {
        appendLine("Error: ${e.message ?: e::class.simpleName}")
      } finally {
        runOnUiThread { setInputEnabled(true) }
      }
    }
  }

  /** Appends a finalized line to the transcript. */
  private fun appendLine(line: String) {
    runOnUiThread {
      committed.append("$line\n\n")
      render()
    }
  }

  /** Updates the in-progress streaming line without committing it. */
  private fun updateStreamingLine(line: String) {
    runOnUiThread {
      streamingLine = line
      render()
    }
  }

  /** Commits [line] as the final version of the in-progress streaming line. */
  private fun commitStreamingLine(line: String) {
    runOnUiThread {
      streamingLine = null
      committed.append("$line\n\n")
      render()
    }
  }

  /** Commits any leftover in-progress streaming line (e.g. if no final event arrived). */
  private fun flushStreamingLine() {
    runOnUiThread {
      val pending = streamingLine ?: return@runOnUiThread
      streamingLine = null
      committed.append("$pending\n\n")
      render()
    }
  }

  /**
   * Renders the transcript and keeps it scrolled to the newest content. Must run on the UI thread.
   */
  private fun render() {
    transcript.text = committed.toString() + streamingLine.orEmpty()
    scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
  }

  private fun setInputEnabled(enabled: Boolean) {
    input.isEnabled = enabled
    sendButton.isEnabled = enabled
    streamingSwitch.isEnabled = enabled
  }

  private fun buildContent(): LinearLayout {
    transcript = TextView(this).apply { setPadding(24, 24, 24, 24) }
    scroll =
      ScrollView(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        addView(transcript)
      }
    streamingSwitch =
      Switch(this).apply {
        text = "Stream"
        isChecked = true
      }
    val switchRow =
      LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(24, 0, 24, 0)
        addView(streamingSwitch)
      }
    input =
      EditText(this).apply {
        hint = "Type a message..."
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
      }
    sendButton =
      Button(this).apply {
        text = "Send"
        setOnClickListener {
          val text = input.text.toString()
          if (text.isNotBlank()) {
            input.text.clear()
            sendToAgent(text)
          }
        }
      }
    val inputRow =
      LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(input)
        addView(sendButton)
      }
    return LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      addView(scroll)
      addView(switchRow)
      addView(inputRow)
    }
  }

  private companion object {
    const val APP_NAME = "MlKitChatExample"
    const val USER_ID = "local-user"
    const val SESSION_ID = "local-session"
    const val AGENT_NAME = "mlkit_chat_agent"
  }
}
