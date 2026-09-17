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
 * Thrown when a [SessionService] operation fails. A caller that needs to act on one particular
 * failure matches [message] against the constants declared here, so this stays one type rather than
 * a hierarchy.
 */
class SessionException(message: String, cause: Throwable? = null) :
  RuntimeException(message, cause) {

  companion object {
    /**
     * [message] when [SessionService.createSession] is given a session id that is already taken.
     */
    const val SESSION_ALREADY_EXISTS: String = "Session already exists"
  }
}
