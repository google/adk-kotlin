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

package com.google.adk.kt.examples.memory;

import com.google.adk.kt.agents.BaseAgent;
import com.google.adk.kt.agents.LlmAgent;
import com.google.adk.kt.callbacks.AfterAgentCallback;
import com.google.adk.kt.callbacks.BeforeModelCallback;
import com.google.adk.kt.interop.AsyncJavaHelpers;
import com.google.adk.kt.interop.Choices;
import com.google.adk.kt.interop.FutureCallbacks;
import com.google.adk.kt.interop.PublisherRunner;
import com.google.adk.kt.memory.MemoryService;
import com.google.adk.kt.memory.VertexAiMemoryBankService;
import com.google.adk.kt.models.Gemini;
import com.google.adk.kt.models.VertexCredentials;
import com.google.adk.kt.runners.InMemoryRunner;
import com.google.adk.kt.sessions.Session;
import com.google.adk.kt.sessions.SessionKey;
import com.google.adk.kt.tools.PreloadMemoryTool;
import com.google.adk.kt.types.Content;
import com.google.adk.kt.types.Role;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Java port of the standard-ADK example of using {@link VertexAiMemoryBankService} with an agent.
 *
 * <p>The memory service is wired into the {@link InMemoryRunner}, and the agent uses it from both
 * ends: {@link PreloadMemoryTool} injects relevant memories into the prompt at the start of each
 * turn, and an after-agent callback calls {@code CallbackContext.addSessionToMemory} to write the
 * conversation back for recall in future sessions.
 *
 * <p>The suspending {@code addSessionToMemory} is called from Java through {@link
 * AsyncJavaHelpers#await}, run inside the callback's returned future so the engine thread is not
 * blocked.
 *
 * <p>Authentication uses Application Default Credentials ({@code gcloud auth application-default
 * login}).
 *
 * <p>Environment variables:
 *
 * <ul>
 *   <li>{@code GOOGLE_CLOUD_PROJECT} - GCP project id.
 *   <li>{@code VERTEX_AGENT_ENGINE_ID} - the numeric Agent Engine id that owns the memories.
 *   <li>{@code GOOGLE_CLOUD_LOCATION} - Vertex region for the Agent Engine (optional, defaults to
 *       {@code us-central1}).
 *   <li>{@code GOOGLE_CLOUD_MODEL_LOCATION} - Vertex region for the model (optional, defaults to
 *       {@code global}).
 * </ul>
 */
public final class VertexAiMemoryBankServiceExampleJava {

  private static final String MODEL_NAME = "gemini-3.1-flash-lite";
  private static final String APP_NAME = "memory-bank-example";
  private static final String USER_ID = "demo-user";

  /** Full message: states Alex's preferences, so this turn seeds memory for later recall. */
  private static final String SEED_MESSAGE =
      "Hi! I'm Alex, I'm vegetarian, and I love hiking. What should I have for dinner?";

  /** Greeting only: anything the model brings up must come from memory. */
  private static final String RECALL_MESSAGE = "Hi! I'm Alex. What should I have for dinner?";

  public static void main(String[] args) {
    String project = requireEnv("GOOGLE_CLOUD_PROJECT");
    String agentEngineId = requireEnv("VERTEX_AGENT_ENGINE_ID");
    String location = envOrDefault("GOOGLE_CLOUD_LOCATION", "us-central1");
    String modelLocation = envOrDefault("GOOGLE_CLOUD_MODEL_LOCATION", "global");

    MemoryService memoryService =
        VertexAiMemoryBankService.builder()
            .project(project)
            .location(location)
            .agentEngineId(agentEngineId)
            .build();

    BaseAgent agent =
        LlmAgent.builder()
            .name("memory_agent")
            .description(
                "A helpful assistant that remembers user preferences across conversations.")
            .model(new Gemini(MODEL_NAME, new VertexCredentials(project, modelLocation, null)))
            .instruction(
                "You are a helpful assistant. You remember user preferences and facts from previous"
                    + " conversations and use them to personalize your responses. Answer briefly.")
            .tools(new PreloadMemoryTool())
            .beforeModelCallbacks(List.of(reportMemoryInjection()))
            .afterAgentCallbacks(List.of(storeSessionToMemory()))
            .build();

    InMemoryRunner inMemoryRunner =
        InMemoryRunner.builder()
            .agent(agent)
            .appName(APP_NAME)
            .memoryService(memoryService)
            .build();
    Session created =
        AsyncJavaHelpers.await(
            c ->
                inMemoryRunner
                    .getSessionService()
                    .createSession(new SessionKey(APP_NAME, USER_ID, null), null, c));
    String sessionId = Objects.requireNonNull(created.getKey().getId());
    PublisherRunner runner = PublisherRunner.of(inMemoryRunner);

    String message =
        args.length > 0 && args[0].equalsIgnoreCase("short") ? RECALL_MESSAGE : SEED_MESSAGE;
    System.out.println("user > " + message);
    AsyncJavaHelpers.forEach(
        runner.runAsync(USER_ID, sessionId, null, Content.fromText(Role.USER, message)),
        event -> {
          if (event.getPartial()) {
            return;
          }
          String text = event.contentText(" ");
          if (!text.isBlank()) {
            System.out.println(event.getAuthor() + " > " + text);
          }
        });

    System.out.println(
        "\n"
            + "The after-agent callback stored this turn to the Memory Bank. Run the example again"
            + " (after the memory is generated) with the `short` argument to watch"
            + " PreloadMemoryTool recall it in a new session.");
    runner.close();
    System.exit(0);
  }

  /** Reports whether PreloadMemoryTool injected recalled memories into this turn's prompt. */
  private static BeforeModelCallback reportMemoryInjection() {
    return FutureCallbacks.beforeModel(
        (context, request) -> {
          Content systemInstruction = request.getConfig().getSystemInstruction();
          String systemText = systemInstruction == null ? "" : systemInstruction.text("\n");
          System.out.println(
              "[memory] injected past conversations: "
                  + systemText.contains("<PAST_CONVERSATIONS>"));
          return CompletableFuture.completedFuture(Choices.proceed(request));
        });
  }

  /** Writes the finished turn back to memory; the suspend call runs inside the returned future. */
  private static AfterAgentCallback storeSessionToMemory() {
    return FutureCallbacks.afterAgent(
        context ->
            CompletableFuture.supplyAsync(
                () -> {
                  var unused = AsyncJavaHelpers.await(c -> context.addSessionToMemory(c));
                  return Choices.<Content>proceed();
                }));
  }

  private static String requireEnv(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("Environment variable " + name + " is not set.");
    }
    return value;
  }

  private static String envOrDefault(String name, String fallback) {
    String value = System.getenv(name);
    return value == null || value.isBlank() ? fallback : value;
  }

  private VertexAiMemoryBankServiceExampleJava() {}
}
