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
import com.google.adk.kt.collections.concurrentMutableMapOf

/**
 * A [CredentialService] that keeps credentials in memory per app, user and credential key, so every
 * session of a user shares them. It keeps each credential as saved, client secret included, and
 * loses them all when the process exits.
 */
@ExperimentalAuthApi
class InMemoryCredentialService : CredentialService {

  private val credentials = concurrentMutableMapOf<UserCredentialKey, AuthCredential>()

  override suspend fun loadCredential(authConfig: AuthConfig, context: Context): AuthCredential? =
    credentials[context.userCredentialKey(authConfig)]

  override suspend fun saveCredential(authConfig: AuthConfig, context: Context) {
    val key = context.userCredentialKey(authConfig)
    val credential = authConfig.exchangedAuthCredential
    if (credential == null) credentials.remove(key) else credentials[key] = credential
  }
}
