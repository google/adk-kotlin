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
import com.google.adk.kt.tools.BaseTool;
import com.google.adk.kt.tools.BlockingTool;
import com.google.adk.kt.tools.ToolContext;
import com.google.adk.kt.types.FunctionDeclaration;
import com.google.adk.kt.types.Schema;
import com.google.adk.kt.types.Type;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Example agent demonstrating how to use Function Tools in the Kotlin ADK.
 *
 * <p>This example showcases:
 *
 * <ol>
 *   <li>Defining tools that simulate The Hitchhiker's Guide to the Galaxy.
 *   <li>Equipping an agent with those tools.
 *   <li>A zero-reflection approach to function tool execution.
 * </ol>
 */
public final class FunctionToolDemoAgentJava {

  public static final BaseAgent rootAgent =
      LlmAgent.builder()
          .name("hitchhikers_guide_bot")
          .model(new Gemini("gemini-3.1-flash-lite"))
          .instruction(
              """
              You are a helpful assistant bot themed around "The Hitchhiker's Guide to the Galaxy".
              You have access to functions simulating guide entries, improbability calculations, tea requests, and more.
              Please use and test out these various tools as requested to showcase their capabilities.
              Be witty, slightly sarcastic, and concise, in the style of the Guide. Don't Panic.\
              """)
          .tools(
              new GetAnswerToEverythingTool(),
              new CalculateImprobabilityTool(),
              new GetDriveStatusTool(),
              new GetBulkGuideEntriesTool(),
              new SubmitTeaRequestTool(),
              new GetHistoricalGuideEntryTool())
          .build();

  private enum TeaStatus {
    HOT,
    COLD,
    NOT_AVAILABLE,
    NEARLY_BUT_NOT_QUITE_ENTIRELY_UNLIKE_TEA
  }

  /** Retrieves the Answer to the Ultimate Question of Life, the Universe, and Everything. */
  private static final class GetAnswerToEverythingTool extends BlockingTool {

    GetAnswerToEverythingTool() {
      super(
          "getAnswerToEverything",
          "Retrieves the Answer to the Ultimate Question of Life, the Universe, and Everything.");
    }

    @Override
    public FunctionDeclaration declaration() {
      return FunctionDeclaration.builder()
          .name("getAnswerToEverything")
          .description(
              "Retrieves the Answer to the Ultimate Question of Life, the Universe, and"
                  + " Everything.")
          .parameters(
              Schema.builder()
                  .type(Type.OBJECT)
                  .properties(
                      Map.of(
                          "question",
                          Schema.builder()
                              .type(Type.STRING)
                              .description(
                                  "The question to ask Deep Thought, e.g., 'What is the answer to"
                                      + " life, the universe, and everything?'")
                              .build()))
                  .required("question")
                  .build())
          .build();
    }

    @Override
    public Object runBlocking(ToolContext context, Map<String, ?> args) {
      String question = (String) args.get("question");
      System.out.println(">>> Deep Thought [SYSTEM]: Calculating answer for '" + question + "'...");
      String lowercased = question.toLowerCase(Locale.ROOT);
      if (lowercased.contains("life") && lowercased.contains("universe")) {
        return "The answer to the Ultimate Question of Life, the Universe, and Everything is 42.";
      }
      return "I don't know the answer to that. I only know the answer to the Ultimate Question.";
    }
  }

  /** Calculates the improbability of a given event. */
  private static final class CalculateImprobabilityTool extends BlockingTool {

    CalculateImprobabilityTool() {
      super("calculateImprobability", "Calculates the improbability of a given event.");
    }

    @Override
    public FunctionDeclaration declaration() {
      return FunctionDeclaration.builder()
          .name("calculateImprobability")
          .description("Calculates the improbability of a given event.")
          .parameters(
              Schema.builder()
                  .type(Type.OBJECT)
                  .properties(
                      Map.of(
                          "event",
                          Schema.builder()
                              .type(Type.STRING)
                              .description(
                                  "The event to calculate the improbability for, e.g., 'A cup of"
                                      + " tea materializing'")
                              .build(),
                          "level",
                          Schema.builder()
                              .type(Type.NUMBER)
                              .description("Desired level of improbability (optional)")
                              .build()))
                  .required("event")
                  .build())
          .build();
    }

