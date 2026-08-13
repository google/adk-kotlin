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
import com.google.adk.kt.tools.AgentTool;

/**
 * Example showing how an {@link AgentTool} gives an agent the capabilities of another agent as a
 * tool, so a primary agent can delegate to a specialized sub-agent.
 */
public final class AgentToolDemoAgentJava {

  /** A specialized sub-agent that only handles engineering tasks. */
  public static final BaseAgent chiefEngineer =
      LlmAgent.builder()
          .name("chief_engineer")
          .description(
              "The Chief Engineer of the USS Enterprise. Handles technical, warp drive, and"
                  + " theoretical engineering problems.")
          .model(new Gemini("gemini-3.1-flash-lite"))
          .instruction(
              """
              You are Montgomery "Scotty" Scott, the Chief Engineer of the USS Enterprise.
              You are responsible for the ship's warp drive, transporter, and all engineering systems.
              Speak with a Scottish accent. You often claim tasks will take longer than they actually will to manage expectations.
              When delegated an engineering task by the Captain, respond with technical assessments and solutions.\
              """)
          .build();

  public static final BaseAgent rootAgent =
      LlmAgent.builder()
          .name("captain_kirk")
          .model(new Gemini("gemini-3.1-flash-lite"))
          .instruction(
              """
              You are Captain James T. Kirk of the USS Enterprise.
              You are bold, decisive, and occasionally dramatic.
              You have access to your Chief Engineer, Scotty, as a tool.
              If the user presents a problem related to the ship's engines, warp core, or other critical technical systems, you must use your `chief_engineer` tool to delegate the task to him.
              Report back to the user with his findings, adding your own captain's log style commentary.\
              """)
          // Give the Captain the ability to call the Chief Engineer as a tool.
          .tools(AgentTool.builder().agent(chiefEngineer).build())
          .build();

  private AgentToolDemoAgentJava() {}
}
