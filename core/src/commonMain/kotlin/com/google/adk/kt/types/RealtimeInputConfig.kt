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
 * Configures how realtime input is interpreted as conversational turns.
 *
 * @property automaticActivityDetection Server-side activity detection. If unset, automatic
 *   detection is enabled; if it is disabled the client must send activity signals.
 * @property activityHandling What effect the start of activity has on an in-flight response.
 * @property turnCoverage Which input is included in the user's turn.
 */
@Serializable
data class RealtimeInputConfig(
  val automaticActivityDetection: AutomaticActivityDetection? = null,
  val activityHandling: ActivityHandling? = null,
  val turnCoverage: TurnCoverage? = null,
)
