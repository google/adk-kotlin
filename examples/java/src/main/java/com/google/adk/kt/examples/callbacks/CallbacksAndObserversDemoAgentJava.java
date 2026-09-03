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

package com.google.adk.kt.examples.callbacks;

import com.google.adk.kt.agents.BaseAgent;
import com.google.adk.kt.agents.LlmAgent;
import com.google.adk.kt.annotations.Tool;
import com.google.adk.kt.callbacks.BeforeModelCallback;
import com.google.adk.kt.callbacks.BeforeToolCallback;
import com.google.adk.kt.interop.Choices;
import com.google.adk.kt.interop.FutureCallbacks;
import com.google.adk.kt.interop.ReflectiveTools;
import com.google.adk.kt.models.Gemini;
import com.google.adk.kt.tools.BaseTool;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Whimsical demo agent "Alice in Processorland" demonstrating before-model and before-tool
 * callbacks wired from Java via {@link FutureCallbacks}.
 *
 * <p>The tools are built from annotated methods by {@link ReflectiveTools} (used here because this
 * example is compiled with javac, not the Kotlin compiler; prefer the KSP {@code @Tool} path when
 * available).
 */
public final class CallbacksAndObserversDemoAgentJava {

  /** The agent's tools, built from annotated methods by {@link ReflectiveTools}. */
  static final class AliceTools {
    @Tool(name = "drink_me", description = "Shrinks the user so they can fit through small doors.")
    public Map<String, Object> drinkMe() {
      System.out.println(
          ">>> [SYSTEM]: *Gulp gulp gulp*... You feel yourself getting smaller and smaller!");
      return Map.of("result", "You are now 10 inches tall. Perfect for tiny doors!");
    }

    @Tool(name = "eat_me", description = "Grows the user so they can reach high shelves.")
    public Map<String, Object> eatMe() {
      System.out.println(">>> [SYSTEM]: *Nom nom nom*... Your head is hitting the ceiling!");
      return Map.of("result", "You are now 10 feet tall. You can reach the glass table!");
    }
  }

  /** Logs a Cheshire Cat grin before the model runs, then continues unchanged. */
  private static BeforeModelCallback cheshireCat() {
    return FutureCallbacks.beforeModel(
        (context, request) -> {
          System.out.println(">>> [CALLBACK]: Cheshire Cat grin appears... 'We're all mad here.'");
          return CompletableFuture.completedFuture(Choices.proceed(request));
        });
  }

  /** Warns about being late before a tool is called, then continues unchanged. */
  private static BeforeToolCallback whiteRabbit() {
    return FutureCallbacks.beforeTool(
        (context, tool, args) -> {
          System.out.println(
              ">>> [CALLBACK]: White Rabbit checks his watch... 'Oh dear! Oh dear! I shall be too"
                  + " late!' (Executing "
                  + tool.getName()
                  + ")");
          return CompletableFuture.completedFuture(Choices.proceed(args));
        });
  }

  private static final AliceTools TOOLS = new AliceTools();

  // Reflection is costly, so each tool is built once; prefer the KSP @Tool path when the Kotlin
  // compiler is available (see
  // examples/src/main/java/com/google/adk/kt/examples/interop/FunctionToolDemoAgentJava.java).
  private static final BaseTool DRINK_ME = ReflectiveTools.fromMethod(TOOLS, "drinkMe");
  private static final BaseTool EAT_ME = ReflectiveTools.fromMethod(TOOLS, "eatMe");

  public static final BaseAgent rootAgent =
      LlmAgent.builder()
          .name("alice")
          .model(new Gemini("gemini-3.1-flash-lite"))
          .instruction(
              """
              You are Alice, exploring a very strange and nonsensical digital Wonderland.
              You have found two items: a potion labeled "DRINK ME" and a cake labeled "EAT ME".
              When the user asks you to interact with the environment, try using your tools to see what happens.
              Speak with a sense of wonder and slight confusion.\
              """)
          .tools(DRINK_ME, EAT_ME)
          .beforeModelCallbacks(List.of(cheshireCat()))
          .beforeToolCallbacks(List.of(whiteRabbit()))
          .build();

  private CallbacksAndObserversDemoAgentJava() {}
}
