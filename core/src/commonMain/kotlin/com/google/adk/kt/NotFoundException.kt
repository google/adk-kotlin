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

package com.google.adk.kt

/**
 * Thrown when something addressed by id does not exist.
 *
 * Any layer may throw this, and a serving layer catches it to answer 404 with [message] as the
 * body, so the message should name what was missing rather than restate that something was. A store
 * that learns of the miss from below passes that failure as [cause], so the trace does not stop at
 * the translation.
 */
class NotFoundException(
  message: String = "The requested item was not found.",
  cause: Throwable? = null,
) : Exception(message, cause)
