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

package com.google.adk.kt.examples.interop;

import com.google.adk.kt.agents.BaseAgent;
import com.google.adk.kt.agents.LlmAgent;
import com.google.adk.kt.agents.ReadonlyContext;
import com.google.adk.kt.events.Event;
import com.google.adk.kt.interop.AsyncJavaHelpers;
import com.google.adk.kt.interop.BaseFutureTool;
import com.google.adk.kt.interop.BaseFutureToolset;
import com.google.adk.kt.interop.BasePublisherModel;
import com.google.adk.kt.interop.PublisherRunner;
import com.google.adk.kt.models.LlmRequest;
import com.google.adk.kt.models.LlmResponse;
import com.google.adk.kt.runners.InMemoryRunner;
import com.google.adk.kt.tools.BaseTool;
import com.google.adk.kt.tools.ToolContext;
import com.google.adk.kt.types.Content;
import com.google.adk.kt.types.FunctionDeclaration;
import com.google.adk.kt.types.Role;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.reactivestreams.Publisher;

/**
 * Demonstrates implementing a {@link com.google.adk.kt.tools.Toolset} from Java via {@link
 * BaseFutureToolset} - a dynamically resolved group of tools. The engine's {@code getTools} is
 * {@code suspend}; the base asks for a {@link CompletableFuture} instead.
 *
 * <p>{@code main} shows the typical usage: the toolset is configured on an agent via {@code
 * LlmAgent.builder().toolsets(...)}, and the runner resolves it (calls {@code getTools}) when
 * building each model request. Runs offline against a fake model.
 */
public final class FutureToolsetDemoJava {

  /**
   * A tiny future-based tool the toolset hands out. Hand-written as a {@link BaseFutureTool}
   * because this module is compiled by javac; with the Kotlin toolchain (KSP), the recommended
   * approach is instead the {@code @Tool} annotation shown in
   * examples/src/main/java/com/google/adk/kt/examples/interop/FunctionToolDemoAgentJava.java.
   */
  private static final class EchoTool extends BaseFutureTool {
    EchoTool(String name, String description) {
      super(name, description);
    }

    @Override
    public FunctionDeclaration declaration() {
      return FunctionDeclaration.builder().name(getName()).description(getDescription()).build();
    }

    @Override
    protected CompletableFuture<Object> runAsync(ToolContext context, Map<String, Object> args) {
      return CompletableFuture.completedFuture(Map.of("result", "handled by " + getName()));
    }
  }

  /** Resolves to a fixed set of tools. A real toolset might read them from an MCP server. */
  private static final class DemoToolset extends BaseFutureToolset {
    @Override
    protected CompletableFuture<List<BaseTool>> getToolsAsync(ReadonlyContext readonlyContext) {
      List<BaseTool> tools =
          List.of(
              new EchoTool("get_weather", "Returns the weather for a city."),
              new EchoTool("get_time", "Returns the current time."));
      System.out.println("[toolset] runner resolved " + tools.size() + " tool(s)");
      return CompletableFuture.completedFuture(tools);
    }
  }

  /** A minimal offline model returning a fixed reply, so the run needs no API key. */
  private static final class EchoModel extends BasePublisherModel {
    EchoModel() {
      super("echo-model");
    }

    @Override
    protected Publisher<LlmResponse> generateContentJava(LlmRequest request, boolean stream) {
      return AsyncJavaHelpers.publisherOf(
          List.of(LlmResponse.builder().content(Content.fromText(Role.MODEL, "Done.")).build()));
    }
  }

  public static void main(String[] args) {
    // Configure the toolset on the agent; the runner resolves it when building each model request.
    BaseAgent agent =
        LlmAgent.builder()
            .name("toolset_agent")
            .model(new EchoModel())
            .instruction("Use the available tools.")
            .toolsets(List.of(new DemoToolset()))
            .build();
    PublisherRunner runner =
        PublisherRunner.of(
            InMemoryRunner.builder().agent(agent).appName("FutureToolsetDemo").build());

    List<Event> events = new ArrayList<>();
    AsyncJavaHelpers.forEach(
        runner.runAsync("demo-user", "demo-session", null, Content.fromText(Role.USER, "Hi!")),
        events::add);
    System.out.println("run produced " + events.size() + " event(s)");
  }

  private FutureToolsetDemoJava() {}
}
