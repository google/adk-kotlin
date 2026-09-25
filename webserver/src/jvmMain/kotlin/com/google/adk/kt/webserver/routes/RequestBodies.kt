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

package com.google.adk.kt.webserver.routes

import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.serialization.adkJson
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.request.receiveNullable
import io.ktor.server.response.respond
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * Reads a body the endpoint requires, answering `422` itself and returning `null` when the body
 * parses but does not fit [T], so a caller must stop on `null` rather than carry on.
 *
 * The receive has to be nullable: content negotiation reports an empty body as no body only for a
 * nullable type, and a non-nullable one leaves the body untransformed, which the engine answers
 * with `415`.
 *
 * @throws BadRequestException if the body is missing, or is not JSON the server can take; Ktor
 *   answers `400`
 */
@OptIn(FrameworkInternalApi::class)
internal suspend inline fun <reified T : Any> ApplicationCall.receiveRequiredBodyOrRespond(): T? {
  val json =
    try {
      receiveNullable<JsonElement?>()
    } catch (cause: BadRequestException) {
      // Ktor's message quotes the input it rejected, and the engine logs it with the cause, so
      // only the underlying failure's type survives - enough to tell a parse error from a fault.
      throw BadRequestException("Unreadable request: ${cause.cause?.let { it::class.simpleName }}")
    } ?: throw BadRequestException("Missing request body")
  val body =
    try {
      adkJson.decodeFromJsonElement<T>(json)
    } catch (_: IllegalArgumentException) {
      null
    } catch (_: IllegalStateException) {
      null
    } catch (cause: StackOverflowError) {
      // Nesting deep enough to exhaust the stack arrives as an Error, not a decoding failure.
      throw BadRequestException("Unreadable request: ${cause::class.simpleName}")
    }
  if (body == null) respond(HttpStatusCode.UnprocessableEntity)
  return body
}
