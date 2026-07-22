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

package com.google.adk.kt.sessions.dto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Wire-level representation of `EventActions` from `session.proto`.
 *
 * [stateDelta] is a raw [JsonElement] so the mapper can control the encoding of the
 * [com.google.adk.kt.sessions.State.REMOVED] sentinel (emitted as JSON `null`, decoded back). Both
 * [transferAgent] (canonical wire name) and [transferToAgent] (accepted for compatibility) are
 * modeled: the mapper reads either and always writes [transferAgent].
 */
@Serializable
internal data class EventActionsDto(
  val skipSummarization: Boolean? = null,
  val stateDelta: JsonElement? = null,
  val artifactDelta: Map<String, Int>? = null,
  val transferAgent: String? = null,
  val transferToAgent: String? = null,
  val escalate: Boolean? = null,
  val endOfAgent: Boolean? = null,
)
