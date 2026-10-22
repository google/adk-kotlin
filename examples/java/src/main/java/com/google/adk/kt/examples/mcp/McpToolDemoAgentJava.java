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
import com.google.adk.kt.models.VertexCredentials;
import com.google.adk.kt.tools.mcp.McpConnectionParameters;
import com.google.adk.kt.tools.mcp.McpToolset;
import com.google.auth.oauth2.GoogleCredentials;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;

/**
 * Java port of the MCP tool demo, exercising {@link McpToolset} through Java interop.
 *
 * <p>The agent connects to the Google Developer Knowledge MCP server over the Streamable HTTP
 * transport; {@link McpToolset} discovers the server's tools on first use and exposes them to the
 * model, so the agent declares no tools of its own. The server authenticates with an Application
 * Default Credentials access token and the project from {@code GOOGLE_CLOUD_PROJECT}. That project
 * must have the Developer Knowledge API enabled; see https://developers.google.com/knowledge/mcp.
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
                    .headers(authHeaders())
                    .build())
            .build()
            .toToolset();

    // The Kotlin demo also adds a cosmetic beforeToolCallback. That callback is a suspend
    // functional interface, so implementing it from Java needs a dedicated adapter (a separate
    // interop item), and this port omits it.
    return LlmAgent.builder()
        .name("mcp_assistant")
        .model(
            new Gemini("gemini-3.8-flash", VertexCredentials.builder().location("global").build()))
        .instruction(INSTRUCTION)
        .toolsets(toolset)
        .build();
  }

  /**
   * Returns the server's auth headers. The access token expires after an hour and is not refreshed
   * per call, because {@code headerProvider} is a Kotlin suspend function with no Java adapter yet.
   */
  private static Map<String, String> authHeaders() {
    String project = System.getenv("GOOGLE_CLOUD_PROJECT");
    if (project == null || project.isBlank()) {
      throw new IllegalStateException("Set GOOGLE_CLOUD_PROJECT.");
    }
    try {
      GoogleCredentials credentials =
          GoogleCredentials.getApplicationDefault()
              .createScoped("https://www.googleapis.com/auth/cloud-platform");
      credentials.refreshIfExpired();
      return Map.of(
          "Authorization",
          "Bearer " + credentials.getAccessToken().getTokenValue(),
          "X-Goog-User-Project",
          project);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private McpToolDemoAgentJava() {}
}
