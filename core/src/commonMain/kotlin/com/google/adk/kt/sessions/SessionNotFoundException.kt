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
 * Thrown when a run names a session that does not exist and the runner does not create missing
 * sessions, as set by `autoCreateSession` on
 * [AbstractRunner][com.google.adk.kt.runners.AbstractRunner]. It is an [IllegalArgumentException]
 * because the caller passed an id that names no session.
 */
class SessionNotFoundException : IllegalArgumentException("Session not found")
