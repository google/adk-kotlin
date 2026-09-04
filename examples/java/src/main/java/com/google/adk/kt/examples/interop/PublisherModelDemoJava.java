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
import com.google.adk.kt.events.Event;
import com.google.adk.kt.interop.AsyncJavaHelpers;
import com.google.adk.kt.interop.BasePublisherModel;
import com.google.adk.kt.interop.PublisherRunner;
import com.google.adk.kt.models.LlmRequest;
import com.google.adk.kt.models.LlmResponse;
import com.google.adk.kt.runners.InMemoryRunner;
import com.google.adk.kt.types.Content;
import com.google.adk.kt.types.Part;
import com.google.adk.kt.types.Role;
import java.util.List;
import org.reactivestreams.Publisher;

/**
 * Demonstrates implementing a {@link com.google.adk.kt.models.Model} from Java via {@link
 * BasePublisherModel}. The engine's {@code generateContent} returns a Kotlin {@code Flow}; the base
 * lets Java return a Reactive Streams {@link Publisher} instead. This fake model just echoes the
 * user's last message, so the whole agent runs offline (no API key).
 */
public final class PublisherModelDemoJava {

  /** A minimal offline model: echoes the user's last message back as the model reply. */
  private static final class EchoModel extends BasePublisherModel {
    EchoModel(String name) {
      super(name);
    }

    @Override
    protected Publisher<LlmResponse> generateContentJava(LlmRequest request, boolean stream) {
      LlmResponse response =
          LlmResponse.builder()
              .content(Content.fromText(Role.MODEL, "Echo: " + lastUserText(request)))
              .build();
      return AsyncJavaHelpers.publisherOf(List.of(response));
    }
  }

  public static void main(String[] args) {
    BaseAgent agent =
        LlmAgent.builder()
            .name("echo_agent")
            .model(new EchoModel("echo-model"))
            .instruction("Echo the user's message.")
            .build();
    PublisherRunner runner =
        PublisherRunner.of(
            InMemoryRunner.builder().agent(agent).appName("PublisherModelDemo").build());

    // runAsync returns a Reactive Streams Publisher<Event>. Adapt it in one line to
    // RxJava:  Flowable<Event> rx = Flowable.fromPublisher(eventStream);
    // Reactor: Flux<Event> flux = Flux.from(eventStream);
    // or block on it directly with AsyncJavaHelpers, as below.
    Publisher<Event> eventStream =
        runner.runAsync(
            "demo-user", "demo-session", null, Content.fromText(Role.USER, "Hello, model!"));
    AsyncJavaHelpers.forEach(
        eventStream,
        event -> {
          String text = event.contentText(" ");
          if (!text.isBlank()) {
            System.out.println("[" + event.getAuthor() + "] " + text);
          }
        });
  }

  /** Returns the text of the last user turn in [request], or an empty string. */
  private static String lastUserText(LlmRequest request) {
    List<Content> contents = request.getContents();
    for (int i = contents.size() - 1; i >= 0; i--) {
      Content content = contents.get(i);
      if (Role.USER.equals(content.getRole())) {
        for (Part part : content.getParts()) {
          if (part.getText() != null) {
            return part.getText();
          }
        }
      }
    }
    return "";
  }

  private PublisherModelDemoJava() {}
}
