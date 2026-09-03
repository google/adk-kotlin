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

package com.google.adk.kt.examples.mcp;

import com.google.adk.kt.agents.BaseAgent;
import com.google.adk.kt.agents.LlmAgent;
import com.google.adk.kt.models.Gemini;
import com.google.adk.kt.tools.mcp.McpConnectionParameters;
import com.google.adk.kt.tools.mcp.McpToolset;
import java.util.Map;

/**
 * Java port of the MCP tool demo, exercising {@link McpToolset} through Java interop.
 *
 * <p>The agent connects to the Google Developer Knowledge MCP server over the Streamable HTTP
 * transport; {@link McpToolset} discovers the server's tools on first use and exposes them to the
 * model, so the agent declares no tools of its own. Set {@code DEVELOPERKNOWLEDGE_API_KEY} (or
 * reuse {@code GEMINI_API_KEY} if that key is enabled for the Developer Knowledge API). See
 * https://developers.google.com/knowledge/mcp.
 */
public final class McpToolDemoAgentJava {

  private static final String INSTRUCTION =
      """
      You are the interface to the Google Developer Knowledge system.
      Your goal is to assist developers in finding official documentation,
      code snippets, and best practices across Google's developer products.
      Use search_documents to find relevant documentation, get_documents to
      read a document in full, and answer_query for a grounded answer.\
      """;

  public static final BaseAgent rootAgent = buildRootAgent();

  private static BaseAgent buildRootAgent() {
    McpToolset toolset =
        McpToolset.McpToolsetConfig.builder()
            .streamableHttpConnectionParams(
                McpConnectionParameters.StreamableHttp.builder()
                    .url("https://developerknowledge.googleapis.com/mcp")
                    .headers(Map.of("X-Goog-Api-Key", requireApiKey()))
                    .build())
            .build()
            .toToolset();

    // The Kotlin demo also adds a cosmetic beforeToolCallback. That callback is a suspend
    // functional interface, so implementing it from Java needs a dedicated adapter (a separate
    // interop item), and this port omits it.
    return LlmAgent.builder()
        .name("mcp_assistant")
        .model(new Gemini("gemini-3.1-flash-lite"))
        .instruction(INSTRUCTION)
        .toolsets(toolset)
        .build();
  }

  private static String requireApiKey() {
    String key = System.getenv("DEVELOPERKNOWLEDGE_API_KEY");
    if (key == null || key.isBlank()) {
      key = System.getenv("GEMINI_API_KEY");
    }
    if (key == null || key.isBlank()) {
      throw new IllegalStateException(
          "Set DEVELOPERKNOWLEDGE_API_KEY, or GEMINI_API_KEY if that key is also enabled for the"
              + " Developer Knowledge API. See https://developers.google.com/knowledge/mcp.");
    }
    return key;
  }

  private McpToolDemoAgentJava() {}
}
