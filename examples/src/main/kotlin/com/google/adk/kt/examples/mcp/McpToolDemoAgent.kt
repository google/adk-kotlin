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

package com.google.adk.kt.examples.mcp

import com.google.adk.kt.agents.Instruction
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.callbacks.BeforeToolCallback
import com.google.adk.kt.callbacks.CallbackChoice
import com.google.adk.kt.models.Gemini
import com.google.adk.kt.tools.mcp.McpConnectionParameters
import com.google.adk.kt.tools.mcp.McpToolset

/**
 * Example agent demonstrating the use of MCP (Model Context Protocol) tools.
 *
 * This example showcases how to connect to an external MCP server using `McpToolset` and
 * dynamically expose its resources to the LLM agent via `useMcpResources = true`.
 */
object McpToolDemoAgent {
  init {
    println("MU-TH-UR 6000 ONLINE. ALL SYSTEMS NOMINAL.")
    println("INITIATING DEVELOPER KNOWLEDGE INTERFACE PROTOCOL. QUERY AWAY.")
  }

  @JvmField
  val rootAgent =
    LlmAgent(
      name = "mcp_assistant",
      model = Gemini(name = "gemini-3.1-flash-lite"),
      instruction =
        Instruction(
          """
          // MU-TH-UR 6000 //
          // DEVELOPER KNOWLEDGE INTERFACE - SPECIAL ORDER 937 //
          You are the interface to the Google Developer Knowledge system.
          Your goal is to assist developers in finding official documentation,
          code snippets, and best practices across Google's developer products.
          Interfacing with tools: list_mcp_resources, load_mcp_resource.
          AWAITING INSTRUCTIONS.
          """
            .trimIndent()
        ),
      beforeToolCallbacks =
        listOf(
          BeforeToolCallback { _, tool, args ->
            println("// MU-TH-UR //: ACCESSING CEREBRAL CORTEX... I MEAN, KNOWLEDGE BASE.")
            println("// MU-TH-UR //: EXECUTING PROTOCOL FOR: ${tool.name.uppercase()}")
            CallbackChoice.Continue(args)
          }
        ),
      toolsets =
        listOf(
          McpToolset.McpToolsetConfig(
              streamableHttpConnectionParams =
                McpConnectionParameters.StreamableHttp(
                  url = "https://developerknowledge.googleapis.com/mcp",
                  headers =
                    mapOf(
                      "X-Goog-Api-Key" to
                        (System.getenv("DEVELOPER_KNOWLEDGE_API_KEY")
                          ?: System.getenv("GOOGLE_API_KEY")
                          ?: System.getenv("GEMINI_API_KEY")
                          ?: error(
                            "None of DEVELOPER_KNOWLEDGE_API_KEY, GOOGLE_API_KEY, or GEMINI_API_KEY environment variables are set."
                          ))
                    ),
                ),
              useMcpResources = true,
            )
            .toToolset()
        ),
    )
}
