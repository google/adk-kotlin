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

import com.google.adk.kt.agents.Context
import com.google.adk.kt.annotations.ExperimentalAuthApi
import com.google.adk.kt.logging.LoggerFactory
import com.google.adk.kt.sessions.State
import kotlinx.serialization.SerializationException

/**
 * A [CredentialService] that keeps each credential in session state under its
 * [AuthConfig.credentialKey], so it lasts as long as the session. Session state is readable by
 * anything that can read the session: the OAuth2 client secret is left out, but every other secret,
 * the user's tokens included, is stored in clear text.
 */
@ExperimentalAuthApi
class SessionStateCredentialService : CredentialService {

  /**
   * Returns the stored credential with [authConfig]'s OAuth2 client secret put back, or null if
   * nothing is stored or the stored value is not a credential.
   *
   * @throws SerializationException if the stored credential is malformed.
   */
  override suspend fun loadCredential(authConfig: AuthConfig, context: Context): AuthCredential? {
    val credential =
      when (val stored = context.state[authConfig.credentialKey.value]) {
        null -> return null
        is AuthCredential -> stored
        is Map<*, *> ->
          decodeFromPlainOrNull(AuthCredentialSerializer, stored)
            ?: throw SerializationException("Malformed auth credential.")
        else -> {
          // AuthHandler also reads a bare token here, but this store reads only credentials.
          logger.warn { "Ignoring a ${stored::class.simpleName} stored under a credential key." }
          return null
        }
      }
    return credential.withConfiguredClientSecret(authConfig.rawAuthCredential)
  }

  /**
   * Stores [authConfig]'s exchanged credential without its OAuth2 client secret, or removes any
   * stored one if [authConfig] has no exchanged credential. It stays typed so ADK's session
   * services persist its secrets.
   */
  override suspend fun saveCredential(authConfig: AuthConfig, context: Context) {
    val stored = authConfig.exchangedAuthCredential?.withoutClientSecret()
    context.updateState(authConfig.credentialKey.value, stored ?: State.REMOVED)
  }

  private companion object {
    val logger = LoggerFactory.getLogger(SessionStateCredentialService::class)
  }
}
