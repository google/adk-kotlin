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
package com.google.adk.kt.testing

import com.google.adk.kt.sessions.Session
import com.google.adk.kt.sessions.SessionKey

/**
 * A [Session] with a [SessionKey] and otherwise default state and event list.
 *
 * Convenience for tests that need a session value object but don't care about its contents. For
 * tests that need a specific [SessionKey], pass `key = SessionKey(...)`.
 */
fun testSession(
  appName: String = "test_app_name",
  userId: String = "test_user_id",
  id: String? = "test_session_id",
): Session = Session(key = SessionKey(appName = appName, userId = userId, id = id))
