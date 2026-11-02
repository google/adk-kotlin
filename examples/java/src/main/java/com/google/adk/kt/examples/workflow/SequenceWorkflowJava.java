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

package com.google.adk.kt.examples.workflow;

import com.google.adk.kt.agents.LlmAgent;
import com.google.adk.kt.apps.App;
import com.google.adk.kt.events.Event;
import com.google.adk.kt.interop.PublisherRunner;
import com.google.adk.kt.models.Gemini;
import com.google.adk.kt.models.Model;
import com.google.adk.kt.models.VertexCredentials;
import com.google.adk.kt.types.Content;
import com.google.adk.kt.types.Role;
import com.google.adk.kt.workflow.Workflow;
import reactor.core.publisher.Flux;

/**
 * A port of adk-python's {@code sequence} workflow sample: one agent names a random fruit, and the
 * next agent receives that answer as its input and tells a health benefit of the fruit.
 */
public final class SequenceWorkflowJava {

  private static final String MODEL_NAME = "gemini-3.8-flash";

  /** Builds the workflow, with both agents on {@code model}. */
  public static Workflow create(Model model) {
    return Workflow.builder()
        .name("sequence")
        .edges(g -> g.fromStart().chain(generateFruitAgent(model), generateBenefitAgent(model)))
        .build();
  }

  /**
   * Runs the workflow once and prints each agent's answer. Needs {@code GOOGLE_CLOUD_PROJECT} and
   * Application Default Credentials.
   */
  public static void main(String[] args) {
    Workflow workflow =
        create(new Gemini(MODEL_NAME, VertexCredentials.builder().location("global").build()));
    PublisherRunner runner =
        PublisherRunner.inMemory(App.builder().appName("sequence").rootNode(workflow).build());
    // The workflow needs no input, so any message starts it.
    Content message = Content.fromText(Role.USER, "go");
    for (Event event : Flux.from(runner.runAsync("user", "session", null, message)).toIterable()) {
      String text = event.contentText(" ");
      if (!text.isBlank()) {
        System.out.println("[" + event.getAuthor() + "] " + text);
      }
    }
  }

  private static LlmAgent generateFruitAgent(Model model) {
    return LlmAgent.builder()
        .name("generate_fruit_agent")
        .model(model)
        .instruction(
            """
            Return the name of a random fruit.
            Return only the name, nothing else.\
            """)
        .build();
  }

  private static LlmAgent generateBenefitAgent(Model model) {
    return LlmAgent.builder()
        .name("generate_benefit_agent")
        .model(model)
        .instruction("Tell me a health benefit about the specified fruit.")
        .build();
  }

  private SequenceWorkflowJava() {}
}
