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

package com.google.adk.kt.examples.live;

import static com.google.adk.kt.examples.live.AudioSupportJava.newDaemon;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.adk.kt.agents.BaseAgent;
import com.google.adk.kt.agents.LiveRequestQueue;
import com.google.adk.kt.agents.LiveRunConfig;
import com.google.adk.kt.agents.LlmAgent;
import com.google.adk.kt.agents.RunConfig;
import com.google.adk.kt.annotations.Param;
import com.google.adk.kt.annotations.Tool;
import com.google.adk.kt.events.Event;
import com.google.adk.kt.examples.live.AudioSupportJava.AudioDeviceException;
import com.google.adk.kt.interop.AsyncJavaHelpers;
import com.google.adk.kt.interop.PublisherRunner;
import com.google.adk.kt.interop.ReflectiveTools;
import com.google.adk.kt.models.Gemini;
import com.google.adk.kt.models.RealtimeInput;
import com.google.adk.kt.models.VertexCredentials;
import com.google.adk.kt.types.Blob;
import com.google.adk.kt.types.FunctionCall;
import com.google.adk.kt.types.FunctionResponse;
import com.google.adk.kt.types.Modality;
import com.google.adk.kt.types.Part;
import com.google.adk.kt.types.Transcription;
import com.google.genai.kotlin.GenAiApiException;
import java.io.IOException;
import java.util.Collections;
import java.util.Locale;
import java.util.Objects;
import java.util.Scanner;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.reactivestreams.Publisher;

/**
 * Holds a spoken conversation with an agent through the microphone and speaker until Enter is
 * pressed, printing both sides and any {@code getWeather} call. Talking over the agent cuts its
 * reply short. Use headphones, since there is no echo cancellation.
 */
public final class LiveAgentDemoJava {

  private static final Logger logger = Logger.getLogger(LiveAgentDemoJava.class.getName());

  /**
   * The voice demo's tool, for a question the model cannot answer on its own. {@link
   * ReflectiveTools} builds it, as this module has no KSP.
   */
  static final class WeatherTools {

    @Tool(description = "Reports the current weather in a city.")
    public String getWeather(
        @Param(name = "city", description = "The city to look up, e.g. 'Krakow'") String city) {
      return switch (city.toLowerCase(Locale.ROOT)) {
        case "krakow", "kraków" -> "Sunny and 21 degrees Celsius in Krakow.";
        case "zurich" -> "Light rain and 14 degrees Celsius in Zurich.";
        default -> "There is no weather report for " + city + ".";
      };
    }
  }

  public static void main(String[] args) {
    BaseAgent agent =
        LlmAgent.builder()
            .name("live_assistant")
            // Live models are served from a region, not the global endpoint other examples use.
            .model(
                new Gemini(
                    "gemini-3.8-live", VertexCredentials.builder().location("us-central1").build()))
            .instruction(
                "You are a friendly assistant in a voice conversation. Keep replies short. Call"
                    + " getWeather for any question about the weather.")
            .tools(ReflectiveTools.fromMethod(new WeatherTools(), "getWeather"))
            .build();
    RunConfig config =
        RunConfig.builder()
            .live(
                LiveRunConfig.builder()
                    .responseModalities(Collections.singletonList(Modality.AUDIO))
                    .build())
            .build();
    // The model client's HTTP threads would otherwise keep the JVM up for about a minute.
    System.exit(converse(agent, config));
  }

