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
 * A handle for resuming this session on a later connection.
 *
 * Sent only when session resumption is requested. The handle arrives at points of the server's
 * choosing, including after a turn has completed, so a reader that stops at the end of a turn can
 * miss it.
 *
 * @property newHandle The handle to pass as [SessionResumptionConfig.handle] when reconnecting.
 * @property resumable Whether the session can currently be resumed.
 * @property lastConsumedClientMessageIndex Index of the last client message the server processed,
 *   sent only for a transparent session, so a reconnect can replay from that point.
 */
@Serializable
data class LiveServerSessionResumptionUpdate(
  val newHandle: String? = null,
  val resumable: Boolean? = null,
  val lastConsumedClientMessageIndex: Long? = null,
)
