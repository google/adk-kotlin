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
import com.google.adk.kt.agents.ContextCacheConfig
import com.google.adk.kt.agents.ResumabilityConfig
import com.google.adk.kt.plugins.Plugin
import com.google.adk.kt.summarizer.EventsCompactionConfig
import kotlin.jvm.JvmStatic

/**
 * Represents an LLM-backed agentic application.
 *
 * An [App] is the top-level container for an agentic system powered by LLMs. It bundles an
 * application name together with the [rootAgent] that serves as the root of the agent tree,
 * enabling coordination and communication across all agents in the hierarchy. It also carries
 * application-wide configuration, such as [plugins] and [resumabilityConfig], that the runner
 * applies to every session it runs.
 *
 * [appName] must start with a letter and may contain only letters, digits, underscores, and
 * hyphens, and must not be the reserved value `"user"`, which is reserved for end-user input.
 * Construction throws [IllegalArgumentException] if [appName] does not meet these requirements.
 *
 * @property appName The application name.
 * @property rootAgent The root agent of the application's agent tree.
 * @property plugins Application-wide [Plugin]s providing shared callbacks and services to the
 *   entire system. Defaults to an empty list.
 * @property resumabilityConfig Optional resumability configuration applied to the application's
 *   sessions. When `null`, resumability is disabled.
 * @property eventsCompactionConfig Optional configuration controlling context-compaction strategies
 *   for sessions of this application. When `null`, no compaction runs.
 * @property contextCacheConfig Optional context cache configuration that applies to all LLM agents
 *   in the app. When `null`, context caching is disabled.
 */
data class App(
  val appName: String,
  val rootAgent: BaseAgent,
  val plugins: List<Plugin> = emptyList(),
  val resumabilityConfig: ResumabilityConfig? = null,
  val eventsCompactionConfig: EventsCompactionConfig? = null,
  val contextCacheConfig: ContextCacheConfig? = null,
) {
  init {
    require(VALID_APP_NAME_REGEX.matches(appName)) {
      "Invalid app name '$appName': must start with a letter and can only consist of letters, " +
        "digits, underscores, and hyphens."
    }
    require(appName != RESERVED_NAME) {
      "App name cannot be '$RESERVED_NAME'; reserved for end-user input."
    }
  }

  /**
   * Fluent builder for [App], provided primarily for Java callers. Any property left unset falls
   * back to the same default as the constructor.
   */
  @Suppress("ScopeReceiverThis") // Java-style builder for Java interop.
  class Builder {
    private var appName: String? = null
    private var rootAgent: BaseAgent? = null
    private var plugins: List<Plugin> = emptyList()
    private var resumabilityConfig: ResumabilityConfig? = null
    private var eventsCompactionConfig: EventsCompactionConfig? = null
    private var contextCacheConfig: ContextCacheConfig? = null

    fun appName(appName: String): Builder = apply { this.appName = appName }

    fun rootAgent(rootAgent: BaseAgent): Builder = apply { this.rootAgent = rootAgent }

    fun plugins(plugins: List<Plugin>): Builder = apply { this.plugins = plugins }

    fun plugins(vararg plugins: Plugin): Builder = apply { this.plugins = plugins.toList() }

    fun resumabilityConfig(resumabilityConfig: ResumabilityConfig?): Builder = apply {
      this.resumabilityConfig = resumabilityConfig
    }

    fun eventsCompactionConfig(eventsCompactionConfig: EventsCompactionConfig?): Builder = apply {
      this.eventsCompactionConfig = eventsCompactionConfig
    }

    fun contextCacheConfig(contextCacheConfig: ContextCacheConfig?): Builder = apply {
      this.contextCacheConfig = contextCacheConfig
    }

    fun build(): App =
      App(
        appName = checkNotNull(appName) { "App.Builder requires appName to be set." },
        rootAgent = checkNotNull(rootAgent) { "App.Builder requires rootAgent to be set." },
        plugins = plugins,
        resumabilityConfig = resumabilityConfig,
        eventsCompactionConfig = eventsCompactionConfig,
        contextCacheConfig = contextCacheConfig,
      )
  }

  companion object {
    @JvmStatic fun builder(): Builder = Builder()

    private val VALID_APP_NAME_REGEX = Regex("[a-zA-Z][a-zA-Z0-9_-]*")
    private const val RESERVED_NAME = "user"
  }
}
