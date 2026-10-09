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
package com.google.adk.kt.models.chatcompletions

/**
 * An error returned by a Chat Completions endpoint. [statusCode] is the HTTP status, or null for an
 * error reported in a successful response or inside a stream. The errors [ChatCompletions] throws
 * never include a request or response body, so they stay free of customer content.
 */
class ChatCompletionsApiException
internal constructor(message: String, cause: Throwable? = null, val statusCode: Int? = null) :
  RuntimeException(message, cause)
