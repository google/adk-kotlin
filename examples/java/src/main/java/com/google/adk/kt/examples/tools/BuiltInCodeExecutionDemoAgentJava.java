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

package com.google.adk.kt.examples.tools;

import com.google.adk.kt.agents.BaseAgent;
import com.google.adk.kt.agents.LlmAgent;
import com.google.adk.kt.models.Gemini;
import com.google.adk.kt.tools.BuiltInCodeExecutionTool;

/**
 * Example agent that answers questions needing computation by having the Gemini model write and run
 * code on its own serving side, through a {@link BuiltInCodeExecutionTool}.
 */
public final class BuiltInCodeExecutionDemoAgentJava {

  public static final BaseAgent rootAgent =
      LlmAgent.builder()
          .name("code_execution_example")
          .model(new Gemini("gemini-3.1-flash-lite"))
          .instruction(
              "You are a data assistant. When a question needs computation, write and run code with"
                  + " the code execution tool, then answer using the result rather than doing"
                  + " arithmetic in your head.")
          .tools(new BuiltInCodeExecutionTool())
          .build();

  private BuiltInCodeExecutionDemoAgentJava() {}
}
