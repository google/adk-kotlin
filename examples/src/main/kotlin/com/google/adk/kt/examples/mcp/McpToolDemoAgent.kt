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
 * The agent connects to the Google Developer Knowledge MCP server over the Streamable HTTP
 * transport. `McpToolset` discovers the server's tools (`search_documents`, `answer_query` and
 * `get_documents`) on first use and exposes them to the model, so the agent declares no tools of
 * its own.
 *
 * The server authenticates each request with an `X-Goog-Api-Key` header. Set
 * `DEVELOPERKNOWLEDGE_API_KEY` to a Developer Knowledge API key, or reuse `GEMINI_API_KEY` if that
 * key is enabled for the Developer Knowledge API too. See
 * https://developers.google.com/knowledge/mcp for how to enable the API and create a key.
 *
 * `answer_query` is capped at 50 requests per day per project; the server asks callers to fall back
 * to `search_documents` once that returns 429.
 *
 * A server that also advertises MCP *resources* can surface them as extra tools by setting
 * `useMcpResources = true` on [McpToolset.McpToolsetConfig]. This server advertises tools only, so
 * the example leaves that off.
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
          Use search_documents to find relevant documentation, get_documents to
          read a document in full, and answer_query for a grounded answer.
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
                        (System.getenv("DEVELOPERKNOWLEDGE_API_KEY")?.takeUnless { it.isBlank() }
                          ?: System.getenv("GEMINI_API_KEY")?.takeUnless { it.isBlank() }
                          ?: error(
                            "Set DEVELOPERKNOWLEDGE_API_KEY, or GEMINI_API_KEY if that key is " +
                              "also enabled for the Developer Knowledge API. See " +
                              "https://developers.google.com/knowledge/mcp."
                          ))
                    ),
                )
            )
            .toToolset()
        ),
    )
}
