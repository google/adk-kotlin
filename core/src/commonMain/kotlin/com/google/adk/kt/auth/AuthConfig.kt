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

package com.google.adk.kt.auth

import com.google.adk.kt.annotations.AdkJavaInteropApi
import com.google.adk.kt.annotations.ExperimentalAuthApi
import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmStatic

/**
 * What a tool needs to authenticate to a service: the scheme the service expects, and the tool's
 * credentials. Creating a config or reading its properties requires
 * `@OptIn(ExperimentalAuthApi::class)`; holding one, as
 * [EventActions][com.google.adk.kt.events.EventActions] does, does not. In Kotlin, each constructor
 * takes one kind of scheme and only the credentials that fit it, and gives an OAuth2 client
 * [rawAuthCredential] the variant of its scheme.
 *
 * @property authScheme How the service expects the tool to authenticate.
 * @property rawAuthCredential The credential the tool is configured with: one it uses as is, such
 *   as an API key, or one it signs in with, such as an OAuth2 client ID and secret.
 * @property exchangedAuthCredential The credential obtained when the tool has none it can use as
 *   is. The app sets it, such as with an API key the user enters; for OAuth2 and OpenID Connect,
 *   ADK first sets the authorization URL and state, and the app then adds the sign-in result.
 * @property credentialKey The key under which ADK stores and looks up [exchangedAuthCredential];
 *   configs with the same key share it. Defaults to a key derived from [authScheme] and
 *   [rawAuthCredential], computed once, so [toBuilder] keeps it. `toString` redacts it.
 */
