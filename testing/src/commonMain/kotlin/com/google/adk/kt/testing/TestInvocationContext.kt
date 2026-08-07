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
package com.google.adk.kt.testing

import com.google.adk.kt.agents.BaseAgent
import com.google.adk.kt.agents.InvocationContext
import com.google.adk.kt.agents.ResumabilityConfig
import com.google.adk.kt.agents.RunConfig
import com.google.adk.kt.artifacts.ArtifactService
import com.google.adk.kt.events.EventActions
import com.google.adk.kt.events.ToolConfirmation
import com.google.adk.kt.memory.MemoryService
import com.google.adk.kt.sessions.Session
import com.google.adk.kt.sessions.SessionService
import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.types.Content

/**
 * An [InvocationContext] populated with sensible defaults for tests.
 *
 * Most tests only care about a single field (e.g. the [agent], or a particular service); this
 * helper lets the test specify just the fields it cares about and inherit the rest from a
 * test-canonical baseline:
 * - `session = ` [testSession]`()` (`("test_app_name", "test_user_id", "test_session_id")`)
 * - `runConfig = `[RunConfig]`()`
 * - `agent = `[DummyAgent]`()` (defaults to name `"test-agent"`)
 * - `invocationId = "test-invocation-id"`
 *
 * Service params (`sessionService`, `artifactService`, `memoryService`) default to `null`, matching
 * the production [InvocationContext] defaults; tests that exercise a service should pass one
 * explicitly (e.g. `sessionService = InMemorySessionService()`).
 *
 * Override any default with a named argument:
 * ```kotlin
 * val context = testInvocationContext(agent = myAgent, memoryService = recording)
 * ```
 */
fun testInvocationContext(
  agent: BaseAgent = DummyAgent(),
  session: Session = testSession(),
  runConfig: RunConfig? = RunConfig(),
  invocationId: String = "test-invocation-id",
  branch: String? = null,
  sessionService: SessionService? = null,
  artifactService: ArtifactService? = null,
  memoryService: MemoryService? = null,
  resumabilityConfig: ResumabilityConfig? = null,
  userContent: Content? = null,
): InvocationContext =
  InvocationContext(
    session = session,
    runConfig = runConfig,
    agent = agent,
    branch = branch,
    invocationId = invocationId,
    artifactService = artifactService,
    memoryService = memoryService,
    sessionService = sessionService,
    resumabilityConfig = resumabilityConfig,
    userContent = userContent,
  )

/**
 * A [ToolContext] wrapping an [InvocationContext] built by [testInvocationContext].
 *
 * Most tool tests need a `ToolContext` only to satisfy the API surface; this helper avoids
 * boilerplate. Use the [invocationContext] parameter to supply a pre-built context if a test needs
 * to inspect or share it across calls.
 */
fun testToolContext(
  invocationContext: InvocationContext = testInvocationContext(),
  actions: EventActions = EventActions(),
  functionCallId: String? = null,
  eventId: String? = null,
  toolConfirmation: ToolConfirmation? = null,
): ToolContext =
  ToolContext(
    invocationContext = invocationContext,
    actions = actions,
    functionCallId = functionCallId,
    eventId = eventId,
    toolConfirmation = toolConfirmation,
  )
