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

package com.google.adk.kt.testing.live

import com.google.adk.kt.annotations.ExperimentalLiveApi
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.testing.modelFunctionCallResponse
import com.google.adk.kt.testing.modelMessage
import com.google.adk.kt.types.Blob
import com.google.adk.kt.types.LiveServerGoAway
import com.google.adk.kt.types.LiveServerSessionResumptionUpdate
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Transcription
import com.google.adk.kt.types.TurnCompleteReason
import com.google.adk.kt.types.UsageMetadata
import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmStatic
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** Live output format: 24 kHz mono 16-bit PCM. */
private const val OUTPUT_MIME_TYPE = "audio/pcm;rate=24000"

/** 20 ms of output audio. */
private const val OUTPUT_BYTES_PER_FRAME = 960

internal sealed interface LiveScriptStep {
  data class Respond(val response: LlmResponse) : LiveScriptStep

  data class Await(val description: String, val predicate: (SentLiveMessage) -> Boolean) :
    LiveScriptStep

  data class Fail(val cause: Throwable) : LiveScriptStep

  /** Ends the stream itself, the way a closing socket does, rather than only the turn. */
  data object EndStream : LiveScriptStep
}

/**
 * A deterministic script of what the model sends, and of what it waits for before continuing.
 *
 * Built rather than recorded: the behaviors live tests care about most - barge-in, a connection
 * going away, a failure mid-stream - are ones the real service will not produce on demand.
 */
@ExperimentalLiveApi
class LiveScript private constructor(internal val steps: List<LiveScriptStep>) {

  /**
   * Assembles a script; every step method returns this builder. Kotlin and Java both build custom
   * scripts with it, so unlike the Java-interop builders it is not marked `@AdkJavaInteropApi`.
   */
  class Builder {
    private val steps = mutableListOf<LiveScriptStep>()

    /** Start of the current turn, so [endTurn] only looks back over the turn it is closing. */
    private var turnStart = 0

    /** Emits [response] verbatim. Use it for anything the typed steps cannot express. */
    fun respond(response: LlmResponse): Builder {
      add(response)
      return this
    }

    private fun add(response: LlmResponse) {
      steps.add(LiveScriptStep.Respond(response))
    }

    /**
     * Emits a text response, [partial] by default as streamed text is.
     *
     * The real connection follows streamed text with one non-partial aggregate response when the
     * turn completes, when it is interrupted, and before a tool call, so a script adds that itself
     * with `text(full, partial = false)`.
     */
    @JvmOverloads
    fun text(text: String, partial: Boolean = true): Builder =
      respond(LlmResponse(content = modelMessage(text), partial = partial))

    /**
     * Emits [frames] separate audio responses, one blob each, as the service does.
     *
     * The audio is silence at the output frame size, so fixtures stay readable and this module
     * carries no recorded payloads.
     */
    @JvmOverloads
    fun audio(frames: Int = 1): Builder {
      require(frames > 0) { "frames must be positive, was $frames" }
      repeat(frames) {
        val silence = Blob(mimeType = OUTPUT_MIME_TYPE, data = ByteArray(OUTPUT_BYTES_PER_FRAME))
        add(LlmResponse(content = modelMessage(Part(inlineData = silence))))
      }
      return this
    }

    /** Emits a request to call [name]. The caller answers with a function response. */
    @JvmOverloads
    fun toolCall(name: String, args: Map<String, Any?> = emptyMap(), id: String? = null): Builder =
      respond(modelFunctionCallResponse(name, args, id))

    /** Emits an interruption, the signal that the user spoke over the model. */
    fun interrupted(): Builder = respond(LlmResponse(interrupted = true))

    /**
     * Ensures the current turn ends with a turn-complete response, which is what ends a collection.
     *
     * A turn already ending on a turn-complete response is left unchanged; otherwise one is added,
     * even when the turn's last step is a gate. The caller then collects again on the same
     * connection for the next turn, as it does against the real connection.
     */
    fun endTurn(): Builder {
      val last = steps.subList(turnStart, steps.size).lastOrNull()
      if ((last as? LiveScriptStep.Respond)?.response?.turnComplete != true) {
        add(LlmResponse(turnComplete = true))
      }
      turnStart = steps.size
      return this
    }

    /**
     * Ends the stream, so this and every later collection complete; if the script just runs out,
     * the connection parks like an open socket and fails the test after the gate timeout.
     *
     * Use [endTurn] to end a turn, and `endStream` to finish a script with no turn boundary, such
     * as one ending on a gate or a `goAway`. A script for a connection that stays open ends with
     * neither, and its test must stop collecting itself, for example with `take(1)`.
     */
    fun endStream(): Builder {
      steps.add(LiveScriptStep.EndStream)
      return this
    }

    /**
     * Waits until the code under test sends a message matching [predicate].
     *
     * Gates consume sent messages in order, so a message an earlier gate passed over cannot satisfy
     * a later one. [description] is required because it is what an unsatisfied gate reports when it
     * times out; without it a failure says only that something was expected.
     */
    fun awaitClientMessage(description: String, predicate: (SentLiveMessage) -> Boolean): Builder {
      steps.add(LiveScriptStep.Await(description, predicate))
      return this
    }

