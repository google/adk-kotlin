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
import com.google.adk.kt.workflow.Node;
import com.google.adk.kt.workflow.Workflow;
import java.util.List;
import java.util.Locale;
import org.reactivestreams.Publisher;

/**
 * A port of adk-python's {@code multi_triggers} workflow sample: three nodes transform the user's
 * text concurrently and each one triggers the same node, which therefore runs three times.
 */
public final class MultiTriggersWorkflowJava {

  /** Builds the workflow. It calls no model. */
  public static Workflow create() {
    Node makeUppercase = new MakeUppercase();
    Node countCharacters = new CountCharacters();
    Node reverseString = new ReverseString();
    Node sendMessage = new SendMessage();

    return Workflow.builder()
        .name("multi_triggers")
        .edges(
            g -> {
              // START hands each branch the user's message as Content.
              g.fromStart().then(List.of(makeUppercase, countCharacters, reverseString));
              // Without a JoinNode, each predecessor triggers its own run of send_message.
              g.from(makeUppercase).then(sendMessage);
              g.from(countCharacters).then(sendMessage);
              g.from(reverseString).then(sendMessage);
            })
        .build();
  }

  /** Runs the workflow on one message and prints the three reports. */
  public static void main(String[] args) {
    PublisherRunner runner =
        PublisherRunner.inMemory(
            App.builder().appName("multi_triggers").rootNode(create()).build());
    Content message = Content.fromText(Role.USER, "Hello, workflows!");
    AsyncJavaHelpers.forEach(
        runner.runAsync("user", "session", null, message),
        event -> {
          String text = event.contentText(" ");
          if (!text.isBlank()) {
            System.out.println(text);
          }
        });
  }

  private static final class MakeUppercase extends BasePublisherNode {
    MakeUppercase() {
      super("make_uppercase");
    }

    @Override
    protected Publisher<Object> runNodeJava(Context context, Object nodeInput) {
      return AsyncJavaHelpers.publisherOf(
          List.of(((Content) nodeInput).text().toUpperCase(Locale.ROOT)));
    }
  }

  private static final class CountCharacters extends BasePublisherNode {
    CountCharacters() {
      super("count_characters");
    }

    @Override
    protected Publisher<Object> runNodeJava(Context context, Object nodeInput) {
      return AsyncJavaHelpers.publisherOf(List.of(((Content) nodeInput).text().length()));
    }
  }

  private static final class ReverseString extends BasePublisherNode {
    ReverseString() {
      super("reverse_string");
    }

    @Override
    protected Publisher<Object> runNodeJava(Context context, Object nodeInput) {
      String text = ((Content) nodeInput).text();
      return AsyncJavaHelpers.publisherOf(List.of(new StringBuilder(text).reverse().toString()));
    }
  }

  /** Reports the output of whichever predecessor triggered this run. */
  private static final class SendMessage extends BasePublisherNode {
    SendMessage() {
      super("send_message");
    }

    @Override
    protected Publisher<Object> runNodeJava(Context context, Object nodeInput) {
      String text = "Triggered for input: " + nodeInput;
      return AsyncJavaHelpers.publisherOf(
          List.of(Event.builder().author("").content(Content.fromText(Role.MODEL, text)).build()));
    }
  }

  private MultiTriggersWorkflowJava() {}
}
