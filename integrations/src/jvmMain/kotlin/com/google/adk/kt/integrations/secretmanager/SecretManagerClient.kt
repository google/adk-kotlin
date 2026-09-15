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

package com.google.adk.kt.integrations.secretmanager

import com.google.adk.kt.VERSION
import com.google.api.gax.core.FixedCredentialsProvider
import com.google.api.gax.rpc.FixedHeaderProvider
import com.google.auth.oauth2.AccessToken
import com.google.auth.oauth2.GoogleCredentials
import com.google.auth.oauth2.ServiceAccountCredentials
import com.google.cloud.secretmanager.v1.SecretManagerServiceClient
import com.google.cloud.secretmanager.v1.SecretManagerServiceSettings
import java.io.ByteArrayInputStream
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val USER_AGENT = "google-adk/$VERSION"

private const val CLOUD_PLATFORM_SCOPE = "https://www.googleapis.com/auth/cloud-platform"

/** Regional endpoints carry a port because that is the form gax wants a gRPC endpoint in. */
private const val DEFAULT_REGIONAL_ENDPOINT_TEMPLATE = "secretmanager.%s.rep.googleapis.com:443"

private const val DEFAULT_MTLS_REGIONAL_ENDPOINT_TEMPLATE =
  "secretmanager.%s.rep.mtls.googleapis.com:443"

private const val MTLS_ENDPOINT_ENV_VAR = "GOOGLE_API_USE_MTLS_ENDPOINT"

private const val CLIENT_CERTIFICATE_ENV_VAR = "GOOGLE_API_USE_CLIENT_CERTIFICATE"

/**
 * A client for reading secrets out of Google Cloud Secret Manager.
 *
 * Authentication is either the text of a service account keyfile or an authorization token the
 * caller already holds; given neither it falls back to Application Default Credentials, and given
 * both it refuses. Credentials are resolved in the constructor but the transport is opened on the
 * first read, so a client that reads holds a gRPC channel and the threads serving it until closed.
 *
 * ```
 * SecretManagerClient(serviceAccountJson = keyfileContents).use { client ->
 *   val apiKey = client.getSecret("projects/my-project/secrets/my-secret/versions/latest")
 * }
 * ```
 *
 * Experimental: the shape of this type may change.
 *
 * @param serviceAccountJson the contents of a service account JSON keyfile, which is the keyfile
 *   itself and not a path to it
 * @param authToken an existing Google Cloud authorization token to read secrets with
 * @param location the Google Cloud location whose regional endpoint the secrets are read through;
 *   left unset, the global endpoint is used
 * @throws IllegalArgumentException if both a keyfile and a token are given, if the keyfile cannot
 *   be read, or if neither is given and Application Default Credentials cannot be resolved
 */
class SecretManagerClient(
  serviceAccountJson: String? = null,
  authToken: String? = null,
  location: String? = null,
) : AutoCloseable {

  init {
    require(serviceAccountJson.isNullOrEmpty() || authToken.isNullOrEmpty()) {
      "Must provide either 'serviceAccountJson' or 'authToken', not both."
    }
  }

  private val credentials: GoogleCredentials =
    resolveCredentials(serviceAccountJson?.ifEmpty { null }, authToken?.ifEmpty { null })

  private val endpoint: String? =
    location?.ifEmpty { null }?.let { apiEndpoint(it, useMtlsEndpointFromEnv()) }

  /** Held rather than only delegated to, so [close] can ask whether a transport was ever opened. */
  internal val lazyServiceClient = lazy { createServiceClient(credentials, endpoint) }

  private val serviceClient: SecretManagerServiceClient by lazyServiceClient

  @Volatile private var closed = false

  /**
   * Returns the payload of the secret version named by [resourceName], as text.
   *
   * @param resourceName the full resource name of the secret version, in the format
   *   `projects/{project}/secrets/{secret}/versions/{version}`. Usually you want the `latest`
   *   version, for example `projects/my-project/secrets/my-secret/versions/latest`.
   * @throws IllegalStateException if this client has been closed
   * @throws com.google.api.gax.rpc.ApiException if Secret Manager rejects the read, for instance
   *   because the secret does not exist or the credentials cannot reach it
   */
  suspend fun getSecret(resourceName: String): String {
    check(!closed) { "This SecretManagerClient is closed." }
    return withContext(Dispatchers.IO) {
      serviceClient.accessSecretVersion(resourceName).payload.data.toStringUtf8()
    }
  }

  /**
   * Releases the gRPC channel and threads this client opened; reading a secret afterwards fails.
   *
   * A client that was never read from opened nothing, and closing it opens nothing either. Such a
   * client still has to refuse a later read, or the lazy transport would open a channel with no
   * owner left to close it.
   */
  override fun close() {
    closed = true
    if (lazyServiceClient.isInitialized()) {
      serviceClient.close()
    }
  }
}

