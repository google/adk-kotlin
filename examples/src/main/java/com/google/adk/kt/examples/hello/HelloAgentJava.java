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

package com.google.adk.kt.examples.hello;

import com.google.adk.kt.agents.BaseAgent;
import com.google.adk.kt.agents.LlmAgent;
import com.google.adk.kt.models.Gemini;

/** Example hello agent, demonstrating the fundamentals of building an agent with the ADK. */
public final class HelloAgentJava {

  public static final BaseAgent rootAgent =
      LlmAgent.builder()
          .name("hello_agent")
          .model(new Gemini("gemini-3.1-flash-lite"))
          // The instruction defines the agent's persona and primary behavior.
          .instruction("You always greet the user with \"Hello\" and try to solve math problems.")
          .build();

  private HelloAgentJava() {}
}
