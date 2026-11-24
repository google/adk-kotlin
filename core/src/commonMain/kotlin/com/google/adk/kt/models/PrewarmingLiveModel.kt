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
@file:OptIn(ExperimentalAtomicApi::class, ExperimentalLiveApi::class)

package com.google.adk.kt.models

import com.google.adk.kt.agents.LiveRunConfig
import com.google.adk.kt.agents.RunConfig
import com.google.adk.kt.annotations.AdkJavaInteropApi
import com.google.adk.kt.annotations.ExperimentalLiveApi
import com.google.adk.kt.annotations.ExperimentalPrewarmingApi
import com.google.adk.kt.logging.LoggerFactory
import com.google.adk.kt.processors.forAgent
import com.google.adk.kt.types.GenerateContentConfig
import com.google.adk.kt.types.LiveConnectConfig
import com.google.errorprone.annotations.CanIgnoreReturnValue
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.CoroutineContext
import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmStatic
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A [Model] that opens live connections to `model` ahead of time so [connect] can take an
 * already-open connection (a spare) instead of waiting for a new session. Spares open without a
 * system instruction or tools and receive them during [connect] via
 * [LiveConnection.sendContextUpdate], so prewarming works only with models whose live connections
 * accept context updates, such as Gemini 3.x Live models. On other models, such as Gemini 2.5 Live
 * models, a [connect] call that takes a spare fails when the server ends the session with an error
 * (1007) after the update, unless `updateCheckWindow` is set, in which case the pool detects the
 * refusal on [Gemini] spares, keeps no spares, and falls back to opening a new connection for every
 * [connect] call.
 *
 * @param model The model whose live connections this pool keeps open.
 * @param spares Number of open connections to keep per setup (default 1); must be positive. Each
 *   [connect] takes a spare and starts opening a replacement, so with `spares = 2`, two calls that
 *   start within the few seconds it takes to open a replacement both get an open connection, while
 *   a third waits for a new connection to open.
 * @param maxSetups Maximum number of setups that keep spares (default 8); must be positive. A setup
 *   covers everything a request uses to connect except its history, system instruction, and tools
 *   (such as the model, voice, and response modalities), so agents that differ only in instruction
 *   and tools share a setup. With `maxSetups = 2`, connecting with setups A, B, and then C closes
 *   the spares for A, the least recently requested setup.
 * @param keepWarmFor How long after a setup's last [connect] or [prewarm] the pool keeps replacing
 *   spares that reach `maxSpareAge` or are ended by the server (default 30 minutes); must be
 *   positive, or null to never replace them. After that window, those spares are closed without
 *   replacement so an unused setup stops holding connections until its next [connect] or [prewarm].
 *   With `keepWarmFor = 10.minutes`, a setup last used at 12:00 gets a replacement for a spare the
 *   server ends at 12:05, but not for one ended at 12:15.
 * @param minLifetimeForReplacement Minimum time a spare must stay open for the pool to immediately
 *   replace it when the server ends it or it reaches `maxSpareAge` (default 1 minute); must be
 *   positive, or null to immediately replace every such spare while `keepWarmFor` allows, even one
 *   the server ends right after opening. A spare that ends sooner is not replaced right away,
 *   preventing a server that ends every new session or a short `maxSpareAge` from keeping the pool
 *   reconnecting; its setup refills on the next [connect] or [prewarm]. With the default, a spare
 *   ended by the server after 5 seconds is not replaced right away, while one ended after 2 minutes
 *   is replaced immediately if `keepWarmFor` allows.
 * @param maxSpareAge How long a spare stays available after opening (default 5 minutes); must be
 *   positive, or null for no limit. An older spare is closed and, while `keepWarmFor` allows,
 *   replaced so [connect] never takes a connection near the server's time limit (about 10 minutes
 *   on Gemini, with a go-away up to 1 minute before). With the default, a spare is replaced every 5
 *   minutes while `keepWarmFor` allows, so [connect] gets a connection at most 5 minutes old.
 * @param updateCheckWindow How long the pool waits for the server to end a [Gemini] spare's session
 *   after sending a test context update (a check the pool runs until one spare passes), or null
 *   (the default) to skip the check for models known to accept context updates. Set it, for example
 *   to 1 second, when the model may not accept context updates, such as a Gemini 2.5 Live model,
 *   where null makes calls that take a spare fail; once the server ends a checked spare's session
 *   with an error (1007), the pool keeps no spares, while a spare that ends otherwise is dropped
 *   and the next one is checked. When set, it must be finite, positive, and longer than the server
 *   takes to end a refused session, and a [connect] that takes a spare before one passes can wait
 *   up to this long.
 * @param context Coroutine context where the pool opens and watches spares (defaults to
 *   [Dispatchers.Default]), since spares outlive the [connect] call that requested them. After
 *   [connect] takes a spare, work the wrapped model tied to opening it (such as a [Gemini] spare's
 *   receive pump) keeps running in this context rather than the caller's. Any [Job] in this context
 *   is ignored, so only [close] ends the pool's work; because [close] blocks until that work
 *   finishes, this context must not be confined to the thread calling [close].
 */
