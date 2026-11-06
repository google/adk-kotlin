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

import com.google.adk.kt.agents.Context
import com.google.adk.kt.annotations.ExperimentalAuthApi

/** The scope a stored credential is shared in: one app, one user and one [CredentialKey]. */
internal data class UserCredentialKey(
  val appName: String,
  val userId: String,
  val credentialKey: CredentialKey,
) {
  // The user ID is user data; the credential key redacts itself.
  override fun toString(): String =
    "UserCredentialKey(appName=$appName, userId=${userId.redacted()}, credentialKey=$credentialKey)"
}

/** Returns the scope this context's user shares [authConfig]'s stored credential in. */
internal fun Context.userCredentialKey(authConfig: AuthConfig): UserCredentialKey {
  // Not `session`, which copies the whole session on every read.
  val sessionKey = invocationContext.session.key
  return UserCredentialKey(sessionKey.appName, sessionKey.userId, authConfig.credentialKey)
}
