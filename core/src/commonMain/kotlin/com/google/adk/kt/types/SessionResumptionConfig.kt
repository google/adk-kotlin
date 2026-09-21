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
 * Configures the session resumption mechanism.
 *
 * Setting this asks the server to send session resumption updates over the connection.
 *
 * @property handle Resumption handle of a previous session to restore. If absent, a new session is
 *   started.
 * @property transparent Whether the server should report the last consumed client message index, so
 *   a reconnect can resume without replaying. Only honoured on the Vertex backend.
 */
@Serializable
data class SessionResumptionConfig(val handle: String? = null, val transparent: Boolean? = null)
