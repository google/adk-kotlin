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
import kotlinx.serialization.json.JsonNames

/**
 * Reads and writes an [AuthCredential] as a JSON object: `authType` names the kind, such as
 * `API_KEY`, and the kind's field holds its payload. Input also takes each multi-word field's
 * snake_case name, as the server's request bodies do.
 */
internal object AuthCredentialSerializer : KSerializer<AuthCredential> {
  override val descriptor: SerialDescriptor = Wire.serializer().descriptor

  override fun serialize(encoder: Encoder, value: AuthCredential) {
    encoder.encodeSerializableValue(Wire.serializer(), Wire.of(value))
  }

  override fun deserialize(decoder: Decoder): AuthCredential =
    decodeWithoutInput { decoder.decodeSerializableValue(Wire.serializer()) }.toCredential()

  /**
   * Rethrows a decode failure without its cause, whose message can quote secrets in the input. It
   * covers only errors inside the credential; a caller that decodes a larger document must hide
   * errors around it, such as trailing text.
   */
  private inline fun <T> decodeWithoutInput(decode: () -> T): T =
    try {
      decode()
    } catch (e: IllegalArgumentException) {
      // SerializationException extends IllegalArgumentException, so this catches both.
      throw SerializationException("Malformed auth credential.")
    }

  /**
   * The credential's JSON object: the kind in [authType], and the payload in that kind's field,
   * which is `oauth2` for OpenID Connect.
   */
  @Serializable
  private class Wire(
    @JsonNames("auth_type") val authType: Type,
    @JsonNames("api_key") val apiKey: JsonSecret? = null,
    val http: Http? = null,
  ) {
    fun toCredential(): AuthCredential =
      when (authType) {
        Type.API_KEY -> AuthCredential.ApiKey(apiKey)
        Type.HTTP -> http.required("http").toHttp()
        Type.OAUTH2,
        Type.OPEN_ID_CONNECT,
        Type.SERVICE_ACCOUNT ->
          throw SerializationException(
            "Reading this credential type from JSON is not supported yet."
          )
      }

    // Only an API key's payload may be absent; the other kinds can't be built without theirs.
    private fun <P : Any> P?.required(field: String): P =
      this ?: throw SerializationException("An auth credential has no $field payload.")

    enum class Type {
      API_KEY,
      HTTP,
      OAUTH2,
      OPEN_ID_CONNECT,
      SERVICE_ACCOUNT,
    }

    companion object {
      fun of(credential: AuthCredential): Wire =
        when (credential) {
          is AuthCredential.ApiKey -> Wire(Type.API_KEY, apiKey = credential.apiKey)
          is AuthCredential.Http -> Wire(Type.HTTP, http = Http(credential))
          is AuthCredential.OAuth2,
          is AuthCredential.ServiceAccount ->
            throw SerializationException(
              "Writing this credential type as JSON is not supported yet."
            )
        }
    }
  }

  @Serializable
  private class Http(
    val scheme: String,
    val credentials: Credentials,
    @JsonNames("additional_headers") val additionalHeaders: Map<String, JsonSecret>? = null,
  ) {
    constructor(
      http: AuthCredential.Http
    ) : this(http.scheme, Credentials(http.credentials), http.additionalHeaders)

    fun toHttp(): AuthCredential.Http =
      AuthCredential.Http(scheme, credentials.toCredentials(), additionalHeaders)

    @Serializable
    class Credentials(
      val username: String? = null,
      val password: JsonSecret? = null,
      val token: JsonSecret? = null,
    ) {
      constructor(
        credentials: AuthCredential.Http.Credentials
      ) : this(credentials.username, credentials.password, credentials.token)

      fun toCredentials(): AuthCredential.Http.Credentials =
        AuthCredential.Http.Credentials(username, password, token)
    }
  }
}
