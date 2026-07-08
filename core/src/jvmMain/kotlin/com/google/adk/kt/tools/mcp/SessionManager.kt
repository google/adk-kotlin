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

package com.google.adk.kt.tools.mcp

import io.modelcontextprotocol.client.McpAsyncClient

/**
 * Owns and manages MCP client sessions.
 *
 * Implementations are the single owner of the sessions they hand out: sessions are pooled and
 * shared (so a stdio server is backed by exactly one child process), transparently replaced when
 * they die, and torn down wholesale on [closeAll]. Callers ([McpTool], [McpToolset]) hold a
 * reference to the manager rather than caching sessions themselves.
 */
internal interface SessionManager {
  /**
   * Returns an initialized session for the given [headers], creating and initializing one if none
   * is pooled yet. Sessions are keyed so that equivalent [headers] share a single session (a stdio
   * connection ignores headers and always shares one session).
   *
   * Pass the failed client as [stale] to recover from a dead session: if [stale] is still the
   * pooled session for these [headers] it is evicted, closed, and recreated; if another caller
   * already replaced it (dedup across tools sharing the session), the current pooled session is
   * returned. So the underlying client is created at most once per failure, and the common fetch is
   * just `getSession(headers)` with [stale] defaulting to `null`.
   */
  suspend fun getSession(
    headers: Map<String, String> = emptyMap(),
    stale: McpAsyncClient? = null,
  ): McpAsyncClient

  /** Closes every session this manager created. Safe to call more than once. */
  fun closeAll()
}
