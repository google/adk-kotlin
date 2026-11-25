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

import com.google.adk.kt.apps.App;
import com.google.adk.kt.events.Event;
import com.google.adk.kt.interop.FunctionNodes;
import com.google.adk.kt.interop.InteropContextView;
import com.google.adk.kt.interop.PublisherRunner;
import com.google.adk.kt.types.Blob;
import com.google.adk.kt.types.Content;
import com.google.adk.kt.types.Part;
import com.google.adk.kt.types.Role;
import com.google.adk.kt.workflow.FunctionNode;
import com.google.adk.kt.workflow.NodeBody;
import com.google.adk.kt.workflow.Workflow;
import java.util.Base64;
import java.util.List;
import reactor.core.publisher.Flux;

/**
 * A port of adk-python's {@code message} workflow sample: nodes send the user a text message, a
 * message with an inline image, several messages in a row, and a sentence streamed in chunks.
 */
public final class MessageWorkflowJava {

  /** Builds the workflow. It calls no model. */
  public static Workflow create() {
    return Workflow.builder()
        .name("message")
        .edges(g ->
            g.fromStart()
                .chain(sendString(), sendMultimodal(), multipleMessages(), streamSentence()))
        .build();
  }

  /** Runs the workflow once, printing streamed chunks as they arrive and each complete message. */
  public static void main(String[] args) {
    PublisherRunner runner =
        PublisherRunner.inMemory(App.builder().appName("message").rootNode(create()).build());
    Content message = Content.fromText(Role.USER, "go");
    boolean midStream = false;
    for (Event event : Flux.from(runner.runAsync("user", "session", null, message)).toIterable()) {
      String text = event.contentText(" ");
      if (event.getPartial()) {
        System.out.print(text);
        System.out.flush();
        midStream = true;
      } else if (!text.isBlank()) {
        // Ends the line of streamed chunks before the complete message that repeats them.
        if (midStream) {
          System.out.println();
          midStream = false;
        }
        System.out.println(text);
      }
    }
  }

  /** A 16x16 solid red PNG. */
  private static final String RED_SQUARE_PNG =
      "iVBORw0KGgoAAAANSUhEUgAAABAAAAAQCAYAAAAf8/9hAAAAXElEQVR4nO2TSQ7AIAwD"
          + "7fz/z+ZQtapwmrJc8QklmjBIgZJgIZMiAIl9KYbhjx4fgwosbNxgMrF0+4uhgHnYDM6"
          + "AzQHJeg5HYtyHFfgy2AztN/5tZWfrBtVzkl4DzfQkEPd+cEkAAAAASUVORK5CYII=";

  /** A user-facing message from a node, with {@code text} as its content. */
  private static Event message(String text) {
    return Event.builder().content(Content.fromText(Role.MODEL, text)).build();
  }

  /** A node that takes any input, sends messages from {@code body}, and outputs nothing. */
  private static FunctionNode<Object, Object> node(String name, NodeBody<Object, Object> body) {
    return FunctionNodes.of(name, Object.class, Object.class, body);
  }

  /** Sends one message with {@code emit}; a returned value would be the node's output instead. */
  private static FunctionNode<Object, Object> sendString() {
    return node(
        "send_string",
        (ctx, input) -> {
          InteropContextView.of(ctx)
              .emit(Content.fromText(Role.MODEL, "#1 This is a simple string message."));
          return null;
        });
  }

  private static FunctionNode<Object, Object> sendMultimodal() {
    return node(
        "send_multimodal",
        (ctx, input) -> {
          Blob image = new Blob("image/png", null, Base64.getDecoder().decode(RED_SQUARE_PNG));
          String text = "#2 Here is a multi-modal message with an inline image (red square):";
          InteropContextView.of(ctx)
              .emit(
                  new Content(
                      Role.MODEL,
                      List.of(
                          Part.builder().text(text).build(),
                          Part.builder().inlineData(image).build())));
          return null;
        });
  }

  /** Sends complete messages one after another from the same node. */
  private static FunctionNode<Object, Object> multipleMessages() {
    return node(
        "multiple_messages",
        (ctx, input) -> {
          InteropContextView view = InteropContextView.of(ctx);
          for (String step :
              List.of("#3 Multiple messages", "Processing step 1...", "Processing step 2...")) {
            view.emit(message(step));
            Thread.sleep(1000);
          }
          view.emit(message("Done processing."));
          return null;
        });
  }

  /**
   * Streams a sentence in partial chunks, which clients display but the session does not store, so
   * the node then sends the whole sentence once as a complete message.
   */
  private static FunctionNode<Object, Object> streamSentence() {
    return node(
        "stream_sentence",
        (ctx, input) -> {
          InteropContextView view = InteropContextView.of(ctx);
          view.emit(message("#4 Starting to stream..."));
          String sentence =
              """
              This is a streaming message sent in chunks.

              You can stream in markdown as well. For example, the table below:

              | Header 1 | Header 2 |
              |----------|----------|
              | Cell 1   | Cell 2   |
              | Cell 3   | Cell 4   |\
              """;
          for (int start = 0; start < sentence.length(); start += 5) {
            String chunk = sentence.substring(start, Math.min(start + 5, sentence.length()));
            view.emit(message(chunk).toBuilder().partial(true).build());
            Thread.sleep(200);
          }
          view.emit(message(sentence));
          return null;
        });
  }

  private MessageWorkflowJava() {}
}
