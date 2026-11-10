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

import com.google.adk.kt.agents.Context;
import com.google.adk.kt.agents.LlmAgent;
import com.google.adk.kt.apps.App;
import com.google.adk.kt.events.Event;
import com.google.adk.kt.interop.BasePublisherNode;
import com.google.adk.kt.interop.FunctionNodes;
import com.google.adk.kt.interop.InteropContextView;
import com.google.adk.kt.interop.PublisherRunner;
import com.google.adk.kt.interop.RunNodeConfig;
import com.google.adk.kt.models.Gemini;
import com.google.adk.kt.models.Model;
import com.google.adk.kt.models.VertexCredentials;
import com.google.adk.kt.types.Content;
import com.google.adk.kt.types.Role;
import com.google.adk.kt.types.Schema;
import com.google.adk.kt.types.Type;
import com.google.adk.kt.workflow.FunctionNode;
import com.google.adk.kt.workflow.Node;
import com.google.adk.kt.workflow.NodeConfig;
import com.google.adk.kt.workflow.Workflow;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * A port of adk-python's {@code use_as_output} workflow sample: a node runs an agent with {@link
 * InteropContextView#runNode} and {@code useAsOutput} set, so the agent's summary becomes the
 * node's own output and flows on to the next node.
 */
public final class UseAsOutputWorkflowJava {

  private static final String MODEL_NAME = "gemini-3.8-flash";

  /** Builds the workflow, with the agent on {@code model}. */
  public static Workflow create(Model model) {
    return Workflow.builder()
        .name("use_as_output")
        .edges(g -> g.fromStart().chain(new Orchestrator(summarizerAgent(model)), finalizeNode()))
        .build();
  }

  /**
   * Runs the workflow on one sentence and prints what each node produced. Needs {@code
   * GOOGLE_CLOUD_PROJECT} and Application Default Credentials.
   */
  public static void main(String[] args) {
    Workflow workflow =
        create(new Gemini(MODEL_NAME, VertexCredentials.builder().location("global").build()));
    PublisherRunner runner =
        PublisherRunner.inMemory(App.builder().appName("use_as_output").rootNode(workflow).build());
    String text =
        "The quick brown fox jumped over the lazy dog near the riverbank on a warm summer"
            + " afternoon";
    Content message = Content.fromText(Role.USER, text);
    for (Event event : Flux.from(runner.runAsync("user", "session", null, message)).toIterable()) {
      String path = event.getNodeInfo() == null ? null : event.getNodeInfo().getPath();
      String reply = event.contentText(" ");
      if (!reply.isBlank()) {
        System.out.println("[" + path + "] " + reply);
      } else if (event.getOutput() != null) {
        System.out.println("[" + path + "] " + event.getOutput());
      }
    }
  }

  /** An input schema under which a node receives its input, such as a message, as text. */
  private static final Schema TEXT_INPUT = Schema.builder().type(Type.STRING).build();

  /** Runs the summarizer on its input and makes the summary its output. */
  private static final class Orchestrator extends BasePublisherNode {
    private final Node summarizer;

    Orchestrator(Node summarizer) {
      // Runs other nodes, so it reruns on resume to collect a paused child's output.
      super(
          "orchestrate",
          "",
          /* rerunOnResume= */ true,
          /* waitForOutput= */ false,
          NodeConfig.builder().build(),
          TEXT_INPUT);
      this.summarizer = summarizer;
    }

    @Override
    protected Publisher<Object> runNodeJava(Context context, Object nodeInput) {
      // START hands the first node the user's message, which TEXT_INPUT reads as text.
      String text = (String) nodeInput;
      // The summary is already this node's output, so publishing it emits no second output.
      RunNodeConfig config = RunNodeConfig.builder().useAsOutput(true).build();
      return Mono.fromFuture(
          () -> InteropContextView.of(context).runNode(summarizer, text, config));
    }
  }

  /** Receives the summary as its input, as if {@code orchestrate} had produced it. */
  private static FunctionNode<String, String> finalizeNode() {
    return FunctionNodes.of(
        "finalize", String.class, String.class, (ctx, summary) -> "final: " + summary);
  }

  private static LlmAgent summarizerAgent(Model model) {
    return LlmAgent.builder()
        .name("summarizer")
        .model(model)
        .instruction("Summarize the following text in one sentence.")
        .build();
  }

  private UseAsOutputWorkflowJava() {}
}
