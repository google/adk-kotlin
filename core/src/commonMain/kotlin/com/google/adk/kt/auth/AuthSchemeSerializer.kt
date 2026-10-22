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

@file:OptIn(ExperimentalAuthApi::class)

package com.google.adk.kt.auth

import com.google.adk.kt.annotations.ExperimentalAuthApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Reads and writes an [AuthScheme] as a JSON object whose `type` picks the kind, such as `apiKey`.
 * It rejects a scheme with no `type`, a `type` ADK does not define, or a missing or invalid field
 * its `type` needs.
 */
internal object AuthSchemeSerializer : KSerializer<AuthScheme> {
  override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor

  override fun serialize(encoder: Encoder, value: AuthScheme) {
    val jsonEncoder =
      encoder as? JsonEncoder
        ?: throw SerializationException("An auth scheme supports JSON formats only.")
    val json = jsonEncoder.json
    val fields =
      when (value) {
        is AuthScheme.ApiKey -> json.encodeToJsonElement(ApiKey.serializer(), ApiKey(value))
        is AuthScheme.Http -> json.encodeToJsonElement(Http.serializer(), Http(value))
        is AuthScheme.OAuth2,
        is AuthScheme.OpenIdConnectUrl,
        is AuthScheme.OpenIdConnect ->
          throw SerializationException(
            "Writing this auth scheme type as JSON is not supported yet."
          )
      }
    jsonEncoder.encodeJsonElement(
      JsonObject(mapOf("type" to JsonPrimitive(value.type)) + fields.jsonObject)
    )
  }

  override fun deserialize(decoder: Decoder): AuthScheme {
    val jsonDecoder =
      decoder as? JsonDecoder
        ?: throw SerializationException("An auth scheme supports JSON formats only.")
    val scheme =
      decodeWithoutInput { jsonDecoder.decodeJsonElement() } as? JsonObject
        ?: throw SerializationException("An auth scheme is not a JSON object.")
    val type =
      (scheme["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: throw SerializationException("An auth scheme has no string type.")
    val fields = JsonObject(scheme - "type")
    fun <W> decode(serializer: KSerializer<W>): W = decodeWithoutInput {
      jsonDecoder.json.decodeFromJsonElement(serializer, fields)
    }
    return when (type) {
      "apiKey" -> decode(ApiKey.serializer()).toApiKey()
      "http" -> decode(Http.serializer()).toHttp()
      "oauth2",
      "openIdConnect" ->
        throw SerializationException(
          "Reading this auth scheme type from JSON is not supported yet."
        )
      else -> throw SerializationException("An auth scheme has an unsupported type.")
    }
  }

  /**
   * Rethrows a decode failure without its cause, whose message can quote secrets in the input. It
   * covers only errors inside the scheme; a caller that decodes a larger document must hide errors
   * around it, such as trailing text.
   */
  private inline fun <T> decodeWithoutInput(decode: () -> T): T =
    try {
      decode()
    } catch (e: IllegalArgumentException) {
      // SerializationException extends IllegalArgumentException, so this catches both.
      throw SerializationException("Malformed auth scheme.")
    }

  @Serializable
  private class ApiKey(
    val location: AuthScheme.ApiKey.Location,
    val name: String,
    val description: String? = null,
  ) {
    constructor(apiKey: AuthScheme.ApiKey) : this(apiKey.location, apiKey.name, apiKey.description)

    fun toApiKey(): AuthScheme.ApiKey = AuthScheme.ApiKey(name, location, description)
  }

  @Serializable
  private class Http(
    val scheme: String,
    val bearerFormat: String? = null,
    val description: String? = null,
  ) {
    constructor(http: AuthScheme.Http) : this(http.scheme, http.bearerFormat, http.description)

    fun toHttp(): AuthScheme.Http = AuthScheme.Http(scheme, bearerFormat, description)
  }
}
