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

package com.google.adk.kt.clients

import io.ktor.client.HttpClient

/**
 * Runs [block], closing [httpClient] and rethrowing if it fails, so a construction that aborts on
 * invalid arguments never leaves the owned client behind. A failing `close()` is attached to the
 * original failure as suppressed, so it never masks the real diagnostic.
 */
internal inline fun <T> closingHttpClientOnFailure(httpClient: HttpClient, block: () -> T): T =
  try {
    block()
  } catch (e: Throwable) {
    try {
      httpClient.close()
    } catch (closeError: Throwable) {
      e.addSuppressed(closeError)
    }
    throw e
  }
