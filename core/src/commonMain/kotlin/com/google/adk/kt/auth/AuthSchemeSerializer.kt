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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonNames

/**
 * Reads and writes an [AuthScheme] as a JSON object whose `type` picks the kind, such as `apiKey`;
 * an `openIdConnect` scheme with an `openIdConnectUrl` is the discovery URL kind. Input also takes
 * each multi-word field's snake_case name, as the server's request bodies do. It rejects a scheme
 * with no `type`, a `type` ADK does not define, or a missing or invalid field its `type` needs.
 */
internal object AuthSchemeSerializer :
  KSerializer<AuthScheme> by surrogateSerializer(
    surrogate = AuthSchemeSurrogate.serializer(),
    toSurrogate = AuthScheme::toSurrogate,
    fromSurrogate = AuthSchemeSurrogate::toAuthScheme,
  )

private fun AuthScheme.toSurrogate(): AuthSchemeSurrogate =
  when (this) {
    is AuthScheme.ApiKey -> AuthSchemeSurrogate.ApiKey(this)
    is AuthScheme.Http -> AuthSchemeSurrogate.Http(this)
    is AuthScheme.OAuth2 -> AuthSchemeSurrogate.OAuth2(this)
    is AuthScheme.OpenIdConnectUrl -> AuthSchemeSurrogate.OpenIdConnect(this)
    is AuthScheme.OpenIdConnect -> AuthSchemeSurrogate.OpenIdConnect(this)
  }

/** The scheme's JSON object, whose `type` picks the subclass. */
@Serializable
@JsonClassDiscriminator("type")
private sealed interface AuthSchemeSurrogate {
  fun toAuthScheme(): AuthScheme

  @Serializable
  @SerialName("apiKey")
  class ApiKey(
    val location: AuthScheme.ApiKey.Location,
    val name: String,
    val description: String? = null,
  ) : AuthSchemeSurrogate {
    constructor(apiKey: AuthScheme.ApiKey) : this(apiKey.location, apiKey.name, apiKey.description)

    override fun toAuthScheme(): AuthScheme = AuthScheme.ApiKey(name, location, description)
  }

  @Serializable
  @SerialName("http")
  class Http(
    val scheme: String,
    @JsonNames("bearer_format") val bearerFormat: String? = null,
    val description: String? = null,
  ) : AuthSchemeSurrogate {
    constructor(http: AuthScheme.Http) : this(http.scheme, http.bearerFormat, http.description)

    override fun toAuthScheme(): AuthScheme = AuthScheme.Http(scheme, bearerFormat, description)
  }

  @Serializable
  @SerialName("oauth2")
  class OAuth2(
    val flows: Flows,
    @JsonNames("issuer_url") val issuerUrl: String? = null,
    val description: String? = null,
  ) : AuthSchemeSurrogate {
    constructor(
      oauth2: AuthScheme.OAuth2
    ) : this(Flows(oauth2.flows), oauth2.issuerUrl, oauth2.description)

    override fun toAuthScheme(): AuthScheme =
      AuthScheme.OAuth2(flows.toFlows(), issuerUrl, description)

    @Serializable
    class Flows(
      val implicit: Implicit? = null,
      val password: Password? = null,
      @JsonNames("client_credentials") val clientCredentials: ClientCredentials? = null,
      @JsonNames("authorization_code") val authorizationCode: AuthorizationCode? = null,
    ) {
      constructor(
        flows: AuthScheme.OAuth2.Flows
      ) : this(
        implicit = flows.implicit?.let(::Implicit),
        password = flows.password?.let(::Password),
        clientCredentials = flows.clientCredentials?.let(::ClientCredentials),
        authorizationCode = flows.authorizationCode?.let(::AuthorizationCode),
      )

      fun toFlows(): AuthScheme.OAuth2.Flows =
        AuthScheme.OAuth2.Flows(
          implicit = implicit?.toImplicit(),
          password = password?.toPassword(),
          clientCredentials = clientCredentials?.toClientCredentials(),
          authorizationCode = authorizationCode?.toAuthorizationCode(),
        )
    }

    @Serializable
    class Implicit(
      @JsonNames("authorization_url") val authorizationUrl: String,
      @JsonNames("refresh_url") val refreshUrl: String? = null,
      val scopes: Map<String, String> = emptyMap(),
    ) {
      constructor(
        flow: AuthScheme.OAuth2.Flow.Implicit
      ) : this(flow.authorizationUrl, flow.refreshUrl, flow.scopes)

      fun toImplicit(): AuthScheme.OAuth2.Flow.Implicit =
        AuthScheme.OAuth2.Flow.Implicit(authorizationUrl, refreshUrl, scopes)
    }

    @Serializable
    class Password(
      @JsonNames("token_url") val tokenUrl: String,
      @JsonNames("refresh_url") val refreshUrl: String? = null,
      val scopes: Map<String, String> = emptyMap(),
    ) {
      constructor(
        flow: AuthScheme.OAuth2.Flow.Password
      ) : this(flow.tokenUrl, flow.refreshUrl, flow.scopes)

      fun toPassword(): AuthScheme.OAuth2.Flow.Password =
        AuthScheme.OAuth2.Flow.Password(tokenUrl, refreshUrl, scopes)
    }

    @Serializable
    class ClientCredentials(
      @JsonNames("token_url") val tokenUrl: String,
      @JsonNames("refresh_url") val refreshUrl: String? = null,
      val scopes: Map<String, String> = emptyMap(),
    ) {
      constructor(
        flow: AuthScheme.OAuth2.Flow.ClientCredentials
      ) : this(flow.tokenUrl, flow.refreshUrl, flow.scopes)

      fun toClientCredentials(): AuthScheme.OAuth2.Flow.ClientCredentials =
        AuthScheme.OAuth2.Flow.ClientCredentials(tokenUrl, refreshUrl, scopes)
    }

    @Serializable
    class AuthorizationCode(
      @JsonNames("authorization_url") val authorizationUrl: String,
      @JsonNames("token_url") val tokenUrl: String,
      @JsonNames("refresh_url") val refreshUrl: String? = null,
      val scopes: Map<String, String> = emptyMap(),
    ) {
      constructor(
        flow: AuthScheme.OAuth2.Flow.AuthorizationCode
      ) : this(flow.authorizationUrl, flow.tokenUrl, flow.refreshUrl, flow.scopes)

      fun toAuthorizationCode(): AuthScheme.OAuth2.Flow.AuthorizationCode =
        AuthScheme.OAuth2.Flow.AuthorizationCode(authorizationUrl, tokenUrl, refreshUrl, scopes)
    }
  }

