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
 * Thrown when the session a caller named is not there.
 *
 * A serving layer catches this to answer 404 with [message] as the body, so the message should name
 * the session that was meant rather than restate that one was missing. A caller that learns of the
 * miss from a store passes that failure as [cause] so the trace does not stop at the translation.
 */
class SessionNotFoundException(message: String = "Session not found.", cause: Throwable? = null) :
  IllegalArgumentException(message, cause)