    /** Waits for a content whose every part is a function response. */
    fun awaitToolResponse(): Builder = awaitClientMessage("a tool response") { it.isToolResponse }

    /**
     * Emits a transcription chunk of what the user said; a finished one is the turn's aggregate.
     */
    @JvmOverloads
    fun inputTranscription(text: String, finished: Boolean = false): Builder =
      respond(
        LlmResponse(
          inputTranscription = Transcription(text = text, finished = finished),
          partial = !finished,
        )
      )

    /**
     * Emits a transcription chunk of what the model said; a finished one is the turn's aggregate.
     */
    @JvmOverloads
    fun outputTranscription(text: String, finished: Boolean = false): Builder =
      respond(
        LlmResponse(
          outputTranscription = Transcription(text = text, finished = finished),
          partial = !finished,
        )
      )

    /**
     * Emits usage accounting, then a turn-complete response that ends the collection, as the real
     * connection does.
     *
     * One wire frame carries both, but the connection yields usage as its own response first. A
     * fake that folded them into one would pass a caller that counts responses, or that reads usage
     * off the turn-complete response, and that caller would fail against the real service.
     */
    @JvmOverloads
    fun turnComplete(
      usageMetadata: UsageMetadata? = DEFAULT_USAGE,
      reason: TurnCompleteReason? = null,
    ): Builder {
      usageMetadata?.let { add(LlmResponse(usageMetadata = it)) }
      return respond(LlmResponse(turnComplete = true, turnCompleteReason = reason))
    }

    /** Emits a warning that the server will soon drop the connection. */
    @JvmOverloads
    fun goAway(timeLeft: Duration? = null): Builder =
      respond(LlmResponse(goAway = LiveServerGoAway(timeLeft = timeLeft)))

    /** [goAway] for Java, which cannot pass a [Duration]; use [goAway] for no time left. */
    fun goAwayMillis(timeLeftMillis: Long): Builder = goAway(timeLeftMillis.milliseconds)

    /**
     * Emits a session resumption handle; the service sends these on its own, but only when the
     * connection config enables session resumption.
     */
    @JvmOverloads
    fun sessionResumptionUpdate(handle: String, resumable: Boolean = true): Builder =
      respond(
        LlmResponse(
          liveSessionResumptionUpdate =
            LiveServerSessionResumptionUpdate(newHandle = handle, resumable = resumable)
        )
      )

    /**
     * Fails the collection with [cause]; the connection stays broken, so every later collection
     * rethrows the same cause, as a closed transport does.
     */
    fun failWith(cause: Throwable): Builder {
      steps.add(LiveScriptStep.Fail(cause))
      return this
    }

    fun build(): LiveScript = LiveScript(steps.toList())
  }

  companion object {
    /**
     * The call id [toolRoundTrip] uses by default, so a test can check that the tool response
     * answers that call.
     *
     * The gate does not check the id: a wrong answer still passes it, so the test's own assertion
     * reports the mismatch instead of a gate timeout that looks like a hang.
     */
    const val DEFAULT_TOOL_CALL_ID: String = "call-1"

    /** Plausible accounting for a short spoken turn; the numbers themselves carry no meaning. */
    internal val DEFAULT_USAGE: UsageMetadata =
      UsageMetadata(promptTokenCount = 12, candidatesTokenCount = 34, totalTokenCount = 46)

    @JvmStatic fun builder(): Builder = Builder()

    /**
     * A spoken turn, with responses in the order the real connection delivers them.
     *
     * The [Builder.sessionResumptionUpdate] after the turn boundary is the point of this preset: a
     * caller that stops at turn complete never sees the handle, so session resumption cannot work.
     * Reaching it takes a second collection, as it does on the real connection.
     */
    @JvmStatic
    @JvmOverloads
    fun simpleAudioTurn(audioFrames: Int = 3): LiveScript =
      builder()
        .outputTranscription("hello there")
        .audio(frames = audioFrames)
        .respond(LlmResponse(usageMetadata = DEFAULT_USAGE))
        .outputTranscription("hello there", finished = true)
        .endTurn()
        .sessionResumptionUpdate("handle-after-turn")
        .build()

    /** A turn the user speaks over, which the service reports as an interruption. */
    @JvmStatic
    @JvmOverloads
    fun bargeIn(audioFramesBeforeInterrupt: Int = 3): LiveScript =
      builder().audio(frames = audioFramesBeforeInterrupt).interrupted().endTurn().build()

    /**
     * A turn in which the model calls a tool and waits for the answer before finishing.
     *
     * This is the Gemini 3.x live order: the model withholds turn complete until the tool response
     * arrives, so a caller that never answers deadlocks. Other live models send turn complete after
     * the call instead, and this preset does not script that order.
     */
    @JvmStatic
    @JvmOverloads
    fun toolRoundTrip(
      toolName: String,
      args: Map<String, Any?> = emptyMap(),
      callId: String = DEFAULT_TOOL_CALL_ID,
    ): LiveScript =
      builder()
        .toolCall(toolName, args, id = callId)
        .awaitToolResponse()
        .outputTranscription("done")
        .audio(frames = 2)
        .respond(LlmResponse(usageMetadata = DEFAULT_USAGE))
        .outputTranscription("done", finished = true)
        .endTurn()
        .build()
  }
}
