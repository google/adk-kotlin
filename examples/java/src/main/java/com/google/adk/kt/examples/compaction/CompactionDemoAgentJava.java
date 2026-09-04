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

package com.google.adk.kt.examples.compaction;

import com.google.adk.kt.agents.LlmAgent;
import com.google.adk.kt.apps.App;
import com.google.adk.kt.events.Event;
import com.google.adk.kt.events.EventCompaction;
import com.google.adk.kt.interop.AsyncJavaHelpers;
import com.google.adk.kt.interop.BasePublisherModel;
import com.google.adk.kt.interop.PublisherRunner;
import com.google.adk.kt.models.Gemini;
import com.google.adk.kt.models.LlmRequest;
import com.google.adk.kt.models.LlmResponse;
import com.google.adk.kt.models.Model;
import com.google.adk.kt.runners.InMemoryRunner;
import com.google.adk.kt.sessions.Session;
import com.google.adk.kt.sessions.SessionKey;
import com.google.adk.kt.summarizer.EventsCompactionConfig;
import com.google.adk.kt.summarizer.LlmEventSummarizer;
import com.google.adk.kt.types.Content;
import com.google.adk.kt.types.Role;
import java.util.List;
import java.util.Scanner;
import org.reactivestreams.Publisher;

/**
 * Interactive end-to-end demo of sliding-window context compaction.
 *
 * <p>Both the agent's model and the compaction summarizer's model are wrapped in a {@link
 * PrintingModel} that prints every prompt, so you can watch the history grow and then collapse into
 * a single summary once compaction kicks in (configured here to compact every two turns). If {@code
 * GEMINI_API_KEY} (or {@code GOOGLE_API_KEY}) is set it talks to a real Gemini model; otherwise it
 * falls back to canned replies so the behavior is still demonstrable offline. Type {@code exit} (or
 * an empty line) to quit.
 */
public final class CompactionDemoAgentJava {

  private static final String MODEL_NAME = "gemini-3.1-flash-lite";
  private static final String APP_NAME = "compaction_demo";
  private static final String USER_ID = "demo-user";
  private static final String SESSION_ID = "demo-session";

  /** Wraps a model and prints every prompt before delegating, so history growth is visible. */
  private static final class PrintingModel extends BasePublisherModel {
    private final String label;
    private final Model delegate;

    PrintingModel(String label, Model delegate) {
      super(delegate.getName());
      this.label = label;
      this.delegate = delegate;
    }

    @Override
    protected Publisher<LlmResponse> generateContentJava(LlmRequest request, boolean stream) {
      List<Content> contents = request.getContents();
      System.out.println("\n  >>> " + label + " prompt (" + contents.size() + " content(s)):");
      for (int i = 0; i < contents.size(); i++) {
        Content content = contents.get(i);
        String text = content.text(" ");
        System.out.println(
            "        ["
                + i
                + "] "
                + content.getRole()
                + ": "
                + (text.isEmpty() ? "<non-text>" : text));
      }
      return AsyncJavaHelpers.asPublisher(delegate.generateContent(request, stream));
    }
  }

  /** Ignores the prompt and always returns a fixed reply; used when no API key is available. */
  private static final class CannedModel extends BasePublisherModel {
    private final String reply;

    CannedModel(String name, String reply) {
      super(name);
      this.reply = reply;
    }

    @Override
    protected Publisher<LlmResponse> generateContentJava(LlmRequest request, boolean stream) {
      return AsyncJavaHelpers.publisherOf(
          List.of(LlmResponse.builder().content(Content.fromText(Role.MODEL, reply)).build()));
    }
  }

  private static boolean hasApiKey() {
    return !isBlank(System.getenv("GEMINI_API_KEY")) || !isBlank(System.getenv("GOOGLE_API_KEY"));
  }

  private static Model realOrCanned(String cannedName, String cannedReply) {
    return hasApiKey() ? new Gemini(MODEL_NAME) : new CannedModel(cannedName, cannedReply);
  }

  public static void main(String[] args) {
    Model agentModel =
        new PrintingModel("AGENT LLM", realOrCanned("agent", "(canned) Here is an answer."));
    Model summarizerModel =
        new PrintingModel(
            "SUMMARIZER LLM",
            realOrCanned("summarizer", "<<summary of the earlier conversation>>"));

    App app =
        App.builder()
            .appName(APP_NAME)
            .rootAgent(LlmAgent.builder().name("assistant").model(agentModel).build())
            .eventsCompactionConfig(
                // Compact every 2 user invocations, with no overlap, using the LLM summarizer.
                EventsCompactionConfig.builder()
                    .compactionInterval(2)
                    .overlapSize(0)
                    .summarizer(new LlmEventSummarizer(summarizerModel))
                    .build())
            .build();
    InMemoryRunner inMemoryRunner = InMemoryRunner.builder().app(app).build();

    System.out.println(
        "Sliding-window compaction demo. Type a message; 'exit' or an empty line quits.");
    if (!hasApiKey()) {
      System.out.println(
          "(No GEMINI_API_KEY/GOOGLE_API_KEY set -- using canned replies; compaction still"
              + " works.)");
    }

    try (PublisherRunner runner = PublisherRunner.of(inMemoryRunner);
        Scanner scanner = new Scanner(System.in)) {
      while (true) {
        System.out.print("\nYou > ");
        System.out.flush();
        if (!scanner.hasNextLine()) {
          break;
        }
        String input = scanner.nextLine();
        String trimmed = input.trim();
        if (input.isBlank()
            || trimmed.equalsIgnoreCase("exit")
            || trimmed.equalsIgnoreCase("quit")) {
          break;
        }
        AsyncJavaHelpers.forEach(
            runner.runAsync(USER_ID, SESSION_ID, null, Content.fromText(Role.USER, input)),
            event -> {
              String text = event.contentText(" ");
              if (!text.isBlank()) {
                System.out.println("\nassistant > " + text);
              }
            });
      }
    }

    System.out.println(
        "\n========== SESSION EVENTS (raw events are kept; summaries are appended) ==========");
    Session session =
        AsyncJavaHelpers.await(
            c ->
                inMemoryRunner
                    .getSessionService()
                    .getSession(new SessionKey(APP_NAME, USER_ID, SESSION_ID), null, c));
    if (session == null) {
      return;
    }
    List<Event> events = session.getEvents();
    for (int i = 0; i < events.size(); i++) {
      Event event = events.get(i);
      EventCompaction compaction = event.getActions().getCompaction();
      String description;
      if (compaction != null) {
        String summary = compaction.getCompactedContent().text(" ");
        description =
            "COMPACTION SUMMARY covering ["
                + compaction.getStartTimestamp()
                + ".."
                + compaction.getEndTimestamp()
                + "]: "
                + summary;
      } else {
        description = event.getAuthor() + ": " + event.contentText(" ");
      }
      System.out.println("  [" + i + "] " + description);
    }
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  private CompactionDemoAgentJava() {}
}
