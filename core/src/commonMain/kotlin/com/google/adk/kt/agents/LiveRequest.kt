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

import com.google.adk.kt.models.RealtimeInput
import com.google.adk.kt.types.Content

/**
 * One item sent to a live agent through a [LiveRequestQueue].
 *
 * Any combination of [content], [realtimeInput] and [stateDelta] may be set; nothing enforces one
 * over another, and nothing applies that delta to the session yet. A [close] carrying a payload has
 * the payload forwarded before the queue closes.
 *
 * @property content Content to send in turn-by-turn mode.
 * @property realtimeInput Media or an activity signal to send in realtime mode.
 * @property close Closes the queue.
 * @property partial Whether [content] is a partial turn update that does not complete the model's
 *   current turn.
 * @property stateDelta State changes intended for the session.
 */
data class LiveRequest(
  val content: Content? = null,
  val realtimeInput: RealtimeInput? = null,
  val close: Boolean = false,
  val partial: Boolean = false,
  val stateDelta: Map<String, Any>? = null,
)
