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

package com.google.adk.kt.models

import com.google.adk.kt.annotations.ExperimentalLiveApi
import com.google.adk.kt.logging.LoggerFactory
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.GroundingMetadata
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.google.adk.kt.types.Transcription
import com.google.adk.kt.types.fromGenaiSdk
import com.google.adk.kt.types.toGenaiSdk
import com.google.genai.kotlin.LiveSession
import com.google.genai.kotlin.types.ActivityEnd as GenAiActivityEnd
import com.google.genai.kotlin.types.ActivityStart as GenAiActivityStart
import com.google.genai.kotlin.types.Blob as GenAiBlob
import com.google.genai.kotlin.types.Content as GenAiContent
import com.google.genai.kotlin.types.FunctionResponse as GenAiFunctionResponse
import com.google.genai.kotlin.types.LiveServerMessage as GenAiLiveServerMessage
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The operations [GeminiLiveConnection] needs from an open live session.
 *
 * The SDK's `LiveSession` is final and can only be obtained by opening a real websocket, so the
 * connection talks to this instead and tests supply their own implementation. It mirrors
 * [Gemini.GeminiModels], which exists for the same reason on the unary path.
 */
internal interface LiveSessionHandle {
  fun receive(): Flow<GenAiLiveServerMessage>

  suspend fun sendClientContent(turns: List<GenAiContent>, turnComplete: Boolean)

  suspend fun sendRealtimeInput(
    audio: GenAiBlob? = null,
    video: GenAiBlob? = null,
    audioStreamEnd: Boolean? = null,
    text: String? = null,
    activityStart: GenAiActivityStart? = null,
    activityEnd: GenAiActivityEnd? = null,
  )

  suspend fun sendToolResponse(functionResponses: List<GenAiFunctionResponse>)

  suspend fun closeSession()

  /**
   * Drops the transport without a close frame, for when [closeSession] cannot send one.
   *
   * Defaults to doing nothing, which is right for a handle that owns no socket.
   */
  fun cancelSession() {}
}

/** The real [LiveSessionHandle], delegating to an open SDK session. */
internal class SdkLiveSessionHandle(private val session: LiveSession) : LiveSessionHandle {
  override fun receive(): Flow<GenAiLiveServerMessage> = session.receive()

  override fun cancelSession() {
    session.close()
  }

  override suspend fun sendClientContent(turns: List<GenAiContent>, turnComplete: Boolean) {
    session.sendClientContent(turns = turns, turnComplete = turnComplete)
  }

  override suspend fun sendRealtimeInput(
    audio: GenAiBlob?,
    video: GenAiBlob?,
    audioStreamEnd: Boolean?,
    text: String?,
    activityStart: GenAiActivityStart?,
    activityEnd: GenAiActivityEnd?,
  ) {
    session.sendRealtimeInput(
      audio = audio,
      video = video,
      audioStreamEnd = audioStreamEnd,
      text = text,
      activityStart = activityStart,
      activityEnd = activityEnd,
    )
  }

  override suspend fun sendToolResponse(functionResponses: List<GenAiFunctionResponse>) {
    session.sendToolResponse(functionResponses)
  }

  override suspend fun closeSession() {
    session.closeSession()
  }
}

/**
 * A [LiveConnection] over an open Gemini live session.
 *
 * Frames are mapped one at a time by [toLlmResponses]; transcriptions and the model's text are
 * aggregated across frames into non-partial responses alongside the streamed pieces, while
 * grounding is carried forward and tool calls are delivered as they arrive.
 *
 * A normal close ends [receive] quietly; a socket failure throws out of it unwrapped, and a
 * cancellation not caused by [closeSession] throws [IllegalStateException].
 */
