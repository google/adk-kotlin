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
package com.google.adk.kt.models

/**
 * A [Model] that also supports live (bidirectional streaming) connections.
 *
 * This is a separate capability interface rather than a widening of [Model] so that support is
 * visible in the type system: a caller tests `model is LiveModel` and can refuse the run up front,
 * instead of opening a connection to find out. Only backends that genuinely speak the live protocol
 * implement it.
 */
interface LiveModel : Model {
  /**
   * Opens a live connection, configured by [LlmRequest.liveConnectConfig].
   *
   * The returned connection is open but not yet primed; send history or content to start the
   * conversation, and close it when the conversation ends.
   *
   * @param request The request whose live config, model name and tools configure the session.
   */
  suspend fun connect(request: LlmRequest): LiveConnection
}
