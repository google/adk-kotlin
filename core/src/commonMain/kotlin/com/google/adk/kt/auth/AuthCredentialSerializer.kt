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
import kotlinx.serialization.json.JsonNames

/**
 * Reads and writes an [AuthCredential] as a JSON object: `authType` names the kind, such as
 * `apiKey`, and the kind's field holds its payload. Input also takes each multi-word field's
 * snake_case name, as the server's request bodies do. Output leaves out every secret field unless
 * the encoder [revealsSecrets].
 */
internal object AuthCredentialSerializer :
  KSerializer<AuthCredential> by surrogateSerializer(
    surrogate = AuthCredentialSurrogate.serializer(),
    toSurrogate = { credential, encoder -> credential.toSurrogate(encoder.revealsSecrets()) },
    fromSurrogate = AuthCredentialSurrogate::toAuthCredential,
  )

private fun AuthCredential.toSurrogate(revealSecrets: Boolean): AuthCredentialSurrogate =
  when (this) {
    is AuthCredential.ApiKey -> AuthCredentialSurrogate(this, revealSecrets)
    is AuthCredential.Http -> AuthCredentialSurrogate(this, revealSecrets)
    is AuthCredential.OAuth2 -> AuthCredentialSurrogate(this, revealSecrets)
    is AuthCredential.ServiceAccount -> AuthCredentialSurrogate(this, revealSecrets)
  }

/** Returns this secret if [revealSecrets], or null so that the output leaves it out. */
private fun Secret?.keptIf(revealSecrets: Boolean): Secret? = takeIf { revealSecrets }

/**
 * The credential's JSON object: the kind in [authType], and the payload in that kind's field, which
 * is `oauth2` for OpenID Connect.
 */