@OptIn(ExperimentalAtomicApi::class)
internal class GeminiLiveConnection(
  private val session: LiveSessionHandle,
  private val modelVersion: String?,
  private val closeTimeout: Duration = CLOSE_SESSION_TIMEOUT,
  // The collector's context; only its dispatcher is taken, so the pump keeps its own lifecycle.
  pumpDispatcher: CoroutineContext = EmptyCoroutineContext,
) : SingleCollectorLiveConnection() {

  /**
   * The id the server assigns this session, learned from the first setup-complete frame.
   *
   * It is stamped on every response so a consumer can attribute one to a session. It is
   * deliberately read from the stream rather than from the session object: the SDK does not retain
   * it.
   */
  private val liveSessionId = AtomicReference<String?>(null)

  /** Completed by [pump] on the first setup-complete frame; [awaitSetup] suspends on it. */
  private val setup = CompletableDeferred<Unit>()

  /** Makes [closeSession] run its teardown once. */
  private val closed = AtomicBoolean(false)

  /**
   * Frames read off the session by [pump], buffered so a turn boundary never touches the socket.
   *
   * The session is read once for as long as it is open, so ending one turn's collection of [frames]
   * leaves the socket open for the next turn.
   */
  // The default buffer absorbs consumer jitter; `send` suspends when full, so no frame is dropped.
  private val frames = Channel<GenAiLiveServerMessage>(Channel.BUFFERED)

  /** Scope for [pump], tied to this connection and so to the one session it wraps. */
  private val pumpScope =
    CoroutineScope(
      SupervisorJob() + (pumpDispatcher[ContinuationInterceptor] ?: Dispatchers.Default)
    )

  /**
   * Reads the session into [frames] for the lifetime of the connection.
   *
   * Started eagerly, so frames arriving before the first collection are buffered rather than
   * missed, as they would be on a real socket.
   */
  // The lint cannot see the one-reader/serialized-writes guarantee this transport gives.
  @Suppress("UnsafeCoroutineCrossing")
  private val pump = pumpScope.launch {
    try {
      session.receive().collect { frame ->
        frame.setupComplete?.let { ack ->
          ack.sessionId?.let { liveSessionId.store(it) }
          // complete() returns true only the first time, so this logs once per connection.
          if (setup.complete(Unit)) logger.info { "Live session established on $modelVersion." }
        }
        frames.send(frame)
      }
      // A stream that ends before setup must fail connect rather than leave it waiting.
      setup.completeExceptionally(IllegalStateException("The live session closed before setup."))
      frames.close()
    } catch (e: CancellationException) {
      setup.completeExceptionally(e)
      if (closed.load() || !isActive) {
        // A cancellation we asked for: close quietly so the collector sees a normal end.
        frames.close()
      } else {
        // The SDK's own job was cancelled under us; the collector must not mistake it for a close.
        frames.close(IllegalStateException("The live session ended unexpectedly.", e))
      }
      throw e
    } catch (e: Throwable) {
      // After our own close the collector ends normally; otherwise it re-throws the real failure.
      setup.completeExceptionally(e)
      if (closed.load()) frames.close() else frames.close(e)
    }
  }

  /**
   * Suspends until the server confirms setup, throwing if the session is refused or closes first.
   *
   * [Gemini.connect] calls it before handing the connection over; a caller that constructs this
   * directly need not, since nothing else awaits [setup]. Closes the session on failure.
   */
  internal suspend fun awaitSetup() {
    try {
      setup.await()
    } catch (e: CancellationException) {
      closeSession()
      throw e
    } catch (e: Throwable) {
      closeSession()
      throw IllegalStateException("The live session failed to open.", e)
    }
  }

  /**
   * Input transcription chunks seen since the last flush; [outputTranscript] is its output twin.
   *
   * The server sends a transcription in pieces and does not reliably mark the last one, so the
   * aggregate is rebuilt here and flushed on a signal that the turn is over. Touched only from the
   * receive path, which the single-collector contract keeps to one coroutine at a time.
   */
  private var inputTranscript = StringBuilder()
  private var outputTranscript = StringBuilder()

  /**
   * Grounding accumulated across one collection's frames, reset when the collection ends.
   *
   * The frame that completes a turn usually carries none of its own, so citations are carried
   * forward to the full-text, tool-call or turn-complete response. Lives for one collection, as ADK
   * Python keeps it local to `receive`.
   */
  private var turnGrounding: GroundingMetadata? = null

  /**
   * The model's streamed text, aggregated across one collection's frames into non-partial full-text
   * responses. Mirrors ADK Python's `text`/`is_thought` locals in `receive`; touched only from the
   * receive path, like the aggregates above.
   */
  private var modelText = StringBuilder()
  private var modelTextIsThought = false

  /**
   * Ends the collection once the model completes a turn, so a caller reads one turn per collection
   * and the connection stays open for the next one. Mirrors ADK Python's
   * `GeminiLlmConnection.receive` (`models/gemini_llm_connection.py`), which breaks out of its
   * receive loop immediately after yielding turn-complete.
   */
  override fun responses(): Flow<LlmResponse> =
    turnResponses().onCompletion {
      // Reset per collection; the receive-path aggregates are kept local to one collection.
      turnGrounding = null
      modelText = StringBuilder()
      modelTextIsThought = false
    }

  /** One turn's responses: every frame up to and including the turn boundary, then the end. */
  private fun turnResponses(): Flow<LlmResponse> =
    frames.receiveAsFlow().transformWhile { message ->
      val content = message.serverContent
      // Any of these three ends transcription; the server does not mark the final chunk.
      val endOfSpeech =
        content?.interrupted == true ||
          content?.turnComplete == true ||
          content?.generationComplete == true

      // Merged first, so whichever response carries the turn's grounding has this frame's.
      val frameGrounding = content?.groundingMetadata?.fromGenaiSdk()
      frameGrounding?.let { turnGrounding = mergeGroundingMetadata(turnGrounding, it) }

      var flushed = false
      var heldInterrupted: LlmResponse? = null
      var bareInterruptMarker: LlmResponse? = null
      // A turn-ending or interrupting frame flushes its text, not the chunk.
      val willFlush = content?.turnComplete == true || content?.interrupted == true
      for (response in
        message.toLlmResponses(modelVersion = modelVersion, liveSessionId = liveSessionId.load())) {
        // The aggregate must reach the caller before turn-complete ends the collection.
        if (response.turnComplete == true && !flushed) {
          flushTranscripts { emit(it) }
          // The turn's grounding is decided before the full text clears it, so the warning sees it.
          warnOnIncompleteGrounding(frameGrounding ?: turnGrounding)
          // The aggregated text, with the turn's grounding, precedes turn-complete.
          if (modelText.isNotEmpty()) {
            emit(buildFullTextResponse(interrupted = response.interrupted == true))
          }
          flushed = true
        }
        when {
          response.inputTranscription != null -> {
            inputTranscript.append(response.inputTranscription.text.orEmpty())
            // Python guards its chunk yield on text; an end marker alone is not a chunk.
            if (!response.inputTranscription.text.isNullOrEmpty()) {
              emit(
                response.copy(
                  inputTranscription = response.inputTranscription.copy(finished = false),
                  partial = true,
                )
              )
            }
            if (response.inputTranscription.finished == true) {
              emit(inputTranscriptResponse())
            }
          }
          response.outputTranscription != null -> {
            outputTranscript.append(response.outputTranscription.text.orEmpty())
            if (!response.outputTranscription.text.isNullOrEmpty()) {
              emit(
                response.copy(
                  outputTranscription = response.outputTranscription.copy(finished = false),
                  partial = true,
                )
              )
            }
            if (response.outputTranscription.finished == true) {
              emit(outputTranscriptResponse())
            }
          }
          response.turnComplete == true -> {
            // This frame's grounding, else what the turn collected, else none.
            emit(response.copy(groundingMetadata = frameGrounding ?: turnGrounding))
            turnGrounding = null
          }
          response.content == null &&
            response.groundingMetadata == null &&
            response.interrupted &&
            response.turnComplete != true -> {
            // Converter's bare interruption marker; handled after the loop, with grounding.
            bareInterruptMarker = response
          }
          response.content != null && response.turnComplete != true -> {
            // A streamed model turn: accumulate its text; a flushing frame drops the chunk.
            val modelTurn = response.content
            val flushedText = mutableListOf<Part>()
            val pending = mutableListOf<Part>()
            for (part in modelTurn.parts) {
              val partText = part.text
              when {
                !partText.isNullOrEmpty() -> {
                  val isThought = part.thought == true
                  if (modelText.isNotEmpty() && isThought != modelTextIsThought) {
                    emit(buildFullTextResponse(withGrounding = false))
                    flushedText += pending
                    pending.clear()
                  }
                  modelText.append(partText)
                  modelTextIsThought = isThought
                  pending += part
                }
                modelText.isNotEmpty() && part.inlineData == null -> {
                  emit(buildFullTextResponse())
                  flushedText += pending
                  pending.clear()
                }
              }
            }
            if (willFlush) flushedText += pending
            // Strip by identity, as in ADK Python, so a repeated text part is not dropped.
            val kept = modelTurn.parts.filterNot { part -> flushedText.any { it === part } }
            if (kept.isNotEmpty()) emit(response.copy(content = modelTurn.copy(parts = kept)))
          }
          else -> emit(response)
        }
      }
      // A frame that only signals the end still has to flush.
      if (endOfSpeech && !flushed) {
        flushTranscripts { emit(it) }
      }
      // Interrupt: pending text to an interrupted full text; else a bare marker (audio: neither).
      if (content?.interrupted == true && content.turnComplete != true) {
        heldInterrupted =
          when {
            modelText.isNotEmpty() -> buildFullTextResponse(interrupted = true)
            bareInterruptMarker != null ->
              bareInterruptMarker.copy(groundingMetadata = turnGrounding).also {
                turnGrounding = null
              }
            else -> null
          }
      }
      // Python flushes transcripts before the interrupted response, so emit it after the flush.
      heldInterrupted?.let { emit(it) }
      // Emitted after the loop so usage leads; the call carries and clears the turn's grounding.
      message.toolCall?.functionCalls?.let { calls ->
        if (modelText.isNotEmpty()) emit(buildFullTextResponse())
        emit(
          LlmResponse(
            content =
              Content(
                role = Role.MODEL,
                parts = calls.map { Part(functionCall = it.fromGenaiSdk()) },
              ),
            groundingMetadata = turnGrounding,
            modelVersion = modelVersion,
            liveSessionId = liveSessionId.load(),
          )
        )
        turnGrounding = null
      }

      content?.turnComplete != true
    }

  /** Logs ADK Python's warning for a final grounding that has retrieval queries but no chunks. */
  private fun warnOnIncompleteGrounding(grounding: GroundingMetadata?) {
    if (
      grounding?.retrievalQueries?.isNotEmpty() == true && grounding.groundingChunks.isNullOrEmpty()
    ) {
      logger.warn {
        "Grounding metadata has ${grounding.retrievalQueries.size} retrieval queries but no " +
          "grounding chunks."
      }
    }
  }

  /**
   * Builds the turn's aggregated text as one non-partial response and clears the aggregate.
   *
   * [withGrounding] carries and then clears the turn's grounding, so a later response does not
   * duplicate it; a thought-switch flush passes it false, keeping grounding for the real turn end.
   */
  private fun buildFullTextResponse(
    interrupted: Boolean = false,
    withGrounding: Boolean = true,
  ): LlmResponse {
    val aggregated = modelText.toString()
    val isThought = modelTextIsThought
    modelText = StringBuilder()
    modelTextIsThought = false
    val grounding = if (withGrounding) turnGrounding else null
    if (withGrounding) turnGrounding = null
    return LlmResponse(
      content =
        Content(
          role = Role.MODEL,
          parts = listOf(Part(text = aggregated, thought = isThought.takeIf { it })),
        ),
      groundingMetadata = grounding,
      interrupted = interrupted,
      partial = false,
      modelVersion = modelVersion,
      liveSessionId = liveSessionId.load(),
    )
  }

  /** Emits the aggregated transcriptions built up since the last flush, and clears them. */
  private suspend fun flushTranscripts(emit: suspend (LlmResponse) -> Unit) {
    if (inputTranscript.isNotEmpty()) emit(inputTranscriptResponse())
    if (outputTranscript.isNotEmpty()) emit(outputTranscriptResponse())
  }

  private fun inputTranscriptResponse(): LlmResponse {
    val aggregated = inputTranscript.toString()
    inputTranscript = StringBuilder()
    return LlmResponse(
      inputTranscription = Transcription(text = aggregated, finished = true),
      partial = false,
      modelVersion = modelVersion,
      liveSessionId = liveSessionId.load(),
    )
  }

  private fun outputTranscriptResponse(): LlmResponse {
    val aggregated = outputTranscript.toString()
    outputTranscript = StringBuilder()
    return LlmResponse(
      outputTranscription = Transcription(text = aggregated, finished = true),
      partial = false,
      modelVersion = modelVersion,
      liveSessionId = liveSessionId.load(),
    )
  }

  override suspend fun sendHistory(history: List<Content>) {
    // Drops audio, which corrupts a replay, and `_adk_live` references the model can't resolve.
    val contents = history.map { it.withoutReplayOnlyParts() }.filter { it.parts.isNotEmpty() }
    if (contents.isEmpty()) {
      logger.debug { "No history to send after dropping replay-only parts." }
      return
    }
    // Answered only if history ends with a user-role turn (function responses are user-role too).
    val turnComplete = contents.last().role == Role.USER
    // turnComplete starts the model's reply; an extra "." after it made models reply twice.
    session.sendClientContent(contents.map { it.toGenaiSdk() }, turnComplete)
  }

  override suspend fun sendContent(content: Content, partial: Boolean) {
    // Enforces the LiveConnection contract: invalid content throws before anything is sent.
    val input = ContentInput(content, partial)
    val functionResponses = input.content.parts.mapNotNull { it.functionResponse }
    if (functionResponses.isNotEmpty()) {
      // A content made entirely of function responses is a tool response, which has its own frame.
      session.sendToolResponse(functionResponses.map { it.toGenaiSdk() })
      return
    }
    session.sendClientContent(listOf(input.content.toGenaiSdk()), turnComplete = !input.partial)
  }

  override suspend fun sendRealtime(input: RealtimeInput) {
    when (input) {
      is RealtimeInput.Audio -> session.sendRealtimeInput(audio = input.blob.toGenaiSdk())
      is RealtimeInput.Video -> session.sendRealtimeInput(video = input.blob.toGenaiSdk())
      RealtimeInput.AudioStreamEnd -> session.sendRealtimeInput(audioStreamEnd = true)
      RealtimeInput.ActivityStart -> session.sendRealtimeInput(activityStart = GenAiActivityStart())
      RealtimeInput.ActivityEnd -> session.sendRealtimeInput(activityEnd = GenAiActivityEnd())
    }
  }

  /** The observable close: the caller ended the conversation, or the run handed the agent over. */
  // Suspends in a finally, but inside NonCancellable and with both waits bounded.
  @Suppress("SuspendInFinally")
  override suspend fun closeSession() {
    // Idempotent per the LiveConnection contract: a second call is expected.
    if (!closed.compareAndSet(false, true)) return
    // NonCancellable: closeSession often runs while the caller is being cancelled.
    withContext(NonCancellable) {
      try {
        // Bounded here rather than by the caller: this block is uninterruptible.
        closeOrDrop(session, closeTimeout)
      } finally {
        // Cancelled after the session close, or the SDK reports a clean shutdown as an error.
        pumpScope.cancel()
        // Joined, so the postcondition is that the reader has stopped, not been asked to.
        withTimeoutOrNull(PUMP_JOIN_TIMEOUT) { pump.join() }
        frames.close()
      }
    }
  }

  internal companion object {
    /**
     * How long the graceful close waits for the SDK to queue and flush the close frame before the
     * socket is dropped anyway.
     *
     * Generous, because flushing the close frame takes milliseconds; the peer does not acknowledge
     * it, and the bound exists only so a stuck flush cannot make the enclosing run uncancellable.
     */
    private val CLOSE_SESSION_TIMEOUT = 10.seconds

    /** Closes [session] within [timeout], and drops its socket if the close did not complete. */
    internal suspend fun closeOrDrop(
      session: LiveSessionHandle,
      timeout: Duration = CLOSE_SESSION_TIMEOUT,
    ) {
      var closedGracefully = false
      try {
        closedGracefully =
          withTimeoutOrNull(timeout) {
            session.closeSession()
            true
          } == true
      } finally {
        if (!closedGracefully) {
          // The close frame was not flushed in time, so drop the SDK's socket or it stays open.
          logger.info { "Live session did not close cleanly; dropping the socket." }
          session.cancelSession()
        }
      }
    }

    /** How long teardown waits for the reader to unwind after it has been cancelled. */
    private val PUMP_JOIN_TIMEOUT = 5.seconds

    private val logger = LoggerFactory.getLogger(GeminiLiveConnection::class)
  }
}

/** Drops audio and internal `_adk_live` artifact references, which the model can't resolve. */
private fun Content.withoutReplayOnlyParts(): Content =
  copy(parts = parts.filterNot { it.isAudio() || it.isAdkLiveArtifact() })

/** Matches Python's `is_audio_part`: an `audio/` mime type on either binary field. */
private fun Part.isAudio(): Boolean =
  inlineData?.mimeType?.startsWith("audio/") == true ||
    fileData?.mimeType?.startsWith("audio/") == true

/** Matches Python's `_is_adk_live_artifact_part`: an internal `_adk_live` artifact reference. */
private fun Part.isAdkLiveArtifact(): Boolean =
  fileData?.fileUri?.let { it.startsWith("artifact://") && "/_adk_live/" in it } == true
