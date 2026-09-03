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

package com.google.adk.kt.examples.artifacts;

import com.google.adk.kt.agents.BaseAgent;
import com.google.adk.kt.agents.InvocationContext;
import com.google.adk.kt.agents.LlmAgent;
import com.google.adk.kt.annotations.Param;
import com.google.adk.kt.annotations.Tool;
import com.google.adk.kt.artifacts.ArtifactService;
import com.google.adk.kt.interop.AsyncJavaHelpers;
import com.google.adk.kt.interop.ReflectiveTools;
import com.google.adk.kt.models.Gemini;
import com.google.adk.kt.sessions.SessionKey;
import com.google.adk.kt.tools.BaseTool;
import com.google.adk.kt.tools.ToolContext;
import com.google.adk.kt.types.Part;

/**
 * Example agent demonstrating the Artifact Service: an agent that generates a report, persists it
 * with a tool, and reads it back to verify.
 *
 * <p>The report is saved to and loaded from the {@link ArtifactService} configured on the runner
 * ({@code InMemoryArtifactService} by default; wire a persistent backend for durability). The tools
 * call the suspending artifact API from Java through {@link AsyncJavaHelpers#await}, run inside
 * each tool's returned future.
 *
 * <p>The tools are built from annotated methods by {@link ReflectiveTools} (used here because this
 * example is compiled with javac, not the Kotlin compiler; prefer the KSP {@code @Tool} path when
 * available). {@link ReflectiveTools} injects the {@link ToolContext} parameter automatically.
 */
public final class ReportGeneratorAgentJava {

  /** Artifacts are keyed under this app name, independent of the runner's app name. */
  private static final String ARTIFACT_APP_NAME = "ReportGeneratorApp";

  private static SessionKey artifactKey(InvocationContext invocationContext) {
    SessionKey sessionKey = invocationContext.getSession().getKey();
    return new SessionKey(ARTIFACT_APP_NAME, sessionKey.getUserId(), sessionKey.getId());
  }

  /** The agent's artifact tools, built from annotated methods by {@link ReflectiveTools}. */
  static final class ReportTools {
    @Tool(
        name = "save_report",
        description = "Saves a generated markdown report to the artifact service.")
    public String saveReport(
        ToolContext context,
        @Param(
                name = "filename",
                description = "The desired filename for the report. Should end in .md")
            String filename,
        @Param(name = "content", description = "The full markdown content of the generated report.")
            String content) {
      ArtifactService artifactService = context.getInvocationContext().getArtifactService();
      if (artifactService == null) {
        return "Failed: ArtifactService not configured in InvocationContext.";
      }
      SessionKey key = artifactKey(context.getInvocationContext());
      var unused =
          AsyncJavaHelpers.await(
              c ->
                  artifactService.saveArtifact(
                      key, filename, Part.builder().text(content).build(), c));
      System.out.println(">>> [SYSTEM] Report saved to artifact: " + filename);
      return "Report saved successfully.";
    }

    @Tool(
        name = "read_report",
        description = "Retrieves a previously saved markdown report from the artifact service.")
    public String readReport(
        ToolContext context,
        @Param(name = "filename", description = "The filename to retrieve. Should end in .md")
            String filename) {
      ArtifactService artifactService = context.getInvocationContext().getArtifactService();
      if (artifactService == null) {
        return "Failed: ArtifactService not configured in InvocationContext.";
      }
      SessionKey key = artifactKey(context.getInvocationContext());
      Part artifact =
          AsyncJavaHelpers.await(c -> artifactService.loadArtifact(key, filename, null, c));
      if (artifact == null) {
        System.out.println(">>> [SYSTEM] Failed to read report: " + filename + " (not found)");
        return "Report not found.";
      }
      System.out.println(">>> [SYSTEM] Report read successfully: " + filename);
      String text = artifact.getText();
      return "Report contents:\n" + (text != null ? text : "[no content]");
    }
  }

  private static final ReportTools TOOLS = new ReportTools();

  // Reflection is costly, so each tool is built once; prefer the KSP @Tool path when the Kotlin
  // compiler is available (see
  // examples/src/main/java/com/google/adk/kt/examples/interop/FunctionToolDemoAgentJava.java).
  private static final BaseTool SAVE_REPORT = ReflectiveTools.fromMethod(TOOLS, "saveReport");
  private static final BaseTool READ_REPORT = ReflectiveTools.fromMethod(TOOLS, "readReport");

  public static final BaseAgent rootAgent =
      LlmAgent.builder()
          .name("report_generator")
          .model(new Gemini("gemini-3.1-flash-lite"))
          .instruction(
              "You are a report generating assistant. When asked to write a report, gather the"
                  + " necessary info, format it perfectly, save it using the `save_report` tool,"
                  + " and then read it back using the `read_report` tool to verify the content you"
                  + " just saved.")
          .tools(SAVE_REPORT, READ_REPORT)
          .build();

  private ReportGeneratorAgentJava() {}
}
