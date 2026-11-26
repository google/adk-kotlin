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

@file:OptIn(ExperimentalLiveApi::class, ExperimentalAtomicApi::class)

package com.google.adk.kt.agents

import com.google.adk.kt.annotations.ExperimentalLiveApi
import com.google.adk.kt.events.Event
import com.google.adk.kt.events.EventActions
import com.google.adk.kt.logging.LoggerFactory
import com.google.adk.kt.sessions.Session
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.sessions.SessionService
import com.google.adk.kt.types.Part
import com.google.errorprone.annotations.CanIgnoreReturnValue
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.jvm.JvmSynthetic
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Whether this event carries audio, video or image inline rather than as an artifact reference. */
@JvmSynthetic
internal fun Event.hasInlineMedia(): Boolean = content?.parts?.any { it.isInlineMedia() } == true

/** This event without its inline audio, video and image parts, and without content if none stay. */
@JvmSynthetic
internal fun Event.withoutInlineMedia(): Event {
  val content = content ?: return this
  val parts = content.parts.filterNot { it.isInlineMedia() }
  return copy(content = if (parts.isEmpty()) null else content.copy(parts = parts))
}

/**
 * What a live run stores of a node's event from a live model: all of it if it has no inline media,
 * else all but that media while its actions, tool calls and answers or deferred handoff still
 * matter to history and resumes, else nothing.
 */
@JvmSynthetic
internal fun Event.storedFromLiveModel(): Event? =
  when {
    !hasInlineMedia() -> this
    actions != EventActions() ||
      functionCalls().isNotEmpty() ||
      functionResponses().isNotEmpty() ||
      customMetadata?.containsKey(DEFERRED_HANDOFF_KEY) == true -> withoutInlineMedia()
    else -> null
  }

private fun Part.isInlineMedia(): Boolean {
  val mimeType = inlineData?.mimeType ?: return false
  return listOf("audio/", "video/", "image/").any { mimeType.startsWith(it, ignoreCase = true) }
}

/**
 * A recording save that a live node's turn started and its round cut off by ending the turn: its
 * [write], the recordings it has [saved] so far, and how to [cancel] it.
 */
internal class StrandedSave(
  val write: Job,
  val saved: AtomicReference<List<Event>>,
  val cancel: () -> Unit,
)

/**
 * State shared across every context derived from a live run's root: the caller's [requestQueue] and
 * the audio recorded since the last flush. The runner opens one per live run with [openLiveRun]; a
 * turn-based invocation has none.
 */
