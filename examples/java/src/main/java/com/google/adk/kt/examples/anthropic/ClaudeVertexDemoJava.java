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

package com.google.adk.kt.examples.anthropic;

import com.google.adk.kt.agents.BaseAgent;
import com.google.adk.kt.agents.LlmAgent;
import com.google.adk.kt.models.VertexCredentials;
import com.google.adk.kt.models.anthropic.Claude;

/**
 * Java port of the Claude on Vertex AI demo. Authenticates with Application Default Credentials in
 * the project named by {@code GOOGLE_CLOUD_PROJECT}.
 */
public final class ClaudeVertexDemoJava {

  private static final String MODEL = "claude-haiku-4-5";

  public static final BaseAgent rootAgent =
      LlmAgent.builder()
          .name("claude_vertex_demo")
          .model(new Claude(MODEL, VertexCredentials.builder().location("global").build()))
          .instruction("You are a helpful assistant. Keep answers concise.")
          .build();

  private ClaudeVertexDemoJava() {}
}