@OptIn(ExperimentalAuthApi::class)
@ConsistentCopyVisibility
data class AuthConfig
// Internal so that callers outside ADK use a constructor that checks the pair.
internal constructor(
  @ExperimentalAuthApi val authScheme: AuthScheme,
  @ExperimentalAuthApi val rawAuthCredential: AuthCredential? = null,
  @ExperimentalAuthApi val exchangedAuthCredential: AuthCredential? = null,
  @ExperimentalAuthApi
  val credentialKey: CredentialKey = deriveCredentialKey(authScheme, rawAuthCredential),
) {
  /** A config for an API key: the tool's own [rawAuthCredential], or one the user enters. */
  @ExperimentalAuthApi
  @JvmOverloads
  constructor(
    authScheme: AuthScheme.ApiKey,
    rawAuthCredential: AuthCredential.ApiKey? = null,
    exchangedAuthCredential: AuthCredential.ApiKey? = null,
    credentialKey: CredentialKey = deriveCredentialKey(authScheme, rawAuthCredential),
  ) : this(authScheme as AuthScheme, rawAuthCredential, exchangedAuthCredential, credentialKey)

  /**
   * A config for an HTTP credential: the tool's own [rawAuthCredential], or one the user enters.
   */
  @ExperimentalAuthApi
  @JvmOverloads
  constructor(
    authScheme: AuthScheme.Http,
    rawAuthCredential: AuthCredential.Http? = null,
    exchangedAuthCredential: AuthCredential.Http? = null,
    credentialKey: CredentialKey = deriveCredentialKey(authScheme, rawAuthCredential),
  ) : this(authScheme as AuthScheme, rawAuthCredential, exchangedAuthCredential, credentialKey)

  /** A config for OAuth2 sign-in with the client [rawAuthCredential]. */
  @ExperimentalAuthApi
  @JvmOverloads
  constructor(
    authScheme: AuthScheme.OAuth2,
    rawAuthCredential: AuthCredential.OAuth2,
    exchangedAuthCredential: AuthCredential.OAuth2? = null,
    credentialKey: CredentialKey =
      deriveCredentialKey(authScheme, rawAuthCredential.withVariantOf(authScheme)),
  ) : this(
    authScheme as AuthScheme,
    rawAuthCredential.withVariantOf(authScheme),
    exchangedAuthCredential,
    credentialKey,
  )

  /**
   * A config for a service account, whose OAuth2 client-credentials [authScheme] only marks it as
   * needing no user. ADK Kotlin can't exchange a service account for a token yet.
   */
  @ExperimentalAuthApi
  @JvmOverloads
  constructor(
    authScheme: AuthScheme.OAuth2,
    rawAuthCredential: AuthCredential.ServiceAccount,
    exchangedAuthCredential: AuthCredential.Http? = null,
    credentialKey: CredentialKey = deriveCredentialKey(authScheme, rawAuthCredential),
  ) : this(authScheme as AuthScheme, rawAuthCredential, exchangedAuthCredential, credentialKey)

  /** A config for OpenID Connect sign-in with the client [rawAuthCredential]. */
  @ExperimentalAuthApi
  @JvmOverloads
  constructor(
    authScheme: AuthScheme.OpenIdConnect,
    rawAuthCredential: AuthCredential.OAuth2,
    exchangedAuthCredential: AuthCredential.OAuth2? = null,
    credentialKey: CredentialKey =
      deriveCredentialKey(authScheme, rawAuthCredential.withVariantOf(authScheme)),
  ) : this(
    authScheme as AuthScheme,
    rawAuthCredential.withVariantOf(authScheme),
    exchangedAuthCredential,
    credentialKey,
  )

  /** A config for OpenID Connect sign-in, by discovery URL, with the client [rawAuthCredential]. */
  @ExperimentalAuthApi
  @JvmOverloads
  constructor(
    authScheme: AuthScheme.OpenIdConnectUrl,
    rawAuthCredential: AuthCredential.OAuth2,
    exchangedAuthCredential: AuthCredential.OAuth2? = null,
    credentialKey: CredentialKey =
      deriveCredentialKey(authScheme, rawAuthCredential.withVariantOf(authScheme)),
  ) : this(
    authScheme as AuthScheme,
    rawAuthCredential.withVariantOf(authScheme),
    exchangedAuthCredential,
    credentialKey,
  )

  /**
   * Returns a [Builder] initialized with this instance's properties, primarily for Java callers.
   * Prefer it over `copy` from Java: `copy` takes every property positionally, so its signature
   * changes whenever a property is added.
   */
  @ExperimentalAuthApi
  @AdkJavaInteropApi
  fun toBuilder(): Builder =
    Builder()
      .authScheme(authScheme)
      .rawAuthCredential(rawAuthCredential)
      .exchangedAuthCredential(exchangedAuthCredential)
      .credentialKey(credentialKey)

  /**
   * Fluent builder for [AuthConfig], provided primarily for Java callers. Any property left unset
   * falls back to the same default as the constructor. Unlike the constructors, it doesn't check
   * that the credential fits the scheme or set an OAuth2 client's variant.
   */
  @ExperimentalAuthApi
  @AdkJavaInteropApi
  @Suppress("ScopeReceiverThis") // Java-style builder for Java interop.
  class Builder {
    private var authScheme: AuthScheme? = null
    private var rawAuthCredential: AuthCredential? = null
    private var exchangedAuthCredential: AuthCredential? = null
    private var credentialKey: CredentialKey? = null

    fun authScheme(authScheme: AuthScheme): Builder = apply { this.authScheme = authScheme }

    fun rawAuthCredential(rawAuthCredential: AuthCredential?): Builder = apply {
      this.rawAuthCredential = rawAuthCredential
    }

    fun exchangedAuthCredential(exchangedAuthCredential: AuthCredential?): Builder = apply {
      this.exchangedAuthCredential = exchangedAuthCredential
    }

    fun credentialKey(credentialKey: CredentialKey?): Builder = apply {
      this.credentialKey = credentialKey
    }

    fun build(): AuthConfig {
      val authScheme =
        checkNotNull(authScheme) { "AuthConfig.Builder requires authScheme to be set." }
      return AuthConfig(
        authScheme = authScheme,
        rawAuthCredential = rawAuthCredential,
        exchangedAuthCredential = exchangedAuthCredential,
        credentialKey = credentialKey ?: deriveCredentialKey(authScheme, rawAuthCredential),
      )
    }
  }

  companion object {
    @ExperimentalAuthApi @AdkJavaInteropApi @JvmStatic fun builder(): Builder = Builder()
  }
}

/**
 * Returns this credential with the variant [scheme] implies: OpenID Connect for an OpenID Connect
 * scheme and OAuth2 for an OAuth2 one. Any other scheme leaves it as it is.
 */
@OptIn(ExperimentalAuthApi::class)
private fun AuthCredential.OAuth2.withVariantOf(scheme: AuthScheme): AuthCredential.OAuth2 {
  val variant =
    when (scheme) {
      is AuthScheme.OpenIdConnect,
      is AuthScheme.OpenIdConnectUrl -> AuthCredential.OAuth2.Variant.OPEN_ID_CONNECT
      is AuthScheme.OAuth2 -> AuthCredential.OAuth2.Variant.OAUTH2
      is AuthScheme.ApiKey,
      is AuthScheme.Http -> return this
    }
  return if (this.variant == variant) this else copy(variant = variant)
}
