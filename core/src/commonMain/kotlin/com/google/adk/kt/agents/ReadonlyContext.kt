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

package com.google.adk.kt.agents

import com.google.adk.kt.artifacts.ArtifactService
import com.google.adk.kt.events.Event
import com.google.adk.kt.memory.MemoryService
import com.google.adk.kt.sessions.Session
import com.google.adk.kt.types.Content

/** A readonly view of the invocation context. */
interface ReadonlyContext {
  /** The session that this invocation is a part of. */
  val session: Session

  /** The run configuration for this invocation. */
  val runConfig: RunConfig?

  /** The unique ID of this invocation. */
  val invocationId: String

  /** The name of the agent that is being invoked. */
  val agentName: String

  /** The state of the session. */
  val state: Map<String, Any>

  /** The user ID of the user that initiated the session. */
  val userId: String

  /** The user content that this invocation is processing. */
  val userContent: Content?

  /** The branch of the invocation context. */
  val branch: String?

  /** The ArtifactService instance. */
  val artifactService: ArtifactService?

  /** The MemoryService instance. */
  val memoryService: MemoryService?

  /**
   * Returns the events from the current session.
   *
   * @param currentInvocation Whether to filter the events by the current invocation.
   * @param currentBranch Whether to filter the events by the current branch. The rule is
   *   author-asymmetric: a user event matches this branch, a descendant sub-branch, or no branch,
   *   and one carrying function responses must also answer a call issued on this branch or below,
   *   while every other event must sit on exactly this branch.
   * @return A list of events from the current session.
   */
  suspend fun getEvents(
    currentInvocation: Boolean = false,
    currentBranch: Boolean = false,
  ): List<Event>
}

/** Extension function to convert an [InvocationContext] to a [ReadonlyContext]. */
internal fun InvocationContext.toReadonlyContext(): ReadonlyContext = ReadonlyContextImpl(this)