    @Override
    public Object runBlocking(ToolContext context, Map<String, ?> args) {
      String event = (String) args.get("event");
      Double level =
          args.get("level") instanceof Number number ? Double.valueOf(number.doubleValue()) : null;
      System.out.println(
          ">>> Improbability Drive [SYSTEM]: Engaging for " + event + " at level " + level + "...");
      double improbability = ThreadLocalRandom.current().nextDouble() * 1000;
      return "The improbability of '"
          + event
          + "' is approximately "
          + improbability
          + " to 1 against.";
    }
  }

  /**
   * Gets the status of the Infinite Improbability Drive at given coordinates. Demonstrates Data
   * Class parameter and suspend.
   */
  private static final class GetDriveStatusTool extends BlockingTool {

    private static final String DESCRIPTION =
        "Gets the status of the Infinite Improbability Drive at given coordinates. Demonstrates"
            + " Data Class parameter and suspend.";

    GetDriveStatusTool() {
      super("getDriveStatus", DESCRIPTION);
    }

    @Override
    public FunctionDeclaration declaration() {
      return FunctionDeclaration.builder()
          .name("getDriveStatus")
          .description(DESCRIPTION)
          .parameters(
              Schema.builder()
                  .type(Type.OBJECT)
                  .properties(
                      Map.of(
                          "coordinates",
                          Schema.builder()
                              .type(Type.OBJECT)
                              .description("Galactic Coordinates")
                              .properties(
                                  Map.of(
                                      "x", Schema.builder().type(Type.NUMBER).build(),
                                      "y", Schema.builder().type(Type.NUMBER).build(),
                                      "z", Schema.builder().type(Type.NUMBER).build(),
                                      "time", Schema.builder().type(Type.NUMBER).build()))
                              .required("x", "y", "z", "time")
                              .build()))
                  .required("coordinates")
                  .build())
          .build();
    }

    @Override
    public Object runBlocking(ToolContext context, Map<String, ?> args) {
      Map<?, ?> coordinates = (Map<?, ?>) args.get("coordinates");
      System.out.println(
          ">>> Heart of Gold [SYSTEM]: Suspending to check drive status at "
              + toDouble(coordinates.get("x"))
              + ", "
              + toDouble(coordinates.get("y"))
              + ", "
              + toDouble(coordinates.get("z"))
              + ", "
              + toDouble(coordinates.get("time"))
              + "...");
      try {
        Thread.sleep(500); // Simulate Deep Thought latency
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
      return Map.of(
          BaseTool.RESULT_KEY,
          Map.of(
              "locationName",
              "Sector ZZ9 Plural Z Alpha",
              "improbabilityLevel",
              ThreadLocalRandom.current().nextDouble() * 1e6,
              "sideEffects",
              List.of("Whales and petunias materializing", "Reality alteration"),
              "teaStatus",
              TeaStatus.NEARLY_BUT_NOT_QUITE_ENTIRELY_UNLIKE_TEA.name()));
    }

    private static Double toDouble(Object value) {
      return value instanceof Number number ? Double.valueOf(number.doubleValue()) : null;
    }
  }

  /** Gets bulk guide entries. Demonstrates List parameter and Map return. */
  private static final class GetBulkGuideEntriesTool extends BlockingTool {

    GetBulkGuideEntriesTool() {
      super(
          "getBulkGuideEntries",
          "Gets bulk guide entries. Demonstrates List parameter and Map return.");
    }

    @Override
    public FunctionDeclaration declaration() {
      return FunctionDeclaration.builder()
          .name("getBulkGuideEntries")
          .description("Gets bulk guide entries. Demonstrates List parameter and Map return.")
          .parameters(
              Schema.builder()
                  .type(Type.OBJECT)
                  .properties(
                      Map.of(
                          "entries",
                          Schema.builder()
                              .type(Type.ARRAY)
                              .description("List of guide entries to look up")
                              .items(Schema.builder().type(Type.STRING).build())
                              .build()))
                  .required("entries")
                  .build())
          .build();
    }

