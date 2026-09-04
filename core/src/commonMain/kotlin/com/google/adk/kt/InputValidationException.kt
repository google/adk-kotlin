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
 * Thrown when a caller handed the SDK something it will not accept.
 *
 * A serving layer catches this to answer 400 with [message] as the body, so the message should name
 * the field that was rejected and what is wrong with it. Deliberately narrower than
 * [IllegalArgumentException], which every `require(...)` in the SDK throws: a handler that means
 * "the caller sent this" must not also fire for an internal invariant.
 */
class InputValidationException(message: String = "Invalid input.", cause: Throwable? = null) :
  IllegalArgumentException(message, cause)
