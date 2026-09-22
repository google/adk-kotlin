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

import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import kotlin.time.Duration

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
}