    @Override
    public Object runBlocking(ToolContext context, Map<String, ?> args) {
      List<?> entries = (List<?>) args.get("entries");
      System.out.println(">>> The Guide [SYSTEM]: Looking up bulk entries for " + entries + "...");
      Map<String, Object> reports = new LinkedHashMap<>();
      for (Object rawEntry : entries) {
        String entry = (String) rawEntry;
        reports.put(
            entry,
            Map.of(
                "locationName",
                entry,
                "improbabilityLevel",
                42.0,
                "sideEffects",
                List.of(),
                "teaStatus",
                TeaStatus.NOT_AVAILABLE.name()));
      }
      return Map.of(BaseTool.RESULT_KEY, reports);
    }
  }

  /** Submits a request for tea. Demonstrates Context Injection and Enum parameters. */
  private static final class SubmitTeaRequestTool extends BlockingTool {

    SubmitTeaRequestTool() {
      super(
          "submitTeaRequest",
          "Submits a request for tea. Demonstrates Context Injection and Enum parameters.");
    }

    @Override
    public FunctionDeclaration declaration() {
      return FunctionDeclaration.builder()
          .name("submitTeaRequest")
          .description(
              "Submits a request for tea. Demonstrates Context Injection and Enum parameters.")
          .parameters(
              Schema.builder()
                  .type(Type.OBJECT)
                  .properties(
                      Map.of(
                          "requester",
                          Schema.builder()
                              .type(Type.STRING)
                              .description("The person requesting tea")
                              .build(),
                          "status",
                          Schema.builder()
                              .type(Type.STRING)
                              .description("The desired status of the tea")
                              .enumValues(
                                  "HOT",
                                  "COLD",
                                  "NOT_AVAILABLE",
                                  "NEARLY_BUT_NOT_QUITE_ENTIRELY_UNLIKE_TEA")
                              .build()))
                  .required("requester", "status")
                  .build())
          .build();
    }

    @Override
    public Object runBlocking(ToolContext context, Map<String, ?> args) {
      String requester = (String) args.get("requester");
      TeaStatus status = TeaStatus.valueOf((String) args.get("status"));
      System.out.println(
          ">>> Nutri-Matic [SYSTEM]: Submitting "
              + status
              + " tea request for "
              + requester
              + "... (Call ID: "
              + context.getFunctionCallId()
              + ")");
      return "Successfully submitted request for " + status + " tea for " + requester + ".";
    }
  }

  /**
   * Retrieves an entry from The Hitchhiker's Guide to the Galaxy for a specific edition. This
   * demonstrates relying on KDoc for schema extraction rather than {@code @Param}.
   */
  private static final class GetHistoricalGuideEntryTool extends BlockingTool {

    private static final String DESCRIPTION =
        "Retrieves an entry from The Hitchhiker's Guide to the Galaxy for a specific edition. This"
            + " demonstrates relying on KDoc for schema extraction rather than @Param.";

    GetHistoricalGuideEntryTool() {
      super("getHistoricalGuideEntry", DESCRIPTION);
    }

    @Override
    public FunctionDeclaration declaration() {
      return FunctionDeclaration.builder()
          .name("getHistoricalGuideEntry")
          .description(DESCRIPTION)
          .parameters(
              Schema.builder()
                  .type(Type.OBJECT)
                  .properties(
                      Map.of(
                          "entryName",
                          Schema.builder()
                              .type(Type.STRING)
                              .description("The name of the entry (e.g. 'Babel Fish')")
                              .build(),
                          "edition",
                          Schema.builder()
                              .type(Type.STRING)
                              .description("The edition of the guide (e.g. 'Standard', 'Premium')")
                              .build()))
                  .required("entryName", "edition")
                  .build())
          .build();
    }

    @Override
    public Object runBlocking(ToolContext context, Map<String, ?> args) {
      String entryName = (String) args.get("entryName");
      String edition = (String) args.get("edition");
      System.out.println(
          ">>> The Guide [SYSTEM]: Looking up entry for "
              + entryName
              + " in the "
              + edition
              + " edition...");
      return switch (entryName.toLowerCase(Locale.ROOT)) {
        case "babel fish" ->
            "The Babel fish is small, yellow, and leech-like, and probably the oddest thing in the"
                + " Universe. (Edition: "
                + edition
                + ")";
        case "vogon" ->
            "Vogons are one of the most unpleasant races in the Galaxy. (Edition: " + edition + ")";
        default ->
            "Entry for '" + entryName + "' not found. Mostly harmless. (Edition: " + edition + ")";
      };
    }
  }

  private FunctionToolDemoAgentJava() {}
}
