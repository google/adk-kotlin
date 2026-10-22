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

package com.google.adk.kt.examples.callbacks

import com.google.adk.kt.agents.CallbackContext
import com.google.adk.kt.agents.Instruction
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.annotations.Tool
import com.google.adk.kt.callbacks.BeforeModelCallback
import com.google.adk.kt.callbacks.BeforeToolCallback
import com.google.adk.kt.callbacks.CallbackChoice
import com.google.adk.kt.models.Gemini
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.models.VertexCredentials
import com.google.adk.kt.tools.BaseTool
import com.google.adk.kt.tools.ToolContext

/** Humorous Alice-in-Wonderland tools exposed to the LLM via `@Tool` annotations. */
class WonderlandItems {

  /** Shrinks the user so they can fit through small doors. */
  @Tool(name = "drink_me")
  fun drinkMePotion(): Map<String, String> {
    println(">>> 🧪 [SYSTEM]: *Gulp gulp gulp*... You feel yourself getting smaller and smaller!")
    return mapOf("result" to "You are now 10 inches tall. Perfect for tiny doors!")
  }

  /** Grows the user so they can reach high shelves. */
  @Tool(name = "eat_me")
  fun eatMeCake(): Map<String, String> {
    println(">>> 🍰 [SYSTEM]: *Nom nom nom*... Your head is hitting the ceiling!")
    return mapOf("result" to "You are now 10 feet tall. You can reach the glass table!")
  }
}

/** A callback that logs a Cheshire Cat grin before the model runs! */
class CheshireCatCallback : BeforeModelCallback {
  override suspend fun call(
    context: CallbackContext,
    request: LlmRequest,
  ): CallbackChoice<LlmRequest, LlmResponse> {
    println(">>> 😸 [CALLBACK]: Cheshire Cat grin appears... 'We're all mad here.'")
    return CallbackChoice.Continue(request)
  }
}

/** A callback that warns about being late before a tool is called! */
class WhiteRabbitCallback : BeforeToolCallback {
  override suspend fun call(
    context: ToolContext,
    tool: BaseTool,
    args: Map<String, Any?>,
  ): CallbackChoice<Map<String, Any?>, Map<String, Any?>> {
    println(
      ">>> 🐰 [CALLBACK]: White Rabbit checks his watch... 'Oh dear! Oh dear! I shall be too late!' (Executing ${tool.name})"
    )
    return CallbackChoice.Continue(args)
  }
}

/** Whimsical demo agent Alice in Processorland demonstrating callbacks and observers. */
object CallbacksAndObserversDemoAgent {
  @JvmField
  val rootAgent =
    LlmAgent(
      name = "alice",
      model =
        Gemini(
          name = "gemini-3.8-flash",
          vertexCredentials = VertexCredentials(location = "global"),
        ),
      instruction =
        Instruction(
          """You are Alice, exploring a very strange and nonsensical digital Wonderland.
You have found two items: a potion labeled "DRINK ME" and a cake labeled "EAT ME".
When the user asks you to interact with the environment, try using your tools to see what happens.
Speak with a sense of wonder and slight confusion."""
        ),
      // Note: We expect the KSP processor to generate this extension for WonderlandItems.
      tools = WonderlandItems().generatedTools(),
      beforeModelCallbacks = listOf(CheshireCatCallback()),
      beforeToolCallbacks = listOf(WhiteRabbitCallback()),
    )
}