  /**
   * Both OpenID Connect kinds, which share the `openIdConnect` type: the discovery URL kind when
   * [openIdConnectUrl] is set, else the kind that gives the endpoints.
   */
  @Serializable
  @SerialName("openIdConnect")
  class OpenIdConnect(
    @JsonNames("open_id_connect_url") val openIdConnectUrl: String? = null,
    @JsonNames("authorization_endpoint") val authorizationEndpoint: String? = null,
    @JsonNames("token_endpoint") val tokenEndpoint: String? = null,
    @JsonNames("userinfo_endpoint") val userinfoEndpoint: String? = null,
    @JsonNames("revocation_endpoint") val revocationEndpoint: String? = null,
    @JsonNames("token_endpoint_auth_methods_supported")
    val tokenEndpointAuthMethodsSupported: List<String>? = null,
    @JsonNames("grant_types_supported") val grantTypesSupported: List<String>? = null,
    val scopes: List<String>? = null,
    val description: String? = null,
  ) : AuthSchemeSurrogate {
    constructor(
      scheme: AuthScheme.OpenIdConnectUrl
    ) : this(openIdConnectUrl = scheme.openIdConnectUrl, description = scheme.description)

    constructor(
      scheme: AuthScheme.OpenIdConnect
    ) : this(
      authorizationEndpoint = scheme.authorizationEndpoint,
      tokenEndpoint = scheme.tokenEndpoint,
      userinfoEndpoint = scheme.userinfoEndpoint,
      revocationEndpoint = scheme.revocationEndpoint,
      tokenEndpointAuthMethodsSupported = scheme.tokenEndpointAuthMethodsSupported,
      grantTypesSupported = scheme.grantTypesSupported,
      scopes = scheme.scopes,
      description = scheme.description,
    )

    override fun toAuthScheme(): AuthScheme {
      if (openIdConnectUrl != null) {
        return AuthScheme.OpenIdConnectUrl(openIdConnectUrl, description)
      }
      if (authorizationEndpoint == null || tokenEndpoint == null) {
        throw SerializationException(
          "An openIdConnect scheme needs an openIdConnectUrl or both endpoints."
        )
      }
      return AuthScheme.OpenIdConnect(
        authorizationEndpoint = authorizationEndpoint,
        tokenEndpoint = tokenEndpoint,
        userinfoEndpoint = userinfoEndpoint,
        revocationEndpoint = revocationEndpoint,
        tokenEndpointAuthMethodsSupported = tokenEndpointAuthMethodsSupported,
        grantTypesSupported = grantTypesSupported,
        scopes = scopes,
        description = description,
      )
    }
  }
}
