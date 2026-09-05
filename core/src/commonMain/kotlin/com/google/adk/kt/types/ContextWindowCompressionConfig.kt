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

package com.google.adk.kt.types

import kotlinx.serialization.Serializable

/**
 * Enables context window compression, which keeps a live session's context below a given length so
 * a long conversation does not exhaust it.
 *
 * @property triggerTokens Token count, measured before a turn runs, that triggers compression.
 * @property slidingWindow The sliding-window compression mechanism to apply.
 */
@Serializable
data class ContextWindowCompressionConfig(
  val triggerTokens: Long? = null,
  val slidingWindow: SlidingWindow? = null,
)
