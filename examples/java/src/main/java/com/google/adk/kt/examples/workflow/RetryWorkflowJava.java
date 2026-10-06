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
import com.google.adk.kt.apps.App;
import com.google.adk.kt.events.Event;
import com.google.adk.kt.interop.AsyncJavaHelpers;
import com.google.adk.kt.interop.BasePublisherNode;
import com.google.adk.kt.interop.PublisherRunner;
import com.google.adk.kt.types.Content;
import com.google.adk.kt.types.Role;
import com.google.adk.kt.workflow.NodeConfig;
import com.google.adk.kt.workflow.RetryConfig;
import com.google.adk.kt.workflow.Workflow;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.reactivestreams.Publisher;

/**
 * A port of adk-python's {@code retry} workflow sample: a weather lookup fails at random, and its
 * retry policy runs it again, up to five attempts in all, before the next node reports the weather.
 * Each failed attempt emits an error event; the run fails if every attempt does. A synchronous Java
 * node fails before publishing, so only the successful attempt sends its attempt message.
 */
public final class RetryWorkflowJava {

  /** Builds the workflow. It calls no model. */
  public static Workflow create() {
    return Workflow.builder()
        .name("retry")
        .edges(g -> g.fromStart().chain(new GetWeather(), new ReportWeather()))
        .build();
  }

  /** Runs the workflow once and prints each failure, then the successful attempt and the report. */
  public static void main(String[] args) {
    PublisherRunner runner =
        PublisherRunner.inMemory(App.builder().appName("retry").rootNode(create()).build());
    Content message = Content.fromText(Role.USER, "go");
    try {
      AsyncJavaHelpers.forEach(
          runner.runAsync("user", "session", null, message),
          event -> {
            String text = event.contentText(" ");
            if (!text.isBlank()) {
              System.out.println(text);
            }
            if (event.getErrorCode() != null) {
              System.out.println(event.getErrorCode() + ": " + event.getErrorMessage());
            }
          });
    } catch (HttpException e) {
      // When the last attempt fails too, the run fails with that attempt's error.
      System.out.println("Every attempt failed.");
    }
  }

  /** A mock weather lookup that fails 70% of the time, retried with backoff from one second. */
  private static final class GetWeather extends BasePublisherNode {
    GetWeather() {
      super(
          "get_weather",
          NodeConfig.builder()
              .retryConfig(RetryConfig.builder().maxAttempts(5).initialDelayMillis(1000).build())
              .build());
    }

    @Override
    protected Publisher<Object> runNodeJava(Context context, Object nodeInput) {
      if (ThreadLocalRandom.current().nextDouble() < 0.7) {
        throw new HttpException(500, "Internal Server Error");
      }
      Event attempt =
          Event.builder()
              .content(
                  Content.fromText(
                      Role.MODEL, "Getting weather... attempt " + context.getAttemptCount()))
              .build();
      return AsyncJavaHelpers.publisherOf(List.of(attempt, "sunny"));
    }
  }

  /** Reports the weather the lookup returned. */
  private static final class ReportWeather extends BasePublisherNode {
    ReportWeather() {
      super("report_weather");
    }

    @Override
    protected Publisher<Object> runNodeJava(Context context, Object nodeInput) {
      Event report =
          Event.builder()
              .content(Content.fromText(Role.MODEL, "The weather is " + nodeInput))
              .build();
      return AsyncJavaHelpers.publisherOf(List.of(report));
    }
  }

  /** A mock API's error response. */
  private static final class HttpException extends RuntimeException {
    HttpException(int code, String reason) {
      super("HTTP Error " + code + ": " + reason);
    }
  }

  private RetryWorkflowJava() {}
}