@ExperimentalLiveApi
@ExperimentalPrewarmingApi
class PrewarmingLiveModel(
  internal val model: Model,
  internal val spares: Int = DEFAULT_SPARES,
  internal val maxSetups: Int = DEFAULT_MAX_SETUPS,
  internal val keepWarmFor: Duration? = DEFAULT_KEEP_WARM_FOR,
  internal val minLifetimeForReplacement: Duration? = DEFAULT_MIN_LIFETIME_FOR_REPLACEMENT,
  internal val maxSpareAge: Duration? = DEFAULT_MAX_SPARE_AGE,
  internal val updateCheckWindow: Duration? = null,
  internal val context: CoroutineContext = Dispatchers.Default,
) : Model by model, AutoCloseable {

  init {
    require(spares >= 1) { "spares must be at least 1, was $spares." }
    require(maxSetups >= 1) { "maxSetups must be at least 1, was $maxSetups." }
    require(keepWarmFor == null || keepWarmFor.isPositive()) {
      "keepWarmFor must be positive or null, was $keepWarmFor."
    }
    require(minLifetimeForReplacement == null || minLifetimeForReplacement.isPositive()) {
      "minLifetimeForReplacement must be positive or null, was $minLifetimeForReplacement."
    }
    require(maxSpareAge == null || maxSpareAge.isPositive()) {
      "maxSpareAge must be positive or null, was $maxSpareAge."
    }
    require(
      updateCheckWindow == null || (updateCheckWindow.isPositive() && updateCheckWindow.isFinite())
    ) {
      "updateCheckWindow must be finite and positive, or null, was $updateCheckWindow."
    }
  }

  // Spares outlive the requesting connect call, so they run in [context], not the caller's.
  private val scope = CoroutineScope(context + SupervisorJob())

  private val mutex = Mutex()

  /** Each setup's spares, least recently requested setup first; null once closed. */
  private var pools: Map<Setup, List<Spare>>? = emptyMap()

  /** When each setup in [pools] was last requested. */
  private var requested = emptyMap<Setup, TimeSource.Monotonic.ValueTimeMark>()

  /**
   * Whether new spares skip the test context update, either because a server accepted one or
   * because the check is disabled.
   */
  private val updatesAccepted = AtomicBoolean(updateCheckWindow == null)

  // Setups and requests are immutable, and the mutex guards the pool, so crossing threads is safe.
  @Suppress("UnsafeCoroutineCrossing")
  override suspend fun connect(request: LlmRequest): LiveConnection {
    // A resuming connect continues one particular session, so no spare can stand in for it.
    if (!request.liveConnectConfig.sessionResumption?.handle.isNullOrEmpty()) {
      return model.connect(request)
    }
    val setup = setupOf(request)
    val connection =
      try {
        takeLive(setup, request) ?: model.connect(request)
      } catch (e: Throwable) {
        // A setup that got no connection must not hold a place with no spares.
        withContext(NonCancellable) { forgetIfEmpty(setup) }
        throw e
      }
    scope.launch { topUp(setup, request.forSpare(), onlyIfPooled = false) }
    return connection
  }

  /**
   * Starts opening spares for live agents using [liveRunConfig] and [generateContentConfig], so
   * even their first [connect] can take one. An agent whose request config differs (for example,
   * when its output schema is applied) still opens its first connection directly.
   */
  // The request is immutable, and the mutex guards the pool, so crossing threads is safe.
  @Suppress("UnsafeCoroutineCrossing")
  @JvmOverloads
  fun prewarm(liveRunConfig: LiveRunConfig, generateContentConfig: GenerateContentConfig? = null) {
    val config = generateContentConfig ?: GenerateContentConfig()
    val live = LiveConnectConfig().forAgent(RunConfig(live = liveRunConfig), config)
    val request =
      LlmRequest(
        model = model,
        config = config,
        // Resuming connects never take spares; clear the handle so spares serve later connects.
        liveConnectConfig =
          live.copy(sessionResumption = live.sessionResumption?.copy(handle = null)),
      )
    scope.launch { topUp(setupOf(request), request.forSpare(), onlyIfPooled = false) }
  }

  /**
   * Closes every spare and waits for spares already on their way out, blocking the calling thread
   * until they are closed; a later [connect] opens its connection directly.
   */
  override fun close() = runBlocking {
    val closed = mutex.withLock {
      requested = emptyMap()
      pools.also { pools = null }
    }
    closed?.let { discardAll(it.values.flatten()) }
    // Also waits for spares a stopped pool is still closing.
    scope.coroutineContext.job.cancelAndJoin()
  }

  /**
   * Stops keeping spares because [model]'s connections refuse the context updates that spares
   * require, and closes existing ones in the pool's scope so [close] still waits for them. Logs [e]
   * if this call stopped the pool.
   */
  // The spares were published under the mutex held here, and their later state is atomic.
  @Suppress("UnsafeCoroutineCrossing")
  private suspend fun stopKeepingSpares(e: Exception) {
    val stopped =
      withContext(NonCancellable) {
        mutex.withLock {
          val kept = pools?.values?.flatten() ?: return@withLock false
          requested = emptyMap()
          pools = null
          // Start under the mutex so a later close() waits for it rather than canceling it first.
          scope.launch(start = CoroutineStart.UNDISPATCHED) { discardAll(kept) }
          true
        }
      }
    if (stopped) {
      logger.warn(e) {
        "Live connections of ${model.name} take no context updates; no spares kept."
      }
    }
  }

  /**
   * Handles a spare's failure [e]: stops the pool if a context update was refused, and otherwise
   * logs any non-cancellation failure with [warning] when [shouldWarn] returns true.
   */
  private suspend fun handleFailure(
    e: Exception,
    shouldWarn: suspend () -> Boolean = { true },
    warning: () -> String,
  ) {
    if (e is UnsupportedOperationException) {
      stopKeepingSpares(e)
    } else if (e !is CancellationException && shouldWarn()) {
      logger.warn(e, warning)
    }
  }

  /**
   * Sends [request]'s instruction and tools to the claimed [spare]; if that fails, closes [spare]
   * and returns null unless the caller was canceled. If [spare] does not accept context updates,
   * this also stops the pool.
   */
  private suspend fun configureSpare(spare: LiveConnection, request: LlmRequest): LiveConnection? {
    // Spares have no instruction or tools, so an empty instruction or tool list needs no update.
    val instruction = request.config.systemInstruction?.takeUnless { it.text().isEmpty() }
    val tools = request.config.tools?.ifEmpty { null }
    if (instruction == null && tools == null) return spare
    try {
      spare.sendContextUpdate(systemInstruction = instruction, tools = tools)
      return spare
    } catch (e: Exception) {
      // Always close the spare, even if the caller was canceled, so no spare leaks.
      withContext(NonCancellable) { spare.closeQuietly() }
      handleFailure(e) { "Configuring a spare live connection for its request failed." }
      // Only the caller's own cancellation propagates; otherwise takeLive tries the next spare.
      currentCoroutineContext().ensureActive()
      return null
    }
  }

  private fun setupOf(request: LlmRequest) =
    Setup(request.model?.name ?: model.name, request.forSpare().config, request.liveConnectConfig)

  /**
   * Takes spares of [setup], at most as many as it keeps, until one is still open and accepts
   * [request]'s instruction and tools, and returns that spare's connection or null if none does.
   * Each attempt removes the newest open spare (most server lifetime remaining), or the oldest
   * (closest to opening) if none is open, and marks [setup] as most recently requested.
   */
  private suspend fun takeLive(setup: Setup, request: LlmRequest): LiveConnection? {
    repeat(spares) {
      val spare = mutex.withLock {
        val pool = pools ?: return null
        val kept = pool[setup] ?: return null
        requested = requested + (setup to TimeSource.Monotonic.markNow())
        // A newer spare still opening would make the caller wait while an open one sits idle.
        val taken = kept.lastOrNull { it.isOpen } ?: kept.firstOrNull() ?: return null
        pools = pool - setup + (setup to kept.filterNot { it === taken })
        taken
      }
      spare.claim()?.let { claimed ->
        configureSpare(claimed, request)?.let {
          return it
        }
      }
    }
    return null
  }

  /** Removes [setup] if it has no spares, as after a connect that failed. */
  private suspend fun forgetIfEmpty(setup: Setup) = mutex.withLock {
    val pool = pools ?: return@withLock
    if (pool[setup]?.isEmpty() == true) {
      pools = pool - setup
      requested = requested - setup
    }
  }

  /**
   * Opens spares until [setup] has [spares] of them, unless [onlyIfPooled] and [setup] has left the
   * pool, and closes those of the setups requested least recently beyond [maxSetups].
   */
  private suspend fun topUp(setup: Setup, request: LlmRequest, onlyIfPooled: Boolean) {
    val dropped = mutex.withLock {
      val pool = pools ?: return
      if (onlyIfPooled && setup !in pool) return
      // A connect or prewarm requested this setup; replacements keep the last request's time.
      if (!onlyIfPooled) requested = requested + (setup to TimeSource.Monotonic.markNow())
      val existing = pool[setup].orEmpty()
      // Spares start opening as they are created, before a taker can see them.
      val added = List((spares - existing.size).coerceAtLeast(0)) { Spare(setup, request) }
      // A request makes its setup the most recent; a replacement keeps the setup's place.
      var next = (if (onlyIfPooled) pool else pool - setup) + (setup to existing + added)
      val dropped = mutableListOf<Spare>()
      while (next.size > maxSetups) {
        val oldest = next.keys.first()
        dropped += next.getValue(oldest)
        next = next - oldest
        requested = requested - oldest
      }
      pools = next
      dropped
    }
    discardAll(dropped)
  }

  // A spare is set up before it is published, and its later state is atomic.
  @Suppress("UnsafeCoroutineCrossing")
  private suspend fun discardAll(spares: List<Spare>) {
    if (spares.isEmpty()) return
    // Uncancellable, so cancelling the pool's scope in close() cannot skip these closes.
    withContext(NonCancellable) { for (spare in spares) launch { spare.discard() } }
  }

  /** One spare connection, opening as soon as it is created, then waiting to be taken or ended. */
  // Created under the pool's mutex and read by its taker only after the watcher stops.
  @Suppress("UnsafeCoroutineCrossing")
  private inner class Spare(val setup: Setup, val request: LlmRequest) {
    // Lets discard() close a connection whose open ignored the cancellation and returned anyway.
    private val opened = AtomicReference<LiveConnection?>(null)

    // When the connection opened; maxSpareAge is measured from this time.
    private val openedAt = AtomicReference<TimeSource.Monotonic.ValueTimeMark?>(null)

    private val connection: Deferred<LiveConnection> = scope.async {
      val live = model.connect(request)
      openedAt.store(TimeSource.Monotonic.markNow())
      opened.store(live)
      try {
        if (!updatesAccepted.load()) checkUpdatesAccepted(live)
      } catch (e: CancellationException) {
        // Close here so a close() that cancels the check still waits for the connection to close.
        withContext(NonCancellable) { opened.exchange(null)?.closeQuietly() }
        throw e
      }
      live
    }

    private val watcher: Job = scope.launch { watch() }

    /** Whether the connection has opened and, if it had an update check, passed it. */
    val isOpen: Boolean
      get() = connection.isCompleted && !connection.isCancelled

    /**
     * Hands over the connection of this taken spare, or returns null when it failed to open or has
     * ended, so the caller opens its own.
     */
    suspend fun claim(): LiveConnection? =
      try {
        watcher.cancelAndJoin()
        val opened = connection.await()
        // The server may have ended the session, or the spare aged out, just before it was taken.
        val aged = maxSpareAge?.let { openedAt.load()?.let { at -> at.elapsedNow() >= it } } == true
        if (aged || opened.serverEnding?.isCompleted == true) {
          discard()
          null
        } else {
          opened
        }
      } catch (e: Exception) {
        // The taker owns this spare now, so the watcher neither closed it nor logged the failure.
        discard()
        handleFailure(e) { "Opening or checking a spare live connection failed." }
        // Only the caller's own cancellation propagates; a closed pool means connecting directly.
        currentCoroutineContext().ensureActive()
        null
      }

    /** Closes the connection, stopping it first if it is still opening. */
    suspend fun discard() =
      withContext(NonCancellable) {
        // An open stopped midway closes what it opened; a finished one is closed here.
        connection.cancel()
        connection.join()
        opened.exchange(null)?.closeQuietly()
      }

    /**
     * Sends [spare] a no-op context update and throws [UnsupportedOperationException] if the server
     * ends the session within [updateCheckWindow] for refusing it, as happens on models that do not
     * accept context updates.
     */
    private suspend fun checkUpdatesAccepted(spare: LiveConnection) {
      val window = updateCheckWindow ?: return
      // Only a connection that distinguishes a refusal from another end can be checked this way.
      if (spare !is ContextUpdateRefusalSignal) return
      val ending = spare.serverEnding ?: return
      // A session already ending indicates nothing about updates; the spare's watcher drops it.
      if (ending.isCompleted) return
      // A spare has no tools, so removing them changes nothing.
      spare.sendContextUpdate(tools = emptyList())
      val ended = withTimeoutOrNull(window) { ending.join() } != null
      if (!ended) {
        updatesAccepted.store(true)
        return
      }
      // Any other end, or one after another spare passed, is ordinary; the watcher drops the spare.
      if (spare.refusedContextUpdate && !updatesAccepted.load()) {
        throw UnsupportedOperationException("The server ended a live session on a context update.")
      }
    }

    /**
     * Waits while nobody holds the spare, and drops it once the server ends its session or it
     * reaches [maxSpareAge].
     */
    private suspend fun watch() {
      val opened =
        try {
          connection.await()
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          handleFailure(e, shouldWarn = { drop(replaceIfInUse = false) }) {
            "Opening or checking a spare live connection failed."
          }
          return
        }
      val ending = opened.serverEnding
      // With neither, the spare stays until it is taken, dropped or closed.
      if (ending == null && maxSpareAge == null) return
      val ready = checkNotNull(openedAt.load())
      val remaining = maxSpareAge?.let { it - ready.elapsedNow() } ?: Duration.INFINITE
      // Waits without reading, so no response is taken from whoever takes the spare.
      withTimeoutOrNull(remaining) { ending?.join() ?: awaitCancellation() }
      val lived = ready.elapsedNow()
      drop(replaceIfInUse = minLifetimeForReplacement?.let { lived >= it } ?: true)
    }

    /**
     * Removes this spare from the pool unless someone else already has, closes it, and, if
     * [replaceIfInUse] and its setup was requested within [keepWarmFor], opens a replacement.
     * Returns whether this call removed it.
     */
    @CanIgnoreReturnValue
    private suspend fun drop(replaceIfInUse: Boolean): Boolean {
      var replace = false
      val removed = mutex.withLock {
        val pool = pools ?: return@withLock false
        val spares = pool[setup]
        if (spares == null || this !in spares) return@withLock false
        val window = keepWarmFor
        replace =
          replaceIfInUse &&
            window != null &&
            requested[setup]?.let { it.elapsedNow() < window } == true
        val rest = spares - this
        // A setup left with no spares and none coming frees its place for another.
        if (rest.isEmpty() && !replace) {
          pools = pool - setup
          requested = requested - setup
        } else {
          pools = pool + (setup to rest)
        }
        true
      }
      // Whoever removed the spare owns it: a taker, a closing pool, or a dropped setup.
      if (!removed) return false
      discard()
      if (replace) topUp(setup, request, onlyIfPooled = true)
      return true
    }
  }

  /**
   * Fluent builder for [PrewarmingLiveModel], provided primarily for Java callers. Any property
   * left unset falls back to the same default as the constructor.
   */
  @AdkJavaInteropApi
  @ExperimentalLiveApi
  @ExperimentalPrewarmingApi
  @Suppress("ScopeReceiverThis") // Java-style builder for Java interop.
  class Builder {
    private var model: Model? = null
    private var spares: Int = DEFAULT_SPARES
    private var maxSetups: Int = DEFAULT_MAX_SETUPS
    private var keepWarmFor: Duration? = DEFAULT_KEEP_WARM_FOR
    private var minLifetimeForReplacement: Duration? = DEFAULT_MIN_LIFETIME_FOR_REPLACEMENT
    private var maxSpareAge: Duration? = DEFAULT_MAX_SPARE_AGE
    private var updateCheckWindow: Duration? = null
    private var context: CoroutineContext = Dispatchers.Default

    fun model(model: Model): Builder = apply { this.model = model }

    fun spares(spares: Int): Builder = apply { this.spares = spares }

    fun maxSetups(maxSetups: Int): Builder = apply { this.maxSetups = maxSetups }

    fun keepWarmFor(keepWarmFor: Duration?): Builder = apply { this.keepWarmFor = keepWarmFor }

    /**
     * Sets [keepWarmFor] in milliseconds. Java cannot call [keepWarmFor], whose JVM name is mangled
     * because [Duration] is a value class.
     */
    fun keepWarmForMillis(keepWarmForMillis: Long): Builder = apply {
      this.keepWarmFor = keepWarmForMillis.milliseconds
    }

    /**
     * Sets [keepWarmFor] in milliseconds; pass null to never replace spares that end or age out.
     * The non-null overload is kept so Java `int` literals still compile.
     */
    fun keepWarmForMillis(keepWarmForMillis: Long?): Builder = apply {
      this.keepWarmFor = keepWarmForMillis?.milliseconds
    }

    fun minLifetimeForReplacement(minLifetimeForReplacement: Duration?): Builder = apply {
      this.minLifetimeForReplacement = minLifetimeForReplacement
    }

    /**
     * Sets [minLifetimeForReplacement] in milliseconds. Java cannot call
     * [minLifetimeForReplacement], whose JVM name is mangled because [Duration] is a value class.
     */
    fun minLifetimeForReplacementMillis(minLifetimeForReplacementMillis: Long): Builder = apply {
      this.minLifetimeForReplacement = minLifetimeForReplacementMillis.milliseconds
    }

    /**
     * Sets [minLifetimeForReplacement] in milliseconds; pass null to replace ended spares
     * immediately while `keepWarmFor` allows. The non-null overload is kept so Java `int` literals
     * still compile.
     */
    fun minLifetimeForReplacementMillis(minLifetimeForReplacementMillis: Long?): Builder = apply {
      this.minLifetimeForReplacement = minLifetimeForReplacementMillis?.milliseconds
    }

    fun maxSpareAge(maxSpareAge: Duration?): Builder = apply { this.maxSpareAge = maxSpareAge }

    /**
     * Sets [maxSpareAge] in milliseconds. Java cannot call [maxSpareAge], whose JVM name is mangled
     * because [Duration] is a value class.
     */
    fun maxSpareAgeMillis(maxSpareAgeMillis: Long): Builder = apply {
      this.maxSpareAge = maxSpareAgeMillis.milliseconds
    }

    /**
     * Sets [maxSpareAge] in milliseconds; pass null so spares have no age limit. The non-null
     * overload is kept so Java `int` literals still compile.
     */
    fun maxSpareAgeMillis(maxSpareAgeMillis: Long?): Builder = apply {
      this.maxSpareAge = maxSpareAgeMillis?.milliseconds
    }

    fun updateCheckWindow(updateCheckWindow: Duration?): Builder = apply {
      this.updateCheckWindow = updateCheckWindow
    }

    /**
     * Sets [updateCheckWindow] in milliseconds. Java cannot call [updateCheckWindow], whose JVM
     * name is mangled because [Duration] is a value class.
     */
    fun updateCheckWindowMillis(updateCheckWindowMillis: Long): Builder = apply {
      this.updateCheckWindow = updateCheckWindowMillis.milliseconds
    }

    /**
     * Sets [updateCheckWindow] in milliseconds, or to null (the default) to skip the update check.
     * The non-null overload is kept so Java `int` literals still compile.
     */
    fun updateCheckWindowMillis(updateCheckWindowMillis: Long?): Builder = apply {
      this.updateCheckWindow = updateCheckWindowMillis?.milliseconds
    }

    fun context(context: CoroutineContext): Builder = apply { this.context = context }

    fun build(): PrewarmingLiveModel =
      PrewarmingLiveModel(
        model = checkNotNull(model) { "model is required." },
        spares = spares,
        maxSetups = maxSetups,
        keepWarmFor = keepWarmFor,
        minLifetimeForReplacement = minLifetimeForReplacement,
        maxSpareAge = maxSpareAge,
        updateCheckWindow = updateCheckWindow,
        context = context,
      )
  }

  companion object {
    private val logger = LoggerFactory.getLogger(PrewarmingLiveModel::class)

    private const val DEFAULT_SPARES = 1
    internal const val DEFAULT_MAX_SETUPS = 8
    private val DEFAULT_KEEP_WARM_FOR = 30.minutes
    private val DEFAULT_MIN_LIFETIME_FOR_REPLACEMENT = 1.minutes
    private val DEFAULT_MAX_SPARE_AGE = 5.minutes

    @AdkJavaInteropApi
    @ExperimentalLiveApi
    @ExperimentalPrewarmingApi
    @JvmStatic
    fun builder(): Builder = Builder()

    private suspend fun LiveConnection.closeQuietly() {
      try {
        closeSession()
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        logger.warn(e) { "Closing a spare live connection failed." }
      }
    }
  }
}

/** Live connection setup: the parts of a request that a context update cannot change. */
private data class Setup(
  val model: String,
  val config: GenerateContentConfig,
  val liveConnectConfig: LiveConnectConfig,
)

/** Returns a spare request with no history, instruction, or tools; the taker sends the history. */
private fun LlmRequest.forSpare(): LlmRequest =
  copy(
    contents = emptyList(),
    config = config.copy(systemInstruction = null, tools = null),
    toolsDict = emptyList(),
  )

/**
 * A live connection that reports whether the server closed its session because of a context update.
 */
internal interface ContextUpdateRefusalSignal {
  /** Whether the session was closed with the error the server sends for a refused update. */
  val refusedContextUpdate: Boolean
}

/**
 * The WebSocket close code (invalid payload) the server uses to end a session on a refused update.
 */
internal const val CONTEXT_UPDATE_REFUSED_CLOSE_CODE = 1007
