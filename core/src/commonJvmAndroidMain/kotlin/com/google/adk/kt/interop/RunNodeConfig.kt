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

package com.google.adk.kt.interop

import com.google.adk.kt.agents.Context
import com.google.adk.kt.annotations.AdkJavaInteropApi
import com.google.adk.kt.annotations.ExperimentalWorkflowApi

/**
 * Options for [InteropContextView.runNode]. Each property has the default and meaning of the
 * [Context.runNode] argument with the same name.
 */
@AdkJavaInteropApi
@ExperimentalWorkflowApi
data class RunNodeConfig(
  val runId: String? = null,
  val useAsOutput: Boolean = false,
  val useSubBranch: Boolean = false,
  val overrideBranch: String? = null,
  val overrideIsolationScope: String? = null,
  val raiseOnWait: Boolean = false,
) {

  /**
   * Returns a [Builder] initialized with this instance's properties, primarily for Java callers.
   * Prefer it over `copy` from Java: `copy` takes every property positionally, so its signature
   * changes whenever a property is added.
   */
  @AdkJavaInteropApi
  fun toBuilder(): Builder =
    Builder()
      .runId(runId)
      .useAsOutput(useAsOutput)
      .useSubBranch(useSubBranch)
      .overrideBranch(overrideBranch)
      .overrideIsolationScope(overrideIsolationScope)
      .raiseOnWait(raiseOnWait)

  /**
   * Fluent builder for [RunNodeConfig], provided primarily for Java callers. Any property left
   * unset falls back to the same default as the constructor.
   */
  @AdkJavaInteropApi
  @Suppress("ScopeReceiverThis") // Java-style builder for Java interop.
  class Builder {
    private var runId: String? = null
    private var useAsOutput: Boolean = false
    private var useSubBranch: Boolean = false
    private var overrideBranch: String? = null
    private var overrideIsolationScope: String? = null
    private var raiseOnWait: Boolean = false

    fun runId(runId: String?): Builder = apply { this.runId = runId }

    fun useAsOutput(useAsOutput: Boolean): Builder = apply { this.useAsOutput = useAsOutput }

    fun useSubBranch(useSubBranch: Boolean): Builder = apply { this.useSubBranch = useSubBranch }

    fun overrideBranch(overrideBranch: String?): Builder = apply {
      this.overrideBranch = overrideBranch
    }

    fun overrideIsolationScope(overrideIsolationScope: String?): Builder = apply {
      this.overrideIsolationScope = overrideIsolationScope
    }

    fun raiseOnWait(raiseOnWait: Boolean): Builder = apply { this.raiseOnWait = raiseOnWait }

    fun build(): RunNodeConfig =
      RunNodeConfig(
        runId = runId,
        useAsOutput = useAsOutput,
        useSubBranch = useSubBranch,
        overrideBranch = overrideBranch,
        overrideIsolationScope = overrideIsolationScope,
        raiseOnWait = raiseOnWait,
      )
  }

  companion object {
    @AdkJavaInteropApi @JvmStatic fun builder(): Builder = Builder()
  }
}