@Serializable
private class AuthCredentialSurrogate(
  @JsonNames("auth_type") val authType: Type,
  @JsonNames("api_key") val apiKey: JsonSecret? = null,
  val http: Http? = null,
  @JsonNames("service_account") val serviceAccount: ServiceAccount? = null,
  val oauth2: OAuth2? = null,
) {
  constructor(
    apiKey: AuthCredential.ApiKey,
    revealSecrets: Boolean,
  ) : this(Type.API_KEY, apiKey = apiKey.apiKey.keptIf(revealSecrets))

  constructor(
    http: AuthCredential.Http,
    revealSecrets: Boolean,
  ) : this(Type.HTTP, http = Http(http, revealSecrets))

  constructor(
    oauth2: AuthCredential.OAuth2,
    revealSecrets: Boolean,
  ) : this(
    when (oauth2.variant) {
      AuthCredential.OAuth2.Variant.OAUTH2 -> Type.OAUTH2
      AuthCredential.OAuth2.Variant.OPEN_ID_CONNECT -> Type.OPEN_ID_CONNECT
    },
    oauth2 = OAuth2(oauth2, revealSecrets),
  )

  constructor(
    serviceAccount: AuthCredential.ServiceAccount,
    revealSecrets: Boolean,
  ) : this(Type.SERVICE_ACCOUNT, serviceAccount = ServiceAccount(serviceAccount, revealSecrets))

  fun toAuthCredential(): AuthCredential =
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

  @Serializable
  class Http(
    val scheme: String,
    val credentials: Credentials,
    @JsonNames("additional_headers") val additionalHeaders: Map<String, JsonSecret>? = null,
  ) {
    constructor(
      http: AuthCredential.Http,
      revealSecrets: Boolean,
    ) : this(
      http.scheme,
      Credentials(http.credentials, revealSecrets),
      http.additionalHeaders?.takeIf { revealSecrets },
    )

    fun toHttp(): AuthCredential.Http =
      AuthCredential.Http(scheme, credentials.toCredentials(), additionalHeaders)

    @Serializable
    class Credentials(
      val username: String? = null,
      val password: JsonSecret? = null,
      val token: JsonSecret? = null,
    ) {
      constructor(
        credentials: AuthCredential.Http.Credentials,
        revealSecrets: Boolean,
      ) : this(
        credentials.username,
        credentials.password.keptIf(revealSecrets),
        credentials.token.keptIf(revealSecrets),
      )

      fun toCredentials(): AuthCredential.Http.Credentials =
        AuthCredential.Http.Credentials(username, password, token)
    }
  }

  /** The `oauth2` payload, shared by the `oauth2` and `openIdConnect` kinds. */
  @Serializable
  class OAuth2(
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
      oauth2: AuthCredential.OAuth2,
      revealSecrets: Boolean,
    ) : this(
      clientId = oauth2.clientId,
      clientSecret = oauth2.clientSecret.keptIf(revealSecrets),
      authUri = oauth2.authUri,
      nonce = oauth2.nonce,
      state = oauth2.state,
      redirectUri = oauth2.redirectUri,
      authResponseUri = oauth2.authResponseUri.keptIf(revealSecrets),
      authCode = oauth2.authCode.keptIf(revealSecrets),
      accessToken = oauth2.accessToken.keptIf(revealSecrets),
      refreshToken = oauth2.refreshToken.keptIf(revealSecrets),
      idToken = oauth2.idToken.keptIf(revealSecrets),
      // Any fraction of a second is dropped.
      expiresAt = oauth2.expiresAt?.epochSeconds,
      expiresIn = oauth2.expiresIn?.inWholeSeconds,
      audience = oauth2.audience,
      prompt = oauth2.prompt,
      codeVerifier = oauth2.codeVerifier.keptIf(revealSecrets),
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
  class ServiceAccount(
    @JsonNames("service_account_credential") val serviceAccountCredential: Credential? = null,
    val scopes: List<String>? = null,
    @JsonNames("use_default_credential") val useDefaultCredential: Boolean = false,
    @JsonNames("use_id_token") val useIdToken: Boolean = false,
    val audience: String? = null,
  ) {
    constructor(
      serviceAccount: AuthCredential.ServiceAccount,
      revealSecrets: Boolean,
    ) : this(
      serviceAccountCredential =
        serviceAccount.serviceAccountCredential?.let { Credential(it, revealSecrets) },
      scopes = serviceAccount.scopes,
      useDefaultCredential = serviceAccount.useDefaultCredential,
      useIdToken = serviceAccount.useIdToken,
      audience = serviceAccount.audience,
    )

    fun toServiceAccount(): AuthCredential.ServiceAccount =
      AuthCredential.ServiceAccount(
        serviceAccountCredential = serviceAccountCredential?.toCredential(),
        scopes = scopes,
        useDefaultCredential = useDefaultCredential,
        useIdToken = useIdToken,
        audience = audience,
      )

    /** A service account key; its private key and its ID are left out unless revealing. */
    @Serializable
    class Credential(
      val type: String = "",
      @JsonNames("project_id") val projectId: String,
      @JsonNames("private_key_id") val privateKeyId: JsonSecret? = null,
      @JsonNames("private_key") val privateKey: JsonSecret? = null,
      @JsonNames("client_email") val clientEmail: String,
      @JsonNames("client_id") val clientId: String,
      @JsonNames("auth_uri") val authUri: String,
      @JsonNames("token_uri") val tokenUri: String,
      @JsonNames("auth_provider_x509_cert_url") val authProviderX509CertUrl: String,
      @JsonNames("client_x509_cert_url") val clientX509CertUrl: String,
      @JsonNames("universe_domain") val universeDomain: String,
    ) {
      constructor(
        key: AuthCredential.ServiceAccount.Credential,
        revealSecrets: Boolean,
      ) : this(
        type = key.type,
        projectId = key.projectId,
        privateKeyId = key.privateKeyId.keptIf(revealSecrets),
        privateKey = key.privateKey.keptIf(revealSecrets),
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
          // Like the other required fields, which kotlinx rejects before this runs.
          privateKeyId = privateKeyId ?: throw SerializationException("Malformed auth data."),
          privateKey = privateKey ?: throw SerializationException("Malformed auth data."),
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
