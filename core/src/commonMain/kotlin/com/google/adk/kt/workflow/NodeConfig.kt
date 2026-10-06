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

package com.google.adk.kt.workflow

import com.google.adk.kt.annotations.AdkJavaInteropApi
import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import kotlin.jvm.JvmStatic
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Bundles a node's retry policy and execution timeout. A null [timeout] imposes no limit, so an
 * attempt is then bounded only by any ambient deadline. Validation lives here, so every [Node]
 * implementation inherits it.
 *
 * @property retryConfig How the node is retried when it raises. Null does not retry.
 * @property timeout How long an attempt may run before it is cancelled and treated as a failure.
 *   Must be positive; null imposes no limit.
 */
@ExperimentalWorkflowApi
data class NodeConfig(val retryConfig: RetryConfig? = null, val timeout: Duration? = null) {
  init {
    // A non-positive timeout is meaningless -- it fires at once or never -- so it is rejected here,
    // unlike a retry delay, where zero is a valid "retry immediately" and RetryConfig allows it.
    require(timeout == null || timeout > Duration.ZERO) {
      "timeout must be positive, or null for no timeout."
    }
  }

  /** Returns [timeout] in whole milliseconds, or `null`. Java cannot read [timeout] (mangled). */
  fun timeoutMillis(): Long? = timeout?.inWholeMilliseconds

  /**
   * Returns a [Builder] initialized with this instance's properties, primarily for Java callers.
   * Prefer it over `copy` from Java: `copy` takes every property positionally, so its signature
   * changes whenever a property is added.
   */
  @AdkJavaInteropApi fun toBuilder(): Builder = Builder().retryConfig(retryConfig).timeout(timeout)

  /**
   * Fluent builder for [NodeConfig], provided primarily for Java callers. Any property left unset
   * falls back to the same default as the constructor.
   */
  @AdkJavaInteropApi
  @Suppress("ScopeReceiverThis") // Java-style builder for Java interop.
  class Builder {
    private var retryConfig: RetryConfig? = null
    private var timeout: Duration? = null

    fun retryConfig(retryConfig: RetryConfig?): Builder = apply { this.retryConfig = retryConfig }

    // Let toBuilder copy the timeout exactly; the millisecond setter would truncate it.
    internal fun timeout(timeout: Duration?): Builder = apply { this.timeout = timeout }

    /** Sets [NodeConfig.timeout] in milliseconds. */
    fun timeoutMillis(timeoutMillis: Long): Builder = apply {
      this.timeout = timeoutMillis.milliseconds
    }

    /**
     * Sets [NodeConfig.timeout] in milliseconds, or clears it when `null`. The non-null overload
     * lets Java `int` literals compile.
     */
    fun timeoutMillis(timeoutMillis: Long?): Builder = apply {
      this.timeout = timeoutMillis?.milliseconds
    }

    fun build(): NodeConfig = NodeConfig(retryConfig = retryConfig, timeout = timeout)
  }

  companion object {
    @AdkJavaInteropApi @JvmStatic fun builder(): Builder = Builder()
  }
}
