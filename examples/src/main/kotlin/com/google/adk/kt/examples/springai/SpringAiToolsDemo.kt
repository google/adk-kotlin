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

package com.google.adk.kt.examples.springai

import com.google.adk.kt.agents.Instruction
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.agents.RunConfig
import com.google.adk.kt.callbacks.AfterToolCallback
import com.google.adk.kt.callbacks.BeforeToolCallback
import com.google.adk.kt.callbacks.CallbackChoice
import com.google.adk.kt.models.springai.SpringAiModel
import com.google.adk.kt.models.springai.SpringAiTool
import com.google.adk.kt.models.springai.SpringAiToolset
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.tools.BaseTool
import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FunctionDeclaration
import com.google.adk.kt.types.Role
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type
import com.google.genai.Client
import java.util.function.Function
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.springframework.ai.google.genai.GoogleGenAiChatModel
import org.springframework.ai.google.genai.GoogleGenAiChatOptions
import org.springframework.ai.tool.ToolCallback
import org.springframework.ai.tool.ToolCallbackProvider
import org.springframework.ai.tool.function.FunctionToolCallback
import org.springframework.ai.tool.metadata.ToolMetadata

/**
 * Runnable demo of the Spring AI integration, driving a real Vertex Gemini model through
 * [SpringAiModel]. It walks the cases the module's live tests cover so the behavior is inspectable:
 * plain chat, a native ADK tool, a bridged Spring AI tool, a mixed tool loop, a `SpringAiToolset`
 * for dependency injection, a `returnDirect` tool, and before/after tool callbacks on a bridged
 * tool.
 *
 * Run it (needs Application Default Credentials and `GOOGLE_CLOUD_PROJECT`; `GOOGLE_CLOUD_LOCATION`
 * defaults to `global`):
 * ```
 * GOOGLE_CLOUD_PROJECT=my-project GOOGLE_API_USE_CLIENT_CERTIFICATE=false \
 *   ./gradlew :google-adk-kotlin-examples:runSpringAiToolsDemo
 * ```
 */
private const val MODEL = "gemini-flash-latest"

fun main() =
  runBlocking<Unit> {
    val model = springModel()

    // Case 1: plain chat, no tools.
    runCase(
      title = "Plain chat (adapter only, no tools)",
      agent = LlmAgent(name = "chat", model = model, instruction = Instruction("Be concise.")),
      prompt = "Say hello in exactly one word.",
    )

    // Case 2: a native ADK tool. ADK executes it; Spring AI only ever receives its schema.
    runCase(
      title = "Native ADK tool (ADK executes it; Spring AI sees schema only)",
      agent =
        LlmAgent(
          name = "dice",
          model = model,
          instruction = Instruction("Use the provided tools when they help."),
          tools = listOf(rollDice()),
        ),
      prompt = "Roll a 6-sided dice and tell me the number.",
    )

    // Case 3: a Spring AI tool bridged into ADK via SpringAiTool.
    runCase(
      title = "Bridged Spring AI tool (SpringAiTool wraps a Spring ToolCallback)",
      agent =
        LlmAgent(
          name = "weather",
          model = model,
          instruction = Instruction("Use the provided tools when they help."),
          tools = listOf(SpringAiTool(weatherCallback())),
        ),
      prompt = "What is the weather in Paris?",
    )

    // Case 4: the event loop with mixed tool kinds. Two Spring AI tools (a JSON-object result and a
    // plain-string result) plus one native ADK tool, all driven by the ADK loop in one turn.
    val springTools: List<BaseTool> = SpringAiTool.from(listOf(weatherCallback(), timeCallback()))
    runCase(
      title = "Mixed loop: two Spring AI tools + one native ADK tool",
      agent =
        LlmAgent(
          name = "mixed",
          model = model,
          instruction = Instruction("Before answering, call the tools you need, then summarize."),
          tools = springTools + rollDice(),
        ),
      prompt = "Tell me the weather in Paris, the time in UTC, and roll a 6-sided dice.",
    )

    // Case 5: dependency-injection convenience. A ToolCallbackProvider (e.g. a Spring bean) becomes
    // an ADK Toolset via SpringAiToolset.
    val provider =
      object : ToolCallbackProvider {
        override fun getToolCallbacks(): Array<ToolCallback> =
          arrayOf(weatherCallback(), timeCallback())
      }
    runCase(
      title = "DI convenience: SpringAiToolset(provider)",
      agent =
        LlmAgent(
          name = "provider",
          model = model,
          instruction = Instruction("Use the provided tools when they help."),
          toolsets = listOf(SpringAiToolset(provider)),
        ),
      prompt = "What time is it in UTC?",
    )

    // Case 6: a returnDirect tool. Its result is returned to the caller without a summary turn, so
    // the run ends on the tool response with no trailing model text.
    runCase(
      title = "returnDirect tool (result returned without a model summary turn)",
      agent =
        LlmAgent(
          name = "policy",
          model = model,
          instruction = Instruction("Use lookup_policy for policy questions."),
          tools = listOf(SpringAiTool(returnDirectPolicy())),
        ),
      prompt = "What is the return policy?",
    )

    // Case 7: before/after tool callbacks on a bridged Spring AI tool. Both fire around the tool,
    // and the after-callback rewrites the result before it becomes the function response.
    runCase(
      title = "Tool callbacks (before/after) firing on a SpringAiTool",
      agent =
        LlmAgent(
          name = "callbacks",
          model = model,
          instruction = Instruction("Use the get_weather tool for weather questions."),
          tools = listOf(SpringAiTool(weatherCallback())),
          beforeToolCallbacks =
            listOf(
              BeforeToolCallback { _, tool, args ->
                println("  [before-tool]   ${tool.name} fired, args=$args")
                CallbackChoice.Continue(args)
              }
            ),
          afterToolCallbacks =
            listOf(
              AfterToolCallback { _, tool, _, result ->
                println("  [after-tool]    ${tool.name} fired, rewriting result")
                result + ("note" to "added by afterToolCallback")
              }
            ),
        ),
      prompt = "What is the weather in Paris?",
    )
  }

