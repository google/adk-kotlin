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
import com.google.adk.kt.examples.android.firebase.FirebaseAppResolver
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
 * The agent is backed by the cloud Firebase AI (Gemini) model (see [WizardApprenticeAgent]), whose
 * reliable function calling drives the toolset. It therefore needs a Firebase configuration and
 * network access; the Firebase-setup plumbing lives in [FirebaseAppResolver]. See the app README.md
 * for setup.
 */
class SkillsAssetSourceActivity : ScopedExampleActivity() {

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
          title = "Skills (AssetSkillSource)",
          messages = messages,
          inputEnabled = inputEnabled,
          onSend = ::sendToAgent,
          onBack = ::finish,
          hint = "Ask the wizard's apprentice…",
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
          agent = WizardApprenticeAgent.create(applicationContext, firebaseApp),
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
        "Ready. Try: \"Cast a fireball at the goblin\" or \"Summon a familiar\".",
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
            if (event.author == WizardApprenticeAgent.NAME && reply.isNotBlank()) {
              runOnUiThread {
                messages.add(ChatMessage(ChatAuthor.AGENT, reply, WizardApprenticeAgent.NAME))
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
    const val APP_NAME = "SkillsAssetSourceExample"
    const val USER_ID = "local-user"
    const val SESSION_ID = "local-session"
  }
}
