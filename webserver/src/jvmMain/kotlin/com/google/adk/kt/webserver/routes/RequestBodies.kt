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

import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.request.receiveNullable

/**
 * Reads a body the endpoint requires, reporting a failure without quoting what was sent.
 *
 * The receive has to be nullable: content negotiation reports an empty body as no body only for a
 * nullable type, and a non-nullable one leaves the body untransformed, which the engine answers
 * with `415`.
 *
 * @throws BadRequestException if the body is missing, or could not be read; Ktor answers `400`
 */
internal suspend inline fun <reified T : Any> ApplicationCall.receiveRequiredBody(): T {
  val body =
    try {
      receiveNullable<T?>()
    } catch (cause: BadRequestException) {
      // Ktor's message quotes the input it rejected, and the engine logs it with the cause, so
      // only the underlying failure's type survives - enough to tell a parse error from a fault.
      throw BadRequestException("Unreadable request: ${cause.cause?.let { it::class.simpleName }}")
    }
  return body ?: throw BadRequestException("Missing request body")
}
