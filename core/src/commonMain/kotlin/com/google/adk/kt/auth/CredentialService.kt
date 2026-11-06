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

/**
 * Stores the credentials tools obtain, such as a user's OAuth2 tokens, so that later invocations
 * can reuse them instead of asking the user to sign in again. A failure of the underlying store
 * propagates as an exception.
 */
@ExperimentalAuthApi
interface CredentialService {

  /**
   * Returns the credential stored under [authConfig]'s [AuthConfig.credentialKey] for [context], or
   * null if there is none.
   */
  suspend fun loadCredential(authConfig: AuthConfig, context: Context): AuthCredential?

  /**
   * Stores [authConfig]'s [AuthConfig.exchangedAuthCredential] under its [AuthConfig.credentialKey]
   * for [context]. If that credential is null, a later [loadCredential] returns null.
   */
  suspend fun saveCredential(authConfig: AuthConfig, context: Context)
}