  /** Holds the conversation until Enter and returns the exit status: 0, or 1 after a failure. */
  // Kotlin code can throw undeclared checked exceptions; log anything else as a bug.
  @SuppressWarnings("CatchingUnchecked")
  private static int converse(BaseAgent agent, RunConfig config) {
    AudioRecorderJava recorder = new AudioRecorderJava();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    // Set before the queue closes; unset at the end means the session ended without Enter.
    AtomicBoolean enterPressed = new AtomicBoolean();
    try {
      try (PublisherRunner runner = PublisherRunner.inMemory(agent, "live_agent_demo");
          // Carries the microphone's audio to the agent; closing it ends the run.
          LiveRequestQueue queue = new LiveRequestQueue();
          AudioPlayerJava player =
              AudioPlayerJava.open(error -> recordFailure(failure, queue, error))) {
        System.out.println(
            "Talk to the agent; press Enter to stop. Use headphones: there's no echo"
                + " cancellation.");
        // Daemon: a blocked read of System.in can't be cancelled and must not keep the JVM up.
        newDaemon(
                () -> {
                  try {
                    if (new Scanner(System.in, UTF_8).hasNextLine()) {
                      enterPressed.set(true);
                    }
                  } finally {
                    queue.close();
                  }
                },
                "stop-on-enter")
            .start();

        recorder.start(
            frame ->
                queue.sendRealtime(
                    new RealtimeInput.Audio(
                        Blob.builder().mimeType(recorder.mimeType()).data(frame).build())),
            error -> recordFailure(failure, queue, error),
            () -> System.out.println("The microphone stopped; press Enter to end the session."));
        try {
          forEachEvent(
              runner.runLive("demo-user", "demo-session", queue, config),
              event -> handle(event, player));
        } finally {
          // Stop capturing and drop queued audio, as closing the player otherwise plays it out.
          recorder.stop();
          player.discardQueuedAudio();
        }
      }
      // Checked after the resources close, so a failure while the speaker drains is reported too.
      throwIfUnchecked(failure.get());
      if (!enterPressed.get()) {
        System.out.println("The live session ended.");
      }
      return 0;
    } catch (GenAiApiException | IOException | IllegalStateException e) {
      System.out.println("The live session failed: " + innermostMessage(e));
    } catch (AudioDeviceException e) {
      System.out.println(
          "No usable microphone or speaker: "
              + e.getMessage()
              + ". Run this on a machine with audio.");
    } catch (Exception e) {
      logger.log(Level.SEVERE, "The live demo failed unexpectedly", e);
    }
    return 1;
  }

  // Declared because forEach declares nothing, though the SDK and its transport throw these.
  @SuppressWarnings("CheckedExceptionNotThrown")
  private static void forEachEvent(Publisher<Event> events, Consumer<Event> action)
      throws GenAiApiException, IOException {
    AsyncJavaHelpers.forEach(events, action);
  }

  /** Records the first failure of the microphone or the speaker, which ends the run. */
  private static void recordFailure(
      AtomicReference<Throwable> failure, LiveRequestQueue queue, Throwable error) {
    if (failure.compareAndSet(null, error)) {
      queue.close();
    }
  }

  /** Prints the event and plays its audio, cutting it short when the user starts talking. */
  private static void handle(Event event, AudioPlayerJava player) {
    // The user started talking, so the rest of the reply is dropped.
    if (event.getInterrupted() || (event.getInputTranscription() != null && event.getPartial())) {
      player.discardQueuedAudio();
    }
    printEvent(event);
    // Audio on the interrupting event itself is stale too.
    if (event.getContent() == null || event.getInterrupted()) {
      return;
    }
    for (Part part : event.getContent().getParts()) {
      Blob audio = part.getInlineData();
      if (audio != null
          && audio.getMimeType() != null
          && audio.getMimeType().startsWith("audio/")
          && audio.getData() != null) {
        player.play(audio.getData());
      }
    }
  }

  /** Prints what each side said, the agent's tool calls and their results, and interruptions. */
  private static void printEvent(Event event) {
    Transcription heard = event.getInputTranscription();
    if (heard != null && isFinishedSpeech(heard)) {
      System.out.println("you: " + heard.getText());
    }
    for (FunctionCall call : event.functionCalls()) {
      System.out.println("agent calls " + call.getName() + "(" + call.getArgs() + ")");
    }
    for (FunctionResponse result : event.functionResponses()) {
      System.out.println(result.getName() + " returned " + result.getResponse());
    }
    Transcription said = event.getOutputTranscription();
    if (said != null && isFinishedSpeech(said)) {
      System.out.println("agent: " + said.getText());
    }
    if (event.getInterrupted()) {
      System.out.println("[interrupted]");
    }
  }

  /** Whether this is a finished transcription with words in it; a tool-call turn can end blank. */
  private static boolean isFinishedSpeech(Transcription transcription) {
    String text = transcription.getText();
    return Objects.equals(transcription.getFinished(), true) && text != null && !text.isBlank();
  }

  /**
   * The innermost non-null message in the cause chain, skipping cancellations, since the model
   * wraps the server's reason.
   */
  private static String innermostMessage(Throwable error) {
    String message = error.toString();
    for (Throwable cause = error; cause != null; cause = cause.getCause()) {
      if (!(cause instanceof CancellationException) && cause.getMessage() != null) {
        message = cause.getMessage();
      }
    }
    return message;
  }

  /** Rethrows a recorded device failure as is, so an AudioDeviceException reaches its catch. */
  private static void throwIfUnchecked(Throwable error) {
    if (error instanceof RuntimeException e) {
      throw e;
    }
    if (error instanceof Error e) {
      throw e;
    }
  }

  private LiveAgentDemoJava() {}
}
