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

import com.google.adk.firebase.models.Firebase
import com.google.adk.kt.agents.Instruction
import com.google.adk.kt.agents.LlmAgent
import com.google.firebase.FirebaseApp
import com.google.firebase.ai.FirebaseAI

/**
 * Builds the [LlmAgent] used by [FirebaseChatActivity], backed by the Firebase AI (Gemini) model
 * from the `:google-adk-kotlin-firebase` module.
 *
 * The agent both answers general questions (plain chat) and can call [WeatherTools] to look up a
 * temperature, mirroring the two scenarios proven by the module's `FirebaseIntegrationTest`.
 */
internal object FirebaseChatAgent {
  const val NAME: String = "firebase_agent"

  /**
   * The Firebase AI model to use. Any model available to your Firebase project works; override it
   * if this one is not enabled for you.
   */
  private const val MODEL_NAME: String = "gemini-3.5-flash"

  /** Builds the agent against the given (already initialized) [firebaseApp]. */
  fun create(firebaseApp: FirebaseApp): LlmAgent =
    LlmAgent(
      name = NAME,
      model = Firebase.create(MODEL_NAME, FirebaseAI.getInstance(firebaseApp)),
      instruction =
        Instruction(
          """
          You are a helpful assistant. Answer general questions in one or two sentences. When the
          user asks about the current temperature somewhere, call the get_current_temperature tool
          and state the exact value it returns.
          """
            .trimIndent()
        ),
      tools = WeatherTools().generatedTools(),
    )
}