/** Runs one prompt through a fresh runner and prints the tool calls, tool responses, and answer. */
private suspend fun runCase(title: String, agent: LlmAgent, prompt: String) {
  println("\n===== $title =====")
  println("user> $prompt")
  val runner = InMemoryRunner(agent = agent, appName = "springai_tools_demo")
  val events =
    runner
      .runAsync(
        userId = "demo",
        sessionId = "session",
        newMessage = Content.fromText(Role.USER, prompt),
        runConfig = RunConfig(maxLlmCalls = 8),
      )
      .toList()

  for (event in events) {
    event.functionCalls().forEach { println("  [tool-call]     ${it.name}(${it.args})") }
    event.functionResponses().forEach { println("  [tool-response] ${it.name} -> ${it.response}") }
  }
  events
    .lastOrNull { !it.content?.text().isNullOrBlank() }
    ?.let { println("model> ${it.content?.text()?.trim()}") }
  val totalTokens = events.mapNotNull { it.usageMetadata?.totalTokenCount }.sum()
  println("  (finishReason=${events.last().finishReason}, totalTokens=$totalTokens)")
}

private fun springModel(): SpringAiModel {
  val project = System.getenv("GOOGLE_CLOUD_PROJECT").orEmpty()
  require(project.isNotBlank()) {
    "Set GOOGLE_CLOUD_PROJECT (and optionally GOOGLE_CLOUD_LOCATION, default global) to run."
  }
  val location = System.getenv("GOOGLE_CLOUD_LOCATION").orEmpty().ifBlank { "global" }
  val client = Client.builder().vertexAI(true).project(project).location(location).build()
  val options = GoogleGenAiChatOptions.builder().model(MODEL).build()
  val chatModel = GoogleGenAiChatModel.builder().genAiClient(client).options(options).build()
  return SpringAiModel(MODEL, chatModel)
}

/** A Spring AI tool whose result is a JSON object. */
private fun weatherCallback(): ToolCallback =
  FunctionToolCallback.builder(
      "get_weather",
      Function<Map<String, Any?>, String> { "{\"tempC\":18}" },
    )
    .description("Get the current weather for a city, in Celsius.")
    .inputType(Map::class.java)
    .inputSchema(
      "{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}},\"required\":[\"city\"]}"
    )
    .build()

/** A Spring AI tool whose result is a plain string (decoded and wrapped under "result"). */
private fun timeCallback(): ToolCallback =
  FunctionToolCallback.builder("get_time", Function<Map<String, Any?>, String> { "09:00 UTC" })
    .description("Get the current local time for a timezone.")
    .inputType(Map::class.java)
    .inputSchema(
      "{\"type\":\"object\",\"properties\":{\"tz\":{\"type\":\"string\"}},\"required\":[\"tz\"]}"
    )
    .build()

/** A Spring AI tool marked returnDirect, so ADK skips the model's summary of its result. */
private fun returnDirectPolicy(): ToolCallback =
  FunctionToolCallback.builder(
      "lookup_policy",
      Function<Map<String, Any?>, String> { "{\"policy\":\"Returns accepted within 30 days.\"}" },
    )
    .description("Look up a store policy; its result is returned to the user directly.")
    .inputType(Map::class.java)
    .inputSchema(
      "{\"type\":\"object\",\"properties\":{\"topic\":{\"type\":\"string\"}},\"required\":[\"topic\"]}"
    )
    .toolMetadata(ToolMetadata.builder().returnDirect(true).build())
    .build()

/** A native ADK tool (a [BaseTool] subclass), with no Spring AI involvement. */
private fun rollDice(): BaseTool =
  object : BaseTool(name = "roll_dice", description = "Roll a fair dice with the given sides.") {
    override fun declaration(): FunctionDeclaration =
      FunctionDeclaration(
        name = name,
        description = description,
        parameters =
          Schema(
            type = Type.OBJECT,
            properties = mapOf("sides" to Schema(type = Type.INTEGER)),
            required = listOf("sides"),
          ),
      )

    override suspend fun run(context: ToolContext, args: Map<String, Any?>): Any =
      mapOf("result" to 4)
  }
