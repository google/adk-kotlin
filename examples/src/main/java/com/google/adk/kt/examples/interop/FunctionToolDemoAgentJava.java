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
import com.google.adk.kt.annotations.Param;
import com.google.adk.kt.annotations.Tool;
import com.google.adk.kt.models.Gemini;
import com.google.adk.kt.models.VertexCredentials;
import com.google.adk.kt.tools.ToolContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Java port of {@code FunctionToolDemoAgent.kt} exposing {@code @Tool} methods from a pure-Java
 * service. It sits in the Kotlin examples module because {@code @Tool} runs through KSP at compile
 * time; the sample's data classes are modeled as {@link Map}s.
 */
public final class FunctionToolDemoAgentJava {

  /** Tea status enum, ported directly from the Kotlin enum. */
  public enum TeaStatus {
    HOT,
    COLD,
    NOT_AVAILABLE,
    NEARLY_BUT_NOT_QUITE_ENTIRELY_UNLIKE_TEA,
  }

  /** A mock service whose {@code @Tool}-annotated methods are exposed to the LLM. */
  public static final class HitchhikersGuideService {

    @Tool(
        description =
            "Retrieves the Answer to the Ultimate Question of Life, the Universe, and Everything.")
    public String getAnswerToEverything(
        @Param(
                description =
                    "The question to ask Deep Thought, e.g., 'What is the answer to life?'")
            String question) {
      System.out.println(">>> Deep Thought [JAVA]: Calculating answer for '" + question + "'...");
      String q = question.toLowerCase(Locale.ROOT);
      if (q.contains("life") && q.contains("universe")) {
        return "The answer to the Ultimate Question of Life, the Universe, and Everything is 42.";
      }
      return "I don't know that. I only know the answer to the Ultimate Question.";
    }

    @Tool(description = "Calculates the improbability of a given event.")
    public String calculateImprobability(
        @Param(description = "The event, e.g. 'A cup of tea materializing'") String event,
        @Param(description = "Desired level of improbability") Double level) {
      System.out.println(
          ">>> Improbability Drive [JAVA]: Engaging for " + event + " at level " + level + "...");
      double improbability = ThreadLocalRandom.current().nextDouble() * 1000;
      return "The improbability of '" + event + "' is approximately " + improbability + " to 1.";
    }

    /** Uses {@link Map}s where the Kotlin sample used data classes. */
    @Tool(description = "Gets the status of the Infinite Improbability Drive at given coordinates.")
    public Map<String, Object> getDriveStatus(
        @Param(description = "Galactic coordinates as an object with numeric x, y, z, time")
            Map<String, Object> coordinates) {
      System.out.println(">>> Heart of Gold [JAVA]: Checking drive status at " + coordinates + ".");
      List<String> sideEffects = new ArrayList<>();
      sideEffects.add("Whales and petunias materializing");
      sideEffects.add("Reality alteration");
      Map<String, Object> report = new LinkedHashMap<>();
      report.put("locationName", "Sector ZZ9 Plural Z Alpha");
      report.put("improbabilityLevel", ThreadLocalRandom.current().nextDouble() * 1e6);
      report.put("sideEffects", sideEffects);
      report.put("teaStatus", TeaStatus.NEARLY_BUT_NOT_QUITE_ENTIRELY_UNLIKE_TEA.name());
      return report;
    }

    @Tool(description = "Gets bulk guide entries. Demonstrates a List parameter and Map return.")
    public Map<String, Object> getBulkGuideEntries(
        @Param(description = "List of guide entries to look up") List<String> entries) {
      System.out.println(">>> The Guide [JAVA]: Looking up bulk entries for " + entries + "...");
      Map<String, Object> result = new LinkedHashMap<>();
      for (String entry : entries) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("locationName", entry);
        report.put("improbabilityLevel", 42.0);
        report.put("sideEffects", new ArrayList<String>());
        report.put("teaStatus", TeaStatus.NOT_AVAILABLE.name());
        result.put(entry, report);
      }
      return result;
    }

    @Tool(
        description = "Submits a request for tea. Demonstrates context injection and enum params.")
    public String submitTeaRequest(
        ToolContext context,
        @Param(description = "The person requesting tea") String requester,
        @Param(description = "The desired status of the tea") TeaStatus status) {
      System.out.println(
          ">>> Nutri-Matic [JAVA]: Submitting "
              + status
              + " tea for "
              + requester
              + "... (Call ID: "
              + context.getFunctionCallId()
              + ")");
      return "Successfully submitted request for " + status + " tea for " + requester + ".";
    }

    @Tool(description = "Retrieves an entry from The Hitchhiker's Guide for a specific edition.")
    public String getHistoricalGuideEntry(
        @Param(description = "The name of the entry (e.g. 'Babel Fish')") String entryName,
        @Param(description = "The edition of the guide (e.g. 'Standard', 'Premium')")
            String edition) {
      System.out.println(
          ">>> The Guide [JAVA]: Looking up " + entryName + " in the " + edition + " edition...");
      return switch (entryName.toLowerCase(Locale.ROOT)) {
        case "babel fish" ->
            "The Babel fish is small, yellow, and leech-like. (Edition: " + edition + ")";
        case "vogon" ->
            "Vogons are one of the most unpleasant races in the Galaxy. (Edition: " + edition + ")";
        default ->
            "Entry for '" + entryName + "' not found. Mostly harmless. (Edition: " + edition + ")";
      };
    }
  }

  public static final BaseAgent rootAgent =
      LlmAgent.builder()
          .name("hitchhikers_guide_bot")
          .model(
              new Gemini(
                  "gemini-3.8-flash", VertexCredentials.builder().location("global").build()))
          .instruction(
              """
              You are a helpful assistant themed around "The Hitchhiker's Guide to the Galaxy".
              Use the available tools as requested to showcase their capabilities.
              Be witty, slightly sarcastic, and concise, in the style of the Guide. Don't Panic.\
              """)
          // KSP-generated accessor; a static *Kt method from Java.
          .tools(
              FunctionToolDemoAgentJava_HitchhikersGuideService_GeneratedToolsKt.generatedTools(
                  new HitchhikersGuideService()))
          .build();

  private FunctionToolDemoAgentJava() {}
}
