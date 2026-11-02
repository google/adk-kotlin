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
import com.google.adk.kt.interop.FunctionNodes;
import com.google.adk.kt.interop.PublisherRunner;
import com.google.adk.kt.models.Gemini;
import com.google.adk.kt.models.Model;
import com.google.adk.kt.models.VertexCredentials;
import com.google.adk.kt.types.Content;
import com.google.adk.kt.types.FunctionCall;
import com.google.adk.kt.types.FunctionResponse;
import com.google.adk.kt.types.Part;
import com.google.adk.kt.types.Role;
import com.google.adk.kt.workflow.FunctionNode;
import com.google.adk.kt.workflow.Node;
import com.google.adk.kt.workflow.RequestInput;
import com.google.adk.kt.workflow.Route;
import com.google.adk.kt.workflow.Workflow;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Flux;

/**
 * A port of adk-python's {@code request_input_rerun} workflow sample: the {@code request_input}
 * sample with one review node instead of two. The node sets {@code rerunOnResume(true)}, so it runs
 * again when the human answers and reads the answer from its context.
 */
public final class RequestInputRerunWorkflowJava {

  private static final String MODEL_NAME = "gemini-3.8-flash";

  /** Builds the workflow, with the drafting agent on {@code model}. */
  public static Workflow create(Model model) {
    Node draftEmail = draftEmailAgent(model);

    return Workflow.builder()
        .name("request_input_rerun")
        .edges(
            g ->
                g.fromStart()
                    .chain(processInput(), draftEmail, humanReview())
                    .route(
                        r -> {
                          r.on("revise").then(draftEmail);
                          r.on("approved").then(sendEmail());
                          r.on("rejected").then(rejectEmail());
                        }))
        .build();
  }

  /**
   * Runs the workflow on one complaint, asks for a revision, and approves the revised draft,
   * printing each draft and question. Needs {@code GOOGLE_CLOUD_PROJECT} and Application Default
   * Credentials.
   */
  public static void main(String[] args) {
    Workflow workflow =
        create(new Gemini(MODEL_NAME, VertexCredentials.builder().location("global").build()));
    PublisherRunner runner =
        PublisherRunner.inMemory(
            App.builder().appName("request_input_rerun").rootNode(workflow).build());
    String interruptId = send(runner, Content.fromText(Role.USER, "The delivery was a week late"));
    // Each answer reruns the review node, which asks again after a revision.
    for (String answer :
        List.of("Make it shorter and offer a discount on the next order.", "approve")) {
      if (interruptId == null) {
        break;
      }
      interruptId = send(runner, reviewAnswer(interruptId, answer));
    }
  }

  /**
   * Sends {@code message}, prints the messages and the question the workflow sends back, and
   * returns the id of the review request the turn ends with, or null if it ends without one.
   */
  private static String send(PublisherRunner runner, Content message) {
    String interruptId = null;
    for (Event event : Flux.from(runner.runAsync("user", "session", null, message)).toIterable()) {
      String text = event.contentText(" ");
      if (!text.isBlank()) {
        System.out.println("[" + event.getAuthor() + "] " + text);
      }
      for (FunctionCall call : event.functionCalls()) {
        if (call.getName().equals(RequestInput.FUNCTION_CALL_NAME)) {
          System.out.println("[" + event.getAuthor() + "] asks: " + call.getArgs().get("message"));
          interruptId = call.getId();
        }
      }
    }
    return interruptId;
  }

  /** The reviewer's {@code answer} to the review request {@code interruptId}. */
  private static Content reviewAnswer(String interruptId, String answer) {
    FunctionResponse response =
        FunctionResponse.builder()
            .name(RequestInput.FUNCTION_CALL_NAME)
            .id(interruptId)
            .response(Map.of("result", answer))
            .build();
    return new Content(Role.USER, List.of(Part.builder().functionResponse(response).build()));
  }

  /**
   * Stores the complaint in state, where the agent's instruction reads it, with no feedback yet.
   */
  private static FunctionNode<Content, Object> processInput() {
    return FunctionNodes.of(
        "process_input",
        // START hands the first node the user's message as Content.
        Content.class,
        Object.class,
        (ctx, message) -> {
          ctx.updateState("complaint", message.text());
          ctx.updateState("feedback", "");
          return null;
        });
  }

  private static LlmAgent draftEmailAgent(Model model) {
    return LlmAgent.builder()
        .name("draft_email")
        .model(model)
        .instruction(
            """
            Please write a polite, helpful response email to the following customer complaint:
            "{complaint}"

            If there is any feedback from the manager to revise the draft, please incorporate it:
            "{feedback?}"\
            """)
        .outputKey("draft")
        .build();
  }

  /**
   * Asks a human to review the draft on its first run, and routes on the answer when it runs again.
   */
  private static FunctionNode<String, Object> humanReview() {
    return FunctionNodes.builder(String.class, Object.class)
        .name("human_review")
        .rerunOnResume(true)
        .body(
            (ctx, draft) -> {
              // The id changes per turn and loop round, and stays the same on a rerun.
              String interruptId = "human_review_" + ctx.getInvocationId() + "_" + ctx.getRunId();
              Object answer = ctx.getResumeInputs().get(interruptId);
              // An empty answer asks again, as in adk-python.
              if (answer == null || "".equals(answer)) {
                return FunctionNodes.requestInput(
                    interruptId,
                    "Please review the following draft email and provide 'approve', 'reject', or"
                        + " feedback to revise.\n\n---\n"
                        + draft
                        + "\n---");
              }
              String route =
                  switch (answer.toString()) {
                    case "reject" -> "rejected";
                    case "approve" -> "approved";
                    default -> {
                      ctx.updateState("feedback", answer);
                      yield "revise";
                    }
                  };
              ctx.setRoutes(List.of(new Route.Tag(route)));
              return null;
            })
        .build();
  }

  private static FunctionNode<Object, Event> rejectEmail() {
    return FunctionNodes.of(
        "reject_email", Object.class, Event.class, (ctx, input) -> message("Draft rejected."));
  }

  private static FunctionNode<Object, Event> sendEmail() {
    return FunctionNodes.of(
        "send_email",
        Object.class,
        Event.class,
        (ctx, input) -> message("Draft approved and sent successfully."));
  }

  private static Event message(String text) {
    return Event.builder().content(Content.fromText(Role.MODEL, text)).build();
  }

  private RequestInputRerunWorkflowJava() {}
}
