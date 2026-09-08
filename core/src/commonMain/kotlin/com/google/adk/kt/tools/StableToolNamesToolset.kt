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

package com.google.adk.kt.tools

import com.google.adk.kt.agents.ReadonlyContext
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.types.FunctionDeclaration
import kotlin.jvm.JvmOverloads
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Wraps a [Toolset] whose tool names can change between turns so a name the model has already been
 * offered keeps working for the rest of the session.
 *
 * A toolset built over a changing catalog -- installed apps, a remote server's tool list -- can
 * rename a tool between turns, while the earlier name survives in the conversation as a function
 * call and its response. A model that re-reads the declarations adapts; a smaller one often repeats
 * the name it used before, and the call then resolves to no tool at all.
 *
 * Every name [delegate] returns is remembered against the session it was returned for. When a
 * remembered name is missing from a later turn, this toolset returns it again alongside the live
 * tools, bound to the tool it was last issued for, and *undeclared*: it carries no
 * [FunctionDeclaration], so it is never declared to the model and costs no declaration tokens. Only
 * names that were actually issued are kept -- no guessing, no matching on shape.
 *
 * A live name always wins: while [delegate] returns a name itself, that tool is used and no
 * retained one shadows it. Re-issuing a name for a different tool replaces what it routes to, so a
 * retained name follows the most recent tool it was issued for.
 *
 * Retention is per session and bounded by [maxRetainedNamesPerSession] and [maxRetainedSessions],
 * evicting the least recently issued first; [close] closes [delegate]. Tools are only retained when
 * the [ReadonlyContext] identifies a session, so a call with no context passes straight through.
 *
 * @property delegate The toolset whose names are kept routable. Closed by [close].
 * @property maxRetainedNamesPerSession Most names kept routable for one session after [delegate]
 *   stops returning them. Names [delegate] still returns are not counted against it.
 * @property maxRetainedSessions Most sessions retained at once.
 */
class StableToolNamesToolset
@JvmOverloads
constructor(
  private val delegate: Toolset,
  private val maxRetainedNamesPerSession: Int = DEFAULT_MAX_RETAINED_NAMES_PER_SESSION,
  private val maxRetainedSessions: Int = DEFAULT_MAX_RETAINED_SESSIONS,
) : Toolset {

  init {
    require(maxRetainedNamesPerSession > 0) { "maxRetainedNamesPerSession must be positive" }
    require(maxRetainedSessions > 0) { "maxRetainedSessions must be positive" }
  }

  // Insertion-ordered so eviction can drop the name issued longest ago; guarded because tools are
  // resolved concurrently under a ParallelAgent.
  private val retainedBySession = LinkedHashMap<SessionKey, LinkedHashMap<String, BaseTool>>()
  private val mutex = Mutex()

  override suspend fun getTools(readonlyContext: ReadonlyContext?): List<BaseTool> {
    val liveTools = delegate.getTools(readonlyContext)
    val sessionKey = readonlyContext?.session?.key?.takeIf { it.id != null } ?: return liveTools
    return liveTools + recordAndRecall(sessionKey, liveTools)
  }

  /** Records this turn's names for [sessionKey] and returns the retained names it is missing. */
  private suspend fun recordAndRecall(
    sessionKey: SessionKey,
    liveTools: List<BaseTool>,
  ): List<BaseTool> = mutex.withLock {
    // Removed first so the session in use is the most recent one and is never the one evicted.
    val retained = retainedBySession.remove(sessionKey) ?: LinkedHashMap()
    retainedBySession[sessionKey] = retained
    retainedBySession.evictDownTo(maxRetainedSessions)
    for (tool in liveTools) {
      // Removed first so re-issuing a name also makes it the most recently issued.
      retained.remove(tool.name)
      retained[tool.name] = tool
    }
    val liveNames = liveTools.mapTo(mutableSetOf()) { it.name }
    retained.evictDownTo(maxRetainedNamesPerSession, exempt = liveNames)
    retained.filterKeys { it !in liveNames }.values.map(::UndeclaredTool)
  }

  override suspend fun processLlmRequest(toolContext: ToolContext, llmRequest: LlmRequest) =
    delegate.processLlmRequest(toolContext, llmRequest)

  override fun close() {
    // The map is left alone: dropping it here would mutate it off-lock, and it is unreachable
    // garbage once this toolset is.
    delegate.close()
  }

  private companion object {
    const val DEFAULT_MAX_RETAINED_NAMES_PER_SESSION = 32
    const val DEFAULT_MAX_RETAINED_SESSIONS = 8

    /** Drops entries from the front, which insertion order makes the oldest, down to [limit]. */
    fun <K, V> LinkedHashMap<K, V>.evictDownTo(limit: Int) {
      while (size > limit) remove(keys.first())
    }

    /**
     * Drops the oldest entries outside [exempt] until at most [limit] of them are left, so the
     * limit bounds the names being kept alive rather than the ones still being handed out.
     */
    fun <K, V> LinkedHashMap<K, V>.evictDownTo(limit: Int, exempt: Set<K>) {
      var evictable = keys.count { it !in exempt }
      val keys = keys.iterator()
      while (evictable > limit && keys.hasNext()) {
        if (keys.next() !in exempt) {
          keys.remove()
          evictable--
        }
      }
    }
  }
}

/**
 * Runs [delegate] under the name it was issued with while staying out of the declarations: it
 * declares nothing, so the name is never declared to the model, and any request processing the
 * delegate does is left to the turn where it is live. A delegate that contributes instructions or
 * artifacts through that hook therefore contributes none of them on a turn where only its retained
 * name survives.
 */
private class UndeclaredTool(private val delegate: BaseTool) :
  BaseTool(
    name = delegate.name,
    description = delegate.description,
    isLongRunning = delegate.isLongRunning,
    customMetadata = delegate.customMetadata,
  ) {
  override fun declaration(): FunctionDeclaration? = null

  override suspend fun run(context: ToolContext, args: Map<String, Any?>): Any =
    delegate.run(context, args)
}
