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

package com.google.adk.kt.examples.plugins;

import com.google.adk.kt.agents.BaseAgent;
import com.google.adk.kt.agents.LlmAgent;
import com.google.adk.kt.apps.App;
import com.google.adk.kt.events.Event;
import com.google.adk.kt.interop.AsyncJavaHelpers;
import com.google.adk.kt.interop.PublisherRunner;
import com.google.adk.kt.models.Gemini;
import com.google.adk.kt.plugins.agentanalytics.BigQueryAgentAnalyticsPlugin;
import com.google.adk.kt.plugins.agentanalytics.BigQueryLoggerConfig;
import com.google.adk.kt.tools.GoogleSearchTool;
import com.google.adk.kt.types.Content;
import com.google.adk.kt.types.Part;
import com.google.adk.kt.types.Role;
import java.util.concurrent.atomic.AtomicInteger;
import org.reactivestreams.Publisher;

/**
 * Java port of the plugin demo, exercising {@link BigQueryAgentAnalyticsPlugin} through Java
 * interop.
 *
 * <p>Plugins intercept the agent lifecycle to log telemetry or integrate with external services.
 * The plugin here logs an event to BigQuery when an invocation starts and completes. Configure it
 * with {@code BIGQUERY_PROJECT_ID} and {@code BIGQUERY_DATASET_ID} (and optional {@code
 * BIGQUERY_TABLE_NAME}).
 */
public final class BigQueryAnalyticsDemoAgentJava {

  public static final BaseAgent rootAgent =
      LlmAgent.builder()
          .name("analytics_assistant")
          .model(new Gemini("gemini-3.1-flash-lite"))
          .instruction(
              "You are a helpful research assistant. Your interactions and events are logged to"
                  + " BigQuery for analytics.")
          .tools(GoogleSearchTool.builder().build())
          .build();

  /** Builds the demo {@link App}, wiring the BigQuery analytics plugin to the root agent. */
  public static App createApp(String projectId, String datasetId, String tableName) {
    BigQueryAgentAnalyticsPlugin plugin =
        BigQueryAgentAnalyticsPlugin.builder()
            .config(
                BigQueryLoggerConfig.builder()
                    .projectId(projectId)
                    .datasetId(datasetId)
                    .tableName(tableName)
                    .build())
            .build();

    return App.builder()
        .appName("BigQueryAnalyticsDemoApp")
        .rootAgent(rootAgent)
        .plugins(plugin)
        .build();
  }

  public static void main(String[] args) {
    String projectId = System.getenv("BIGQUERY_PROJECT_ID");
    String datasetId = System.getenv("BIGQUERY_DATASET_ID");
    String tableNameEnv = System.getenv("BIGQUERY_TABLE_NAME");
    String tableName = isNullOrBlank(tableNameEnv) ? "agent_events" : tableNameEnv;

    if (isNullOrBlank(projectId) || isNullOrBlank(datasetId)) {
      System.out.println(
          "Missing required environment variables!\n\n"
              + "Please set the following environment variables:\n"
              + "  export BIGQUERY_PROJECT_ID=<YOUR_GCP_PROJECT_ID>\n"
              + "  export BIGQUERY_DATASET_ID=<YOUR_BIGQUERY_DATASET_ID>\n"
              + "  export BIGQUERY_TABLE_NAME=<YOUR_TABLE_NAME> (optional, defaults to"
              + " 'agent_events')");
      return;
    }

    System.out.println("Starting BigQuery Analytics Demo Agent...");
    System.out.printf("Target BigQuery table: %s.%s.%s%n", projectId, datasetId, tableName);

    App app = createApp(projectId, datasetId, tableName);
    PublisherRunner runner = PublisherRunner.inMemory(app);

    // runAsync returns a Reactive Streams Publisher<Event>. Adapt it in one line to
    // RxJava:  Flowable<Event> rx = Flowable.fromPublisher(eventStream);
    // Reactor: Flux<Event> flux = Flux.from(eventStream);
    // or block on it directly with AsyncJavaHelpers, as below.
    Publisher<Event> eventStream =
        runner.runAsync(
            "user_123",
            "session_" + System.currentTimeMillis(),
            null,
            Content.fromText(Role.USER, "What are some of the key features of the Kotlin ADK?"));
    AtomicInteger eventCount = new AtomicInteger();
    AsyncJavaHelpers.forEach(
        eventStream,
        event -> {
          eventCount.incrementAndGet();
          String text = firstText(event);
          if (text != null && !text.isBlank()) {
            System.out.println("[" + event.getAuthor() + "]: " + text);
          }
        });
    System.out.println("Agent execution finished. Events collected: " + eventCount.get());
  }

  /** Returns the first text part of an event's content, or {@code null} if there is none. */
  private static String firstText(Event event) {
    Content content = event.getContent();
    if (content == null || content.getParts() == null) {
      return null;
    }
    for (Part part : content.getParts()) {
      if (part.getText() != null) {
        return part.getText();
      }
    }
    return null;
  }

  private static boolean isNullOrBlank(String value) {
    return value == null || value.isBlank();
  }

  private BigQueryAnalyticsDemoAgentJava() {}
}
