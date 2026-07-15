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

package com.google.adk.kt.examples.android.roomsession

import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.adk.kt.agents.Instruction
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.examples.android.common.ScopedExampleActivity
import com.google.adk.kt.examples.android.common.foldTextParts
import com.google.adk.kt.examples.android.common.setExampleContentView
import com.google.adk.kt.models.mlkit.GenaiPrompt
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.sessions.room.RoomSessionService
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.google.adk.kt.utils.mlkit.GenerativeModelHelpers
import kotlinx.coroutines.launch

/**
 * Minimal Android example: an on-device ADK agent whose conversation persists across app restarts
 * via the Room-backed [RoomSessionService].
 *
 * The wiring mirrors a normal ADK Android app but swaps the default in-memory session service for
 * [RoomSessionService.fromContext], which stores sessions and events in a local SQLite database.
 * The agent runs fully on-device through ML Kit's Gemini Nano ([GenaiPrompt]), so no API key or
 * network is required. Because the session lives on disk, relaunching the app reloads the prior
 * conversation via [RoomSessionService.getSession].
 */
// Hardcoded UI strings are intentional in this minimal example; a real app would use resources.
@Suppress("SetTextI18n")
class RoomSessionActivity : ScopedExampleActivity() {

  // The Room-backed, on-device persistent session service (one SQLite database on disk).
  private val sessionService by lazy { RoomSessionService.fromContext(applicationContext) }
  private val sessionKey = SessionKey(APP_NAME, USER_ID, SESSION_ID)

  // Built lazily because initializing the on-device model is a suspend call.
  private var runner: InMemoryRunner? = null

  private lateinit var transcript: TextView
  private lateinit var input: EditText

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setExampleContentView("Room session", buildContent())

    scope.launch {
      // Reload any conversation persisted by a previous run to demonstrate durability.
      val existing = sessionService.getSession(sessionKey)
      if (existing != null && existing.events.isNotEmpty()) {
        appendToTranscript(
          "Reloaded ${existing.events.size} persisted event(s) from a previous run:"
        )
        for (event in existing.events) {
          event.content
            ?.parts
            ?.mapNotNull { it.text }
            ?.forEach { appendToTranscript("${event.author}: $it") }
        }
      } else {
        val unused = sessionService.createSession(sessionKey)
        appendToTranscript("New on-device session created. Messages persist across app restarts.")
      }
      initRunner()
    }
  }

  private suspend fun initRunner() {
    runner =
      try {
        val agent =
          LlmAgent(
            name = AGENT_NAME,
            model =
              GenaiPrompt.create(
                GenerativeModelHelpers.initGenerativeModel(),
                name = "gemini-nano",
              ),
            instruction = Instruction("You are a helpful assistant. Answer in one short sentence."),
          )
        InMemoryRunner(agent = agent, appName = APP_NAME, sessionService = sessionService)
      } catch (e: Exception) {
        appendToTranscript("On-device model unavailable on this device: ${e.message}")
        null
      }
  }

  private fun sendToAgent(text: String) {
    val activeRunner = runner
    if (activeRunner == null) {
      appendToTranscript("Model is not ready yet.")
      return
    }
    appendToTranscript("you: $text")
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
            if (event.author == AGENT_NAME && reply.isNotBlank()) {
              appendToTranscript("$AGENT_NAME: $reply")
            }
          }
      } catch (e: Exception) {
        appendToTranscript("Error: ${e.message}")
      }
    }
  }

  private fun appendToTranscript(line: String) {
    runOnUiThread { transcript.append("$line\n\n") }
  }

  private fun buildContent(): LinearLayout {
    transcript = TextView(this).apply { setPadding(24, 24, 24, 24) }
    val scroll =
      ScrollView(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        addView(transcript)
      }
    input =
      EditText(this).apply {
        hint = "Type a message..."
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
      }
    val sendButton =
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
      addView(inputRow)
    }
  }

  private companion object {
    const val APP_NAME = "RoomSessionExample"
    const val USER_ID = "local-user"
    const val SESSION_ID = "local-session"
    const val AGENT_NAME = "room_session_agent"
  }
}