/**
 * Returns the credentials to read secrets with, given whichever of the two the caller brought.
 *
 * Every way this can go wrong is a complaint about an argument, so each of them arrives as one;
 * left to the credential library, an unreadable keyfile and a Secret Manager that is down would
 * reach the caller as the same [java.io.IOException].
 */
private fun resolveCredentials(serviceAccountJson: String?, authToken: String?): GoogleCredentials {
  // No message below quotes its failure: a credential parse error can carry the key material.
  if (serviceAccountJson != null) {
    return try {
      ByteArrayInputStream(serviceAccountJson.toByteArray()).use {
        ServiceAccountCredentials.fromStream(it)
      }
    } catch (e: Exception) {
      throw IllegalArgumentException("'serviceAccountJson' is not a readable keyfile.", e)
    }
  }
  if (authToken != null) {
    // No expiry: the caller brought the token, so nothing here could refresh it anyway.
    return GoogleCredentials.create(AccessToken.newBuilder().setTokenValue(authToken).build())
  }
  return try {
    GoogleCredentials.getApplicationDefault().createScoped(CLOUD_PLATFORM_SCOPE)
  } catch (e: Exception) {
    throw IllegalArgumentException(
      "'serviceAccountJson' and 'authToken' are both missing, and Application Default " +
        "Credentials could not be resolved either.",
      e,
    )
  }
}

private fun createServiceClient(
  credentials: GoogleCredentials,
  endpoint: String?,
): SecretManagerServiceClient {
  val builder =
    SecretManagerServiceSettings.newBuilder()
      .setCredentialsProvider(FixedCredentialsProvider.create(credentials))
      .setHeaderProvider(FixedHeaderProvider.create(mapOf("user-agent" to USER_AGENT)))
  val settings = if (endpoint != null) builder.setEndpoint(endpoint) else builder
  return try {
    SecretManagerServiceClient.create(settings.build())
  } catch (e: IOException) {
    throw IllegalStateException("Failed to open a Secret Manager client.", e)
  }
}

/** Returns the regional endpoint for [location], in its mutual-TLS form when asked for. */
internal fun apiEndpoint(location: String, useMtls: Boolean): String =
  (if (useMtls) DEFAULT_MTLS_REGIONAL_ENDPOINT_TEMPLATE else DEFAULT_REGIONAL_ENDPOINT_TEMPLATE)
    .format(location)

/**
 * Whether the environment asks for mutual-TLS endpoints, given the two variables that decide it.
 *
 * `GOOGLE_API_USE_MTLS_ENDPOINT` settles it outright when it reads `always` or `never`; otherwise
 * the choice is automatic and follows `GOOGLE_API_USE_CLIENT_CERTIFICATE`.
 */
internal fun useMtlsEndpoint(mtlsEndpointSetting: String?, useClientCertificate: String?): Boolean =
  when (mtlsEndpointSetting?.lowercase()) {
    "always" -> true
    "never" -> false
    else -> useClientCertificate?.lowercase() == "true"
  }

private fun useMtlsEndpointFromEnv(): Boolean =
  useMtlsEndpoint(
    mtlsEndpointSetting = System.getenv(MTLS_ENDPOINT_ENV_VAR),
    useClientCertificate = System.getenv(CLIENT_CERTIFICATE_ENV_VAR),
  )
