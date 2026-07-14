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

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.examples.android.common.setExampleContentView
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.sessions.InMemorySessionService
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Minimal Android example: an [LlmAgent] whose [com.google.adk.kt.tools.SkillToolset] is backed by
 * an [com.google.adk.kt.skills.AssetSkillSource] reading skills from `assets/skills/...` packaged
 * into the APK.
 *
 * The cloud Gemini model is required because tool/function-calling drives the skills workflow and
 * the on-device ML Kit Gemini Nano model does not support tools. The API key is baked into the APK
 * at build time via a `${GEMINI_API_KEY}` manifest placeholder and read here from the application
 * `meta-data`. Supply it as `--define=GEMINI_API_KEY=...` (Bazel) or via the `GEMINI_API_KEY`
 * Gradle property / env var (Gradle, see `examples/android/build.gradle.kts`).
 */
// Hardcoded UI strings are intentional in this minimal example; a real app would use resources.
@Suppress("SetTextI18n")
class SkillsAssetSourceActivity : Activity() {

  // Coroutines launch on the default dispatcher; UI updates are marshaled via runOnUiThread.
  private val scope = CoroutineScope(SupervisorJob())

  private val sessionService = InMemorySessionService()

  /** Built lazily on the first send so we can surface construction failures in the transcript. */
  private var runner: InMemoryRunner? = null

  /** Resolved at onCreate. `null` means no key is available and the agent cannot be built. */
  private var apiKey: String? = null

  private lateinit var transcript: TextView
  private lateinit var input: EditText
  private lateinit var sendButton: Button

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setExampleContentView("Skills (AssetSkillSource)", buildContent())

    apiKey = resolveApiKey()
    if (apiKey == null) {
      appendToTranscript(
        "No Gemini API key baked into this APK. Rebuild with " +
          "`--define=GEMINI_API_KEY=your_key` (Bazel) or " +
          "`-PGEMINI_API_KEY=your_key` (Gradle) and reinstall."
      )
      sendButton.isEnabled = false
    } else {
      appendToTranscript("Ready. Try: \"Cast a fireball at the goblin\" or \"Summon a familiar\".")
    }
  }

  /**
   * Reads the API key written into the `com.google.adk.GEMINI_API_KEY` meta-data entry of the
   * application manifest by the build's `${GEMINI_API_KEY}` placeholder substitution. Returns
   * `null` if the entry is missing, blank, or still contains the literal placeholder (indicating
   * that no key was supplied at build time).
   */
  private fun resolveApiKey(): String? {
    val raw =
      try {
        val appInfo = packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
        appInfo.metaData?.getString("com.google.adk.GEMINI_API_KEY")
      } catch (e: PackageManager.NameNotFoundException) {
        null
      }
    return raw?.takeIf { it.isNotBlank() && !it.contains("GEMINI_API_KEY") }
  }

  private fun sendToAgent(text: String) {
    val key = apiKey ?: return
    val agent = runner ?: tryBuildRunner(key) ?: return

    appendToTranscript("you: $text")
    scope.launch {
      try {
        agent
          .runAsync(
            userId = USER_ID,
            sessionId = SESSION_ID,
            newMessage = Content(role = Role.USER, parts = listOf(Part(text = text))),
          )
          .collect { event ->
            val reply = event.content?.parts?.mapNotNull { it.text }?.joinToString(" ").orEmpty()
            if (event.author == WizardApprenticeAgent.NAME && reply.isNotBlank()) {
              appendToTranscript("${WizardApprenticeAgent.NAME}: $reply")
            }
          }
      } catch (e: Exception) {
        appendToTranscript("Error: ${e.message ?: e::class.simpleName}")
      }
    }
  }

  private fun tryBuildRunner(key: String): InMemoryRunner? {
    return try {
      val agent: LlmAgent = WizardApprenticeAgent.create(applicationContext, key)
      InMemoryRunner(agent = agent, appName = APP_NAME, sessionService = sessionService).also {
        runner = it
      }
    } catch (e: Throwable) {
      appendToTranscript("Failed to build agent: ${e.message ?: e::class.simpleName}")
      null
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
        hint = "Ask the wizard's apprentice…"
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
