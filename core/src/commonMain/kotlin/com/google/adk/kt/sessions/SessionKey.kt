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

package com.google.adk.kt.sessions

/**
 * Composite identifier for a [Session].
 *
 * @property appName Name of the application that owns the session.
 * @property userId Identifier of the end user the session belongs to.
 * @property id Unique session identifier within the `(appName, userId)` namespace. May be `null`
 *   when passed to [SessionService.createSession] to request that the service generate one. Methods
 *   that address an existing session ([SessionService.getSession], [SessionService.deleteSession],
 *   [SessionService.listEvents], [SessionService.flush]) require a non-null [id].
 */
data class SessionKey(val appName: String, val userId: String, val id: String?)
