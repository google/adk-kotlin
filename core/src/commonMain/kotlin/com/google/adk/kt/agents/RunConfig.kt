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

import com.google.adk.kt.logging.LoggerFactory
import kotlin.jvm.JvmStatic

/**
 * Streaming modes for agent execution.
 *
 * This enum defines different streaming behaviors for how the agent returns events as model
 * response.
 */
enum class StreamingMode {
  /**
   * Non-streaming mode (default).
   *
   * In this mode:
   * - The runner returns one single content in a turn (one user / model interaction).
   * - No partial/intermediate events are produced
   * - Suitable for: CLI tools, batch processing, synchronous workflows
   */
  NONE,

  /**
   * Server-Sent Events (SSE) streaming mode.
   *
   * In this mode:
   * - The runner yields events progressively as the LLM generates responses
   * - Both partial events (streaming chunks) and aggregated events are yielded
   * - Suitable for: real-time display with typewriter effects in Web UIs, chat applications,
   *   interactive displays
   */
  SSE,
}

/**
 * Configs for runtime behavior of agents.
 *
 * @property streamingMode Streaming mode, NONE or SSE.
 * @property maxLlmCalls Limit on the total number of LLM calls per run. A positive value is
 *   enforced; a value <= 0 means unbounded.
 * @property customMetadata Custom metadata for the current invocation.
 */
data class RunConfig(
  val streamingMode: StreamingMode = StreamingMode.NONE,
  val maxLlmCalls: Int = 500,
  val customMetadata: Map<String, Any>? = null,
) {
  init {
    require(maxLlmCalls != Int.MAX_VALUE) { "maxLlmCalls should be less than Int.MAX_VALUE." }
    if (maxLlmCalls <= 0) {
      logger.warn {
        "maxLlmCalls <= 0 disables the LLM-call limit, risking never-ending agent loops."
      }
    }
  }

  /**
   * Fluent builder for [RunConfig], provided primarily for Java callers. Any property left unset
   * falls back to the same default as the constructor.
   */
  @Suppress("ScopeReceiverThis") // Java-style builder for Java interop.
  class Builder {
    private var streamingMode: StreamingMode = StreamingMode.NONE
    private var maxLlmCalls: Int = 500
    private var customMetadata: Map<String, Any>? = null

    fun streamingMode(streamingMode: StreamingMode): Builder = apply {
      this.streamingMode = streamingMode
    }

    fun maxLlmCalls(maxLlmCalls: Int): Builder = apply { this.maxLlmCalls = maxLlmCalls }

    fun customMetadata(customMetadata: Map<String, Any>?): Builder = apply {
      this.customMetadata = customMetadata
    }

    fun build(): RunConfig =
      RunConfig(
        streamingMode = streamingMode,
        maxLlmCalls = maxLlmCalls,
        customMetadata = customMetadata,
      )
  }

  companion object {
    @JvmStatic fun builder(): Builder = Builder()

    private val logger = LoggerFactory.getLogger(RunConfig::class)
  }
}
