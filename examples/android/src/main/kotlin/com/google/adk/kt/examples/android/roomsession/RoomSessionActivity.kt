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
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.adk.kt.agents.Instruction
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.examples.android.common.ScopedExampleActivity
import com.google.adk.kt.examples.android.common.foldTextParts
import com.google.adk.kt.examples.android.common.ui.AdkExamplesTheme
import com.google.adk.kt.examples.android.common.ui.ChatAuthor
import com.google.adk.kt.examples.android.common.ui.ChatMessage
import com.google.adk.kt.examples.android.common.ui.ChatScreen
import com.google.adk.kt.mlkit.GenaiPrompt
import com.google.adk.kt.mlkit.GenerativeModelHelpers
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.sessions.room.RoomSessionService
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
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
class RoomSessionActivity : ScopedExampleActivity() {

  // The Room-backed, on-device persistent session service (one SQLite database on disk).
  private val sessionService by lazy { RoomSessionService.fromContext(applicationContext) }
  private val sessionKey = SessionKey(APP_NAME, USER_ID, SESSION_ID)

  // Built lazily because initializing the on-device model is a suspend call.
  private var runner: InMemoryRunner? = null

  private val messages = mutableStateListOf<ChatMessage>()
  private var inputEnabled by mutableStateOf(false)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      AdkExamplesTheme {
        ChatScreen(
          title = "Room session",
          messages = messages,
          inputEnabled = inputEnabled,
          onSend = ::sendToAgent,
          onBack = ::finish,
        )
      }
    }

    scope.launch {
      // Reload any conversation persisted by a previous run to demonstrate durability.
      val existing = sessionService.getSession(sessionKey)
      if (existing != null && existing.events.isNotEmpty()) {
        addSystem("Reloaded ${existing.events.size} persisted event(s) from a previous run.")
        for (event in existing.events) {
          val isUser = event.author != AGENT_NAME
          event.content
            ?.parts
            ?.mapNotNull { it.text }
            ?.forEach { text -> add(if (isUser) ChatAuthor.USER else ChatAuthor.AGENT, text) }
        }
      } else {
        val unused = sessionService.createSession(sessionKey)
        addSystem("New on-device session created. Messages persist across app restarts.")
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
        addSystem("On-device model unavailable on this device: ${e.message}")
        null
      }
    if (runner != null) runOnUiThread { inputEnabled = true }
  }

  private fun sendToAgent(text: String) {
    val activeRunner = runner ?: return
    add(ChatAuthor.USER, text)
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
              add(ChatAuthor.AGENT, reply)
            }
          }
      } catch (e: Exception) {
        addSystem("Error: ${e.message ?: e::class.simpleName}")
      }
    }
  }

  private fun add(author: ChatAuthor, text: String) {
    val label = if (author == ChatAuthor.AGENT) AGENT_NAME else ""
    runOnUiThread { messages.add(ChatMessage(author, text, label)) }
  }

  private fun addSystem(text: String) {
    runOnUiThread { messages.add(ChatMessage(ChatAuthor.SYSTEM, text)) }
  }

  private companion object {
    const val APP_NAME = "RoomSessionExample"
    const val USER_ID = "local-user"
    const val SESSION_ID = "local-session"
    const val AGENT_NAME = "room_session_agent"
  }
}
