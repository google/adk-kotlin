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

package com.google.adk.kt.examples.hello

import com.google.adk.kt.agents.Instruction
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.models.Gemini
import com.google.adk.kt.models.VertexCredentials

/**
 * Example hello agent using the Kotlin ADK implementation.
 *
 * This agent demonstrates the fundamental principles of building an agent with the ADK:
 * 1. Defining the [LlmAgent] with a specific model and behavior (instructions).
 * 2. Using any model configuration (here, [Gemini] on Vertex AI).
 *
 * With no project or credentials set, [VertexCredentials] falls back to `GOOGLE_CLOUD_PROJECT` and
 * Application Default Credentials.
 */
object HelloAgent {
  @JvmField
  val rootAgent =
    LlmAgent(
      name = "hello_agent",
      model =
        Gemini(
          name = "gemini-3.8-flash",
          vertexCredentials = VertexCredentials(location = "global"),
        ),
      // The instruction defines the agent's persona and primary behavior.
      instruction =
        Instruction("You always greet the user with \"Hello\" and try to solve math problems."),
    )
}
