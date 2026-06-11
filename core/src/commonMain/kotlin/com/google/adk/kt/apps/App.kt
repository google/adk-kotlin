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

package com.google.adk.kt.apps

import com.google.adk.kt.agents.BaseAgent
import com.google.adk.kt.summarizer.EventsCompactionConfig

/**
 * Represents an LLM-backed agentic application.
 *
 * An [App] is the top-level container for an agentic system powered by LLMs. It bundles an
 * application name together with the [rootAgent] that serves as the root of the agent tree,
 * enabling coordination and communication across all agents in the hierarchy.
 *
 * [appName] must be a valid identifier (letters, digits, and underscores only, not starting with a
 * digit) and must not be the reserved value `"user"`, which is reserved for end-user input.
 * Construction throws [IllegalArgumentException] if [appName] does not meet these requirements.
 *
 * @property appName The application name.
 * @property rootAgent The root agent of the application's agent tree.
 * @property eventsCompactionConfig Optional configuration controlling context-compaction strategies
 *   for sessions of this application. When `null`, no compaction runs.
 */
data class App(
  val appName: String,
  val rootAgent: BaseAgent,
  val eventsCompactionConfig: EventsCompactionConfig? = null,
) {
  init {
    require(IDENTIFIER_REGEX.matches(appName)) {
      "Invalid app name '$appName': must be a valid identifier consisting of letters, digits, " +
        "and underscores."
    }
    require(appName != RESERVED_NAME) {
      "App name cannot be '$RESERVED_NAME'; reserved for end-user input."
    }
  }

  private companion object {
    val IDENTIFIER_REGEX = Regex("[a-zA-Z_][a-zA-Z0-9_]*")
    const val RESERVED_NAME = "user"
  }
}
