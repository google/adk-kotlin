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

package com.google.adk.kt.examples.android.skillsassetsource

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
 * Minimal Android example: an [com.google.adk.kt.agents.LlmAgent] whose
 * [com.google.adk.kt.tools.SkillToolset] is backed by an
 * [com.google.adk.kt.skills.AssetSkillSource] reading skills from `assets/skills/...` packaged into
 * the APK.
 *
 * The agent runs fully on-device through ML Kit's Gemini Nano, so no API key or network is
 * required.
 */
// Hardcoded UI strings are intentional in this minimal example; a real app would use resources.
@Suppress("SetTextI18n")
class SkillsAssetSourceActivity : ScopedExampleActivity() {

  private val sessionService = InMemorySessionService()

  /**
   * Built asynchronously in [onCreate] because initializing the on-device model is a suspend call.
   */
  private var runner: InMemoryRunner? = null

  private lateinit var transcript: TextView
  private lateinit var input: EditText
  private lateinit var sendButton: Button

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setExampleContentView("Skills (AssetSkillSource)", buildContent())

    sendButton.isEnabled = false
    scope.launch {
      runner =
        try {
          val agent = WizardApprenticeAgent.create(applicationContext)
          InMemoryRunner(agent = agent, appName = APP_NAME, sessionService = sessionService)
        } catch (e: Throwable) {
          appendToTranscript(
            "On-device model unavailable on this device: ${e.message ?: e::class.simpleName}"
          )
          null
        }
      if (runner != null) {
        runOnUiThread { sendButton.isEnabled = true }
        appendToTranscript(
          "Ready. Try: \"Cast a fireball at the goblin\" or \"Summon a familiar\"."
        )
      }
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
            if (event.author == WizardApprenticeAgent.NAME && reply.isNotBlank()) {
              appendToTranscript("${WizardApprenticeAgent.NAME}: $reply")
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
        hint = "Ask the wizard's apprentice..."
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
    const val APP_NAME = "SkillsAssetSourceExample"
    const val USER_ID = "local-user"
    const val SESSION_ID = "local-session"
  }
}
