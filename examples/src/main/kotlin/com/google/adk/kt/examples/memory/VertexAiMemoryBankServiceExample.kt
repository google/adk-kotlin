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

package com.google.adk.kt.examples.memory

import com.google.adk.kt.agents.Instruction
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.callbacks.AfterAgentCallback
import com.google.adk.kt.callbacks.BeforeModelCallback
import com.google.adk.kt.callbacks.CallbackChoice
import com.google.adk.kt.memory.VertexAiMemoryBankService
import com.google.adk.kt.models.Gemini
import com.google.adk.kt.models.VertexCredentials
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.tools.PreloadMemoryTool
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Role
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking

private const val MODEL_NAME = "gemini-3.1-flash-lite"

private const val APP_NAME = "memory-bank-example"
private const val USER_ID = "demo-user"

/** Full message: states Alex's preferences, so this turn seeds memory for later recall. */
private const val SEED_MESSAGE =
  "Hi! I'm Alex, I'm vegetarian, and I love hiking. What should I have for dinner?"

/** Greeting only: states no preferences, so anything the model brings up must come from memory. */
private const val RECALL_MESSAGE = "Hi! I'm Alex. What should I have for dinner?"

/**
 * A standard-ADK example of using [VertexAiMemoryBankService] with an agent.
 *
 * The memory service is wired into the [InMemoryRunner], and the agent uses it from both ends:
 * - [PreloadMemoryTool] retrieves relevant memories at the start of each turn and injects them into
 *   the prompt, so the model sees past preferences/facts without an explicit tool call.
 * - an after-agent callback calls [com.google.adk.kt.agents.CallbackContext.addSessionToMemory], so
 *   the conversation is written back to memory after each turn for recall in future sessions.
 *
 * This mirrors the "Memory Bank quickstart" agent shape from the Python ADK samples, backed by the
 * Vertex AI Agent Engine Memory Bank.
 *
 * Memory Bank generates and consolidates memories asynchronously, so what one turn stores becomes
 * searchable a little later; run the example again to see the agent recall it in a fresh session.
 *
 * To make recall easy to judge, the first argument selects the user message:
 * - (default) - the full message that states Alex's preferences, seeding memory. On this run the
 *   preferences are in the prompt, so a good answer proves nothing about memory.
 * - `short` - a greeting that states no preferences. Run this after the memory is generated: any
 *   preference the model uses must have been recalled, which the `[memory] injected past
 *   conversations: true` line confirms.
 *
 * Authentication uses Application Default Credentials (`gcloud auth application-default login`).
 *
 * Environment variables:
 * - `GOOGLE_CLOUD_PROJECT` - GCP project id.
 * - `VERTEX_AGENT_ENGINE_ID` - the numeric Agent Engine id that owns the memories.
 * - `GOOGLE_CLOUD_LOCATION` - Vertex region for the Agent Engine (optional, defaults to
 *   `us-central1`).
 * - `GOOGLE_CLOUD_MODEL_LOCATION` - Vertex region for the model (optional, defaults to `global`).
 */
fun main(args: Array<String>) {
  runBlocking {
    val project = requireEnv("GOOGLE_CLOUD_PROJECT")
    val agentEngineId = requireEnv("VERTEX_AGENT_ENGINE_ID")
    val location =
      System.getenv("GOOGLE_CLOUD_LOCATION")?.takeUnless { it.isBlank() } ?: "us-central1"
    val modelLocation =
      System.getenv("GOOGLE_CLOUD_MODEL_LOCATION")?.takeUnless { it.isBlank() } ?: "global"

    val memoryService =
      VertexAiMemoryBankService(
        project = project,
        location = location,
        agentEngineId = agentEngineId,
      )

    val agent =
      LlmAgent(
        name = "memory_agent",
        description = "A helpful assistant that remembers user preferences across conversations.",
        model =
          Gemini(name = MODEL_NAME, vertexCredentials = VertexCredentials(project, modelLocation)),
        instruction =
          Instruction(
            "You are a helpful assistant. You remember user preferences and facts from previous " +
              "conversations and use them to personalize your responses. Answer briefly."
          ),
        tools = listOf(PreloadMemoryTool()),
        // Reports whether PreloadMemoryTool actually injected recalled memories into this turn's
        // prompt (the `<PAST_CONVERSATIONS>` block it adds to the system instruction).
        beforeModelCallbacks =
          listOf(
            BeforeModelCallback { _, request ->
              val systemText =
                request.config.systemInstruction
                  ?.parts
                  ?.mapNotNull { it.text }
                  ?.joinToString("\n")
                  .orEmpty()
              println(
                "[memory] injected past conversations: ${"<PAST_CONVERSATIONS>" in systemText}"
              )
              CallbackChoice.Continue(request)
            }
          ),
        afterAgentCallbacks =
          listOf(
            AfterAgentCallback { callbackContext ->
              callbackContext.addSessionToMemory()
              CallbackChoice.Continue(Unit)
            }
          ),
      )

    val runner = InMemoryRunner(agent = agent, appName = APP_NAME, memoryService = memoryService)
    val sessionId =
      runner.sessionService.createSession(SessionKey(APP_NAME, USER_ID, id = null)).key.id!!

    val message =
      if (args.firstOrNull()?.equals("short", ignoreCase = true) == true) {
        RECALL_MESSAGE
      } else {
        SEED_MESSAGE
      }
    println("user > $message")
    runner
      .runAsync(
        userId = USER_ID,
        sessionId = sessionId,
        newMessage = Content.fromText(Role.USER, message),
      )
      .collect { event ->
        if (event.partial == true) return@collect
        val text = event.content?.parts?.mapNotNull { it.text }?.joinToString(" ").orEmpty()
        if (text.isNotBlank()) println("${event.author} > $text")
      }

    println(
      "\nThe after-agent callback stored this turn to the Memory Bank. Run the example again " +
        "(after the memory is generated) with the `short` argument to watch PreloadMemoryTool " +
        "recall it in a new session."
    )
  }

  exitProcess(0)
}

private fun requireEnv(name: String): String =
  System.getenv(name)?.takeUnless { it.isBlank() }
    ?: error("Environment variable $name is not set.")
