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

@file:OptIn(ExperimentalLiveApi::class)

package com.google.adk.kt.examples.live

import com.google.adk.kt.agents.Instruction
import com.google.adk.kt.agents.LiveRequestQueue
import com.google.adk.kt.agents.LiveRunConfig
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.agents.RunConfig
import com.google.adk.kt.annotations.ExperimentalLiveApi
import com.google.adk.kt.annotations.Param
import com.google.adk.kt.annotations.Tool
import com.google.adk.kt.events.Event
import com.google.adk.kt.models.Gemini
import com.google.adk.kt.models.RealtimeInput
import com.google.adk.kt.models.VertexCredentials
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.types.Blob
import com.google.adk.kt.types.Modality
import com.google.adk.kt.types.Transcription
import com.google.genai.kotlin.GenAiApiException
import java.io.IOException
import java.lang.invoke.MethodHandles
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.concurrent.thread
import kotlin.system.exitProcess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

private val logger = Logger.getLogger(MethodHandles.lookup().lookupClass().name)

/** The voice demo's tool, for a question the model cannot answer on its own. */
class WeatherTools {

  /** Reports the current weather in a city. */
  @Tool
  fun getWeather(@Param("The city to look up, e.g. 'Krakow'") city: String): String =
    when (city.lowercase()) {
      "krakow",
      "kraków" -> "Sunny and 21 degrees Celsius in Krakow."
      "zurich" -> "Light rain and 14 degrees Celsius in Zurich."
      else -> "There is no weather report for $city."
    }
}

/**
 * Holds a spoken conversation with an agent through the microphone and speaker until Enter is
 * pressed, printing both sides and any `getWeather` call. Talking over the agent cuts its reply
 * short. Use headphones, since there is no echo cancellation.
 */
fun main(): Unit = runBlocking {
  val agent =
    LlmAgent(
      name = "live_assistant",
      // Live models are served from a region, not the global endpoint other examples use.
      model =
        Gemini(
          name = "gemini-3.8-live",
          vertexCredentials = VertexCredentials(location = "us-central1"),
        ),
      instruction =
        Instruction(
          "You are a friendly assistant in a voice conversation. Keep replies short. Call " +
            "getWeather for any question about the weather."
        ),
      tools = WeatherTools().generatedTools(),
    )

  val exitStatus =
    try {
      InMemoryRunner(agent = agent, appName = "live_agent_demo").use { runner ->
        // Carries the microphone's audio to the agent; closing it ends the run.
        LiveRequestQueue().use { queue ->
          AudioPlayer().use { player ->
            println(
              "Talk to the agent; press Enter to stop. Use headphones: there's no echo " +
                "cancellation."
            )
            // Set before the queue closes; unset at the end means the session ended without Enter.
            val enterPressed = AtomicBoolean()
            // Daemon: a blocked read can't be cancelled and must not keep the JVM up.
            thread(isDaemon = true, name = "stop-on-enter") {
              try {
                if (readlnOrNull() != null) enterPressed.set(true)
              } finally {
                queue.close()
              }
            }
            converse(runner, queue, player)
            if (!enterPressed.get()) println("The live session ended.")
          }
        }
      }
      0
    } catch (e: AudioDeviceException) {
      println("No usable microphone or speaker: ${e.message}. Run this on a machine with audio.")
      1
    } catch (e: Exception) {
      when (e) {
        is GenAiApiException,
        is IOException,
        is IllegalStateException -> println("The live session failed: ${e.innermostMessage()}")
        // Anything else is a bug: log it in full, and still exit below.
        else -> logger.log(Level.SEVERE, "The live demo failed unexpectedly", e)
      }
      1
    }
  // The model client's HTTP threads would otherwise keep the JVM up for about a minute.
  exitProcess(exitStatus)
}

/** Streams the microphone to the agent, prints the conversation and plays the agent's voice. */
private suspend fun converse(
  runner: InMemoryRunner,
  queue: LiveRequestQueue,
  player: AudioPlayer,
): Unit = coroutineScope {
  // Collecting `record()` opens the microphone and closing the collection releases it.
  val recorder = AudioRecorder()
  @Suppress("UnsafeCoroutineCrossing") // recorder is read-only; queue senders take any thread.
  val capture = launch {
    recorder.record().collect { frame ->
      queue.sendRealtime(RealtimeInput.Audio(Blob(mimeType = recorder.mimeType, data = frame)))
    }
    // The recording only ends on its own when the device stops; cancellation skips this line.
    println("The microphone stopped; press Enter to end the session.")
  }
  val reply = AtomicInteger()
  // A channel, so that handling events never waits on playback.
  val speech = Channel<SpeechChunk>(Channel.UNLIMITED)
  // Skips chunks of an interrupted reply, including one already taken off the channel.
  @Suppress("UnsafeCoroutineCrossing") // AudioPlayer lets play() run beside discards.
  val playback = launch {
    for (chunk in speech) if (chunk.reply == reply.get()) player.play(chunk.pcm)
  }

  try {
    runner
      .runLive(
        userId = "demo-user",
        sessionId = "demo-session",
        liveRequestQueue = queue,
        runConfig = RunConfig(live = LiveRunConfig(responseModalities = listOf(Modality.AUDIO))),
      )
      .collect { event ->
        // The user started talking, so the rest of the reply is stale: drop it.
        if (event.interrupted || (event.inputTranscription != null && event.partial)) {
          reply.incrementAndGet()
          player.discardQueuedAudio()
        }
        printEvent(event)
        // Audio on the interrupting event itself is stale too.
        if (!event.interrupted) {
          for (part in event.content?.parts.orEmpty()) {
            val audio = part.inlineData?.takeIf { it.mimeType?.startsWith("audio/") == true }
            audio?.data?.let { speech.trySend(SpeechChunk(reply.get(), it)) }
          }
        }
      }
  } finally {
    // Stop the audio tasks and drop queued audio, as closing the player otherwise plays it out.
    capture.cancel()
    playback.cancel()
    player.discardQueuedAudio()
  }
}

/** A chunk of the agent's voice and the number of the reply it belongs to. */
private class SpeechChunk(val reply: Int, val pcm: ByteArray)

/** Prints what each side said, the agent's tool calls and their results, and interruptions. */
private fun printEvent(event: Event) {
  event.inputTranscription?.takeIf { it.isFinishedSpeech() }?.let { println("you: ${it.text}") }
  for (call in event.functionCalls()) println("agent calls ${call.name}(${call.args})")
  for (result in event.functionResponses()) println("${result.name} returned ${result.response}")
  event.outputTranscription?.takeIf { it.isFinishedSpeech() }?.let { println("agent: ${it.text}") }
  if (event.interrupted) println("[interrupted]")
}

/** Whether this is a finished transcription with words in it; a tool-call turn can end blank. */
private fun Transcription.isFinishedSpeech(): Boolean = finished == true && !text.isNullOrBlank()

/**
 * The innermost non-null message in the cause chain, skipping cancellations, since the model wraps
 * the server's reason.
 */
private fun Throwable.innermostMessage(): String =
  generateSequence(this) { it.cause }
    .filterNot { it is CancellationException }
    .mapNotNull { it.message }
    .lastOrNull() ?: toString()
