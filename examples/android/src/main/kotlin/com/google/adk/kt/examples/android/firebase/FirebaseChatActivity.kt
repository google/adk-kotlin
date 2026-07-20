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
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.adk.kt.examples.android.common.ScopedExampleActivity
import com.google.adk.kt.examples.android.common.foldTextParts
import com.google.adk.kt.examples.android.common.setExampleContentView
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
// Hardcoded UI strings are intentional in this minimal example; a real app would use resources.
@Suppress("SetTextI18n")
class FirebaseChatActivity : ScopedExampleActivity() {

  private val sessionService = InMemorySessionService()

  /**
   * Built in [onCreate] once a FirebaseApp is resolved; null means the agent could not be created.
   */
  private var runner: InMemoryRunner? = null

  private lateinit var transcript: TextView
  private lateinit var input: EditText
  private lateinit var sendButton: Button

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setExampleContentView("Firebase AI chat", buildContent())

    val firebaseApp = FirebaseAppResolver.resolve(applicationContext)
    if (firebaseApp == null) {
      appendToTranscript(
        "No Firebase configuration found. Add a google-services.json to the examples/android/ " +
          "module (standard setup), or rebuild supplying -PFIREBASE_API_KEY=... " +
          "-PFIREBASE_APP_ID=... -PFIREBASE_PROJECT_ID=... (or the matching environment " +
          "variables). See the app README.md."
      )
      sendButton.isEnabled = false
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
        appendToTranscript("Failed to build agent: ${e.message ?: e::class.simpleName}")
        sendButton.isEnabled = false
        return
      }

    appendToTranscript(
      "Ready. Try: \"Tell me about the planet Earth\" or " +
        "\"What is the current temperature in Mountain View?\""
    )
  }

  private fun sendToAgent(text: String) {
    val activeRunner = runner ?: return
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
            if (event.author == FirebaseChatAgent.NAME && reply.isNotBlank()) {
              appendToTranscript("${FirebaseChatAgent.NAME}: $reply")
            }
          }
      } catch (e: Exception) {
        appendToTranscript("Error: ${e.message ?: e::class.simpleName}")
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
        hint = "Type a message…"
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
      addView(inputRow)
    }
  }

  private companion object {
    const val APP_NAME = "FirebaseChatExample"
    const val USER_ID = "local-user"
    const val SESSION_ID = "local-session"
  }
}
