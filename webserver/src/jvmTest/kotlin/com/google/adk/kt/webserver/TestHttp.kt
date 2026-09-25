/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.adk.kt.webserver

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType

/** Sends [body] as JSON, the shape every route test posts in. */
internal fun HttpRequestBuilder.jsonBody(body: String) {
  contentType(ContentType.Application.Json)
  setBody(body)
}

/** Sends [body] with a non-JSON content type, for asserting the 415 path. */
internal fun HttpRequestBuilder.textBody(body: String) {
  contentType(ContentType.Text.Plain)
  setBody(body)
}
