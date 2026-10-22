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
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonNames

/**
 * Reads and writes an [AuthCredential] as a JSON object: `authType` names the kind, such as
 * `apiKey`, and the kind's field holds its payload. Input also takes each multi-word field's
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
    @JsonNames("service_account") val serviceAccount: ServiceAccount? = null,
    val oauth2: OAuth2? = null,
  ) {
    fun toCredential(): AuthCredential =
      when (authType) {
        Type.API_KEY -> AuthCredential.ApiKey(apiKey)
        Type.HTTP -> http.required("http").toHttp()
        Type.OAUTH2 -> oauth2.required("oauth2").toOAuth2(AuthCredential.OAuth2.Variant.OAUTH2)
        Type.OPEN_ID_CONNECT ->
          oauth2.required("oauth2").toOAuth2(AuthCredential.OAuth2.Variant.OPEN_ID_CONNECT)
        Type.SERVICE_ACCOUNT -> serviceAccount.required("serviceAccount").toServiceAccount()
      }

    // Only an API key's payload may be absent; the other kinds are unusable without theirs.
    private fun <P : Any> P?.required(field: String): P =
      this ?: throw SerializationException("An auth credential has no $field payload.")

    @Serializable
    enum class Type {
      @SerialName("apiKey") API_KEY,
      @SerialName("http") HTTP,
      @SerialName("oauth2") OAUTH2,
      @SerialName("openIdConnect") OPEN_ID_CONNECT,
      @SerialName("serviceAccount") SERVICE_ACCOUNT,
    }

    companion object {
      fun of(credential: AuthCredential): Wire =
        when (credential) {
          is AuthCredential.ApiKey -> Wire(Type.API_KEY, apiKey = credential.apiKey)
          is AuthCredential.Http -> Wire(Type.HTTP, http = Http(credential))
          is AuthCredential.OAuth2 ->
            Wire(
              when (credential.variant) {
                AuthCredential.OAuth2.Variant.OAUTH2 -> Type.OAUTH2
                AuthCredential.OAuth2.Variant.OPEN_ID_CONNECT -> Type.OPEN_ID_CONNECT
              },
              oauth2 = OAuth2(credential),
            )
          is AuthCredential.ServiceAccount ->
            Wire(Type.SERVICE_ACCOUNT, serviceAccount = ServiceAccount(credential))
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

  /** The `oauth2` payload, shared by the `oauth2` and `openIdConnect` kinds. */
  @Serializable
  private class OAuth2(
    @JsonNames("client_id") val clientId: String? = null,
    @JsonNames("client_secret") val clientSecret: JsonSecret? = null,
    @JsonNames("auth_uri") val authUri: String? = null,
    val nonce: String? = null,
    val state: String? = null,
    @JsonNames("redirect_uri") val redirectUri: String? = null,
    @JsonNames("auth_response_uri") val authResponseUri: JsonSecret? = null,
    @JsonNames("auth_code") val authCode: JsonSecret? = null,
    @JsonNames("access_token") val accessToken: JsonSecret? = null,
    @JsonNames("refresh_token") val refreshToken: JsonSecret? = null,
    @JsonNames("id_token") val idToken: JsonSecret? = null,
    // In whole seconds.
    @JsonNames("expires_at") val expiresAt: Long? = null,
    @JsonNames("expires_in") val expiresIn: Long? = null,
    val audience: String? = null,
    val prompt: String? = null,
    @JsonNames("code_verifier") val codeVerifier: JsonSecret? = null,
    @JsonNames("code_challenge_method") val codeChallengeMethod: String? = null,
    // Null by default, unlike the credential's, so that a null method reads back as null.
    @JsonNames("token_endpoint_auth_method")
    val tokenEndpointAuthMethod: AuthCredential.OAuth2.TokenEndpointAuthMethod? = null,
  ) {
    constructor(
      oauth2: AuthCredential.OAuth2
    ) : this(
      clientId = oauth2.clientId,
      clientSecret = oauth2.clientSecret,
      authUri = oauth2.authUri,
      nonce = oauth2.nonce,
      state = oauth2.state,
      redirectUri = oauth2.redirectUri,
      authResponseUri = oauth2.authResponseUri,
      authCode = oauth2.authCode,
      accessToken = oauth2.accessToken,
      refreshToken = oauth2.refreshToken,
      idToken = oauth2.idToken,
      // Any fraction of a second is dropped.
      expiresAt = oauth2.expiresAt?.epochSeconds,
      expiresIn = oauth2.expiresIn?.inWholeSeconds,
      audience = oauth2.audience,
      prompt = oauth2.prompt,
      codeVerifier = oauth2.codeVerifier,
      codeChallengeMethod = oauth2.codeChallengeMethod,
      tokenEndpointAuthMethod = oauth2.tokenEndpointAuthMethod,
    )

    fun toOAuth2(variant: AuthCredential.OAuth2.Variant): AuthCredential.OAuth2 =
      AuthCredential.OAuth2(
        variant = variant,
        clientId = clientId,
        clientSecret = clientSecret,
        authUri = authUri,
        nonce = nonce,
        state = state,
        redirectUri = redirectUri,
        authResponseUri = authResponseUri,
        authCode = authCode,
        accessToken = accessToken,
        refreshToken = refreshToken,
        idToken = idToken,
        expiresAt = expiresAt?.let(Instant::fromEpochSeconds),
        expiresIn = expiresIn?.seconds,
        audience = audience,
        prompt = prompt,
        codeVerifier = codeVerifier,
        codeChallengeMethod = codeChallengeMethod,
        tokenEndpointAuthMethod = tokenEndpointAuthMethod,
      )
  }

  @Serializable
  private class ServiceAccount(
    @JsonNames("service_account_credential") val serviceAccountCredential: Credential? = null,
    val scopes: List<String>? = null,
    @JsonNames("use_default_credential") val useDefaultCredential: Boolean = false,
    @JsonNames("use_id_token") val useIdToken: Boolean = false,
    val audience: String? = null,
  ) {
    constructor(
      serviceAccount: AuthCredential.ServiceAccount
    ) : this(
      serviceAccountCredential = serviceAccount.serviceAccountCredential?.let(::Credential),
      scopes = serviceAccount.scopes,
      useDefaultCredential = serviceAccount.useDefaultCredential,
      useIdToken = serviceAccount.useIdToken,
      audience = serviceAccount.audience,
    )

    fun toServiceAccount(): AuthCredential.ServiceAccount =
      try {
        AuthCredential.ServiceAccount(
          serviceAccountCredential = serviceAccountCredential?.toCredential(),
          scopes = scopes,
          useDefaultCredential = useDefaultCredential,
          useIdToken = useIdToken,
          audience = audience,
        )
      } catch (e: IllegalArgumentException) {
        // The constructor's check messages carry no input.
        throw SerializationException(e.message, e)
      }

    @Serializable
    class Credential(
      val type: String = "",
      @JsonNames("project_id") val projectId: String,
      @JsonNames("private_key_id") val privateKeyId: JsonSecret,
      @JsonNames("private_key") val privateKey: JsonSecret,
      @JsonNames("client_email") val clientEmail: String,
      @JsonNames("client_id") val clientId: String,
      @JsonNames("auth_uri") val authUri: String,
      @JsonNames("token_uri") val tokenUri: String,
      @JsonNames("auth_provider_x509_cert_url") val authProviderX509CertUrl: String,
      @JsonNames("client_x509_cert_url") val clientX509CertUrl: String,
      @JsonNames("universe_domain") val universeDomain: String,
    ) {
      constructor(
        key: AuthCredential.ServiceAccount.Credential
      ) : this(
        type = key.type,
        projectId = key.projectId,
        privateKeyId = key.privateKeyId,
        privateKey = key.privateKey,
        clientEmail = key.clientEmail,
        clientId = key.clientId,
        authUri = key.authUri,
        tokenUri = key.tokenUri,
        authProviderX509CertUrl = key.authProviderX509CertUrl,
        clientX509CertUrl = key.clientX509CertUrl,
        universeDomain = key.universeDomain,
      )

      fun toCredential(): AuthCredential.ServiceAccount.Credential =
        AuthCredential.ServiceAccount.Credential(
          type = type,
          projectId = projectId,
          privateKeyId = privateKeyId,
          privateKey = privateKey,
          clientEmail = clientEmail,
          clientId = clientId,
          authUri = authUri,
          tokenUri = tokenUri,
          authProviderX509CertUrl = authProviderX509CertUrl,
          clientX509CertUrl = clientX509CertUrl,
          universeDomain = universeDomain,
        )
    }
  }
}
