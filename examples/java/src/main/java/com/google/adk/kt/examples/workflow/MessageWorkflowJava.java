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
import com.google.adk.kt.interop.BasePublisherNode;
import com.google.adk.kt.interop.PublisherRunner;
import com.google.adk.kt.types.Blob;
import com.google.adk.kt.types.Content;
import com.google.adk.kt.types.Part;
import com.google.adk.kt.types.Role;
import com.google.adk.kt.workflow.Workflow;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * A port of adk-python's {@code message} workflow sample: nodes send the user a text message, a
 * message with an inline image, several messages in a row, and a sentence streamed in chunks.
 */
public final class MessageWorkflowJava {

  /** Builds the workflow. It calls no model. */
  public static Workflow create() {
    return Workflow.builder()
        .name("message")
        .edges(
            g ->
                g.fromStart()
                    .chain(
                        new SendString(),
                        new SendMultimodal(),
                        new MultipleMessages(),
                        new StreamSentence()))
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

  /** A user-facing message from a node, with {@code parts} as its content. */
  private static Event message(Part... parts) {
    return Event.builder().content(new Content(Role.MODEL, List.of(parts))).build();
  }

  private static Part text(String text) {
    return Part.builder().text(text).build();
  }

  /** Publishes {@code event}, then waits {@code pause} before completing. */
  private static Flux<Object> thenPause(Event event, Duration pause) {
    return Mono.<Object>just(event).concatWith(Mono.<Object>empty().delaySubscription(pause));
  }

  private static final class SendString extends BasePublisherNode {
    SendString() {
      super("send_string");
    }

    @Override
    protected Publisher<Object> runNodeJava(Context context, Object nodeInput) {
      return Mono.just(message(text("#1 This is a simple string message.")));
    }
  }

  private static final class SendMultimodal extends BasePublisherNode {
    SendMultimodal() {
      super("send_multimodal");
    }

    @Override
    protected Publisher<Object> runNodeJava(Context context, Object nodeInput) {
      Blob image = new Blob("image/png", null, Base64.getDecoder().decode(RED_SQUARE_PNG));
      return Mono.just(
          message(
              text("#2 Here is a multi-modal message with an inline image (red square):"),
              Part.builder().inlineData(image).build()));
    }
  }

  /** Sends complete messages one after another from the same node. */
  private static final class MultipleMessages extends BasePublisherNode {
    MultipleMessages() {
      super("multiple_messages");
    }

    @Override
    protected Publisher<Object> runNodeJava(Context context, Object nodeInput) {
      return Flux.just("#3 Multiple messages", "Processing step 1...", "Processing step 2...")
          .concatMap(step -> thenPause(message(text(step)), Duration.ofSeconds(1)))
          .concatWith(Mono.just(message(text("Done processing."))));
    }
  }

  /**
   * Streams a sentence in partial chunks, which clients display but the session does not store, so
   * the node then sends the whole sentence once as a complete message.
   */
  private static final class StreamSentence extends BasePublisherNode {
    StreamSentence() {
      super("stream_sentence");
    }

    @Override
    protected Publisher<Object> runNodeJava(Context context, Object nodeInput) {
      String sentence =
          """
          This is a streaming message sent in chunks.

          You can stream in markdown as well. For example, the table below:

          | Header 1 | Header 2 |
          |----------|----------|
          | Cell 1   | Cell 2   |
          | Cell 3   | Cell 4   |\
          """;
      List<Event> chunks = new ArrayList<>();
      for (int start = 0; start < sentence.length(); start += 5) {
        String chunk = sentence.substring(start, Math.min(start + 5, sentence.length()));
        chunks.add(message(text(chunk)).toBuilder().partial(true).build());
      }
      return Flux.<Object>just(message(text("#4 Starting to stream...")))
          .concatWith(
              Flux.fromIterable(chunks)
                  .concatMap(chunk -> thenPause(chunk, Duration.ofMillis(200))))
          .concatWith(Mono.just(message(text(sentence))));
    }
  }

  private MessageWorkflowJava() {}
}