internal class LiveRun(
  val requestQueue: LiveRequestQueue,
  private val session: Session,
  private val sessionService: SessionService?,
) {
  /**
   * Live audio the caller has sent since the last flush, filled only through [AudioCacheManager]
   * when [LiveRunConfig.saveLiveBlob] is set. It lives on the run rather than in the manager, so a
   * turn restarted on a new connection keeps one recording.
   */
  val inputRealtimeCache = RealtimeCache()

  /** The audio the model has spoken since the last flush. See [inputRealtimeCache]. */
  val outputRealtimeCache = RealtimeCache()

  /** Whether a live session of this run has claimed the caller's; see [claimCallersSession]. */
  private val callersSessionClaimed = AtomicBoolean(false)

  /**
   * Claims the caller's session for the live session about to open: true for the run's first, which
   * may resume it with the caller's resumption handle, and false for any later one, which starts a
   * new session.
   */
  fun claimCallersSession(): Boolean = callersSessionClaimed.compareAndSet(false, true)

  /** The final answers of stopped streams, by the path of the live node that is to emit them. */
  private val deferredFinalAnswers = AtomicReference(emptyMap<String, List<Event>>())

  /**
   * Leaves [records], a live node turn's stopped streams' final answers, for the node at [nodePath]
   * to emit after its round's held events, so each is stored after its first answer.
   */
  fun deferFinalAnswers(nodePath: String, records: List<Event>) {
    deferredFinalAnswers.mutate { it + (nodePath to it[nodePath].orEmpty() + records) }
  }

  /** Takes the first final answer [deferFinalAnswers] left for the node at [nodePath], if any. */
  fun takeDeferredFinalAnswer(nodePath: String): Event? {
    while (true) {
      val deferred = deferredFinalAnswers.load()
      val records = deferred[nodePath] ?: return null
      val rest = records.drop(1)
      val next = if (rest.isEmpty()) deferred - nodePath else deferred + (nodePath to rest)
      if (deferredFinalAnswers.compareAndSet(deferred, next)) return records.first()
    }
  }

  /** Takes the final answers [deferFinalAnswers] left for the node at [nodePath]. */
  fun takeDeferredFinalAnswers(nodePath: String): List<Event> {
    while (true) {
      val deferred = deferredFinalAnswers.load()
      val taken = deferred[nodePath] ?: return emptyList()
      if (deferredFinalAnswers.compareAndSet(deferred, deferred - nodePath)) return taken
    }
  }

  /** The recording saves live node turns left running when cut off, by the node's path. */
  private val strandedSaves = AtomicReference(emptyMap<String, List<StrandedSave>>())

  /** The call ids a live node's connection was sent answers for, which no round event records. */
  private val sentAnswers = AtomicReference(emptyMap<String, Set<String>>())

  /** Records that the connection of the live node at [nodePath] was sent answers to [callIds]. */
  fun recordSentAnswers(nodePath: String, callIds: Set<String>) {
    sentAnswers.mutate { it + (nodePath to it[nodePath].orEmpty() + callIds) }
  }

  /** Takes the call ids [recordSentAnswers] recorded for the node at [nodePath]. */
  fun takeSentAnswers(nodePath: String): Set<String> {
    while (true) {
      val sent = sentAnswers.load()
      val taken = sent[nodePath] ?: return emptySet()
      if (sentAnswers.compareAndSet(sent, sent - nodePath)) return taken
    }
  }

  /**
   * Leaves [saves], which a turn of the live node at [nodePath] left running when its round ended
   * it, for that round to wait for, emit and cancel.
   */
  fun strandSaves(nodePath: String, saves: List<StrandedSave>) {
    strandedSaves.mutate { it + (nodePath to it[nodePath].orEmpty() + saves) }
  }

  /** Takes the saves [strandSaves] left for the node at [nodePath]. */
  fun takeStrandedSaves(nodePath: String): List<StrandedSave> {
    while (true) {
      val stranded = strandedSaves.load()
      val taken = stranded[nodePath] ?: return emptyList()
      if (strandedSaves.compareAndSet(stranded, stranded - nodePath)) return taken
    }
  }

  /**
   * The ids of events with inline media that a live model connection produced, not yet stored, by
   * the path of the live node whose turn produced them.
   */
  private val liveModelMedia = AtomicReference(emptyMap<String, Set<String>>())

  /**
   * Marks [event], which a live model connection produced for the node at [nodePath], if it carries
   * inline media, so the runner keeps that media out of the session even when a workflow node
   * relays the event.
   */
  fun markLiveModelMedia(nodePath: String?, event: Event) {
    // Outside a node the runner drops such media anyway. A partial too: a round may store it whole,
    // as a copy with the same id.
    if (nodePath == null || !event.hasInlineMedia()) return
    liveModelMedia.mutate { it + (nodePath to it[nodePath].orEmpty() + event.id) }
  }

  /**
   * Forgets the marks left for the node at [nodePath], once its round has emitted all it will, so a
   * mark on an event its round dropped does not stay; other nodes' marks stay.
   */
  fun forgetLiveModelMediaMarks(nodePath: String) {
    liveModelMedia.mutate { it - nodePath }
  }

  /** Whether [markLiveModelMedia] marked [event], forgetting the mark. */
  fun takeLiveModelMediaMark(event: Event): Boolean {
    while (true) {
      val marked = liveModelMedia.load()
      val (key, ids) = marked.entries.firstOrNull { event.id in it.value } ?: return false
      val rest = ids - event.id
      val next = if (rest.isEmpty()) marked - key else marked + (key to rest)
      if (liveModelMedia.compareAndSet(marked, next)) return true
    }
  }

  /** Whether [append] stores events; a run without a session service drops them. */
  val storesEvents: Boolean
    get() = sessionService != null

  private val appends = AtomicReference(0)

  /** How many events [append] has stored, so a caller can tell whether it stored any. */
  val appendCount: Int
    get() = appends.load()

  /** Returns the next request from [requestQueue] for [agent], one reader at a time. */
  suspend fun receiveRequest(agent: String): LiveRequest? = requestQueue.receiveRequest(agent)

  /** Fails, naming [agent], if another reader of [requestQueue] is already reading. */
  suspend fun checkNoOtherReader(agent: String) = requestQueue.checkNoOtherReader(agent)

  /** The live agent node running a live round now, if any; see [claimLiveNode]. */
  private val activeLiveNode = AtomicReference<LiveNodeClaim?>(null)

  /**
   * Claims the run for the calling live node at [node] before it connects, so a second live node
   * fails at once instead of after opening its own connection. A holder that was cancelled no
   * longer blocks the next node while it unwinds.
   *
   * @throws IllegalStateException if another live node holds the run.
   */
  suspend fun claimLiveNode(node: String): LiveNodeClaim {
    val job = currentCoroutineContext().job
    // A cancelled caller stops, rather than failing as a second node would.
    job.ensureActive()
    val claim = LiveNodeClaim(node, job)
    activeLiveNode.mutate { holder ->
      check(holder == null || !holder.job.isActive) {
        "Live node '$node' cannot start while live node '${holder?.node}' runs: a live run runs" +
          " one live node at a time."
      }
      claim
    }
    return claim
  }

  /** A live node's hold on the run, from [claimLiveNode], made by [job]. */
  inner class LiveNodeClaim(val node: String, val job: Job) {
    /** Releases this claim, so the next live node can claim the run. */
    fun release() {
      val unused = activeLiveNode.compareAndSet(this, null)
    }
  }

  /**
   * Appends [event] to the session, bounded by [LIVE_SESSION_TIMEOUT] so a hung session service
   * fails the run instead of stalling it. This does not serialize appends: the runner and the live
   * turn append concurrently, so the session service must accept concurrent appends.
   */
  suspend fun append(event: Event) {
    if (sessionService == null) {
      logger.warn { "A live session event was dropped: the run has no session service." }
      return
    }
    // Non-cancellable so a committed append is not repeated; a timeout still fails the run.
    withContext(NonCancellable) {
      withTimeoutOrNull(LIVE_SESSION_TIMEOUT) {
        val unused = sessionService.appendEvent(session, event)
        appends.mutate { it + 1 }
      } ?: error("A live session append did not finish in $LIVE_SESSION_TIMEOUT.")
    }
  }

  /** Flushes the session, bounded by [LIVE_SESSION_TIMEOUT]. */
  suspend fun flush() {
    // Nothing to flush: append already warned that this run drops its events.
    if (sessionService == null) return
    // Unlike the append, the flush stays cancellable: re-running a flush is safe.
    sessionService.boundedFlush(session.key)
  }
}

/**
 * Opens a live run for [requestQueue] on this context's session. Contexts copied from this one
 * share its [ContextFrameworkData], so they all see the same run. Opening a second one fails,
 * because it would replace the run those contexts share and mix two runs' queues and audio caches.
 */
@CanIgnoreReturnValue
@JvmSynthetic
internal fun InvocationContext.openLiveRun(requestQueue: LiveRequestQueue): LiveRun {
  check(frameworkData.liveRun == null) { "a live run is already open" }
  return LiveRun(requestQueue, session, sessionService).also { frameworkData.liveRun = it }
}

/** Flushes [key], failing the run if the flush takes longer than [LIVE_SESSION_TIMEOUT]. */
@JvmSynthetic
internal suspend fun SessionService.boundedFlush(key: SessionKey) {
  withTimeoutOrNull(LIVE_SESSION_TIMEOUT) { flush(key) }
    ?: error("A live session flush did not finish in $LIVE_SESSION_TIMEOUT.")
}

/** How long a live session's append or flush may take before failing the run. */
private val LIVE_SESSION_TIMEOUT = 10.seconds

private val logger = LoggerFactory.getLogger(LiveRun::class)
