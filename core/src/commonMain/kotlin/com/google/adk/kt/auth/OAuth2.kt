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

/**
 * Returns this credential without its OAuth2 client secret, which belongs to the agent's client
 * rather than to the user, so it stays out of anything the user's app can read.
 */
internal fun AuthCredential.withoutClientSecret(): AuthCredential =
  if (this is AuthCredential.OAuth2) copy(clientSecret = null) else this

/**
 * Returns this credential with [raw]'s OAuth2 client ID and secret when both are OAuth2, so the app
 * can't choose the client.
 */
internal fun AuthCredential.withConfiguredClient(raw: AuthCredential?): AuthCredential =
  if (this is AuthCredential.OAuth2 && raw is AuthCredential.OAuth2) {
    copy(clientId = raw.clientId, clientSecret = raw.clientSecret)
  } else {
    this
  }

/**
 * Returns this credential with [raw]'s OAuth2 client secret when both are OAuth2, since a
 * credential read back from a store that drops the secret needs it to exchange or refresh a token.
 */
internal fun AuthCredential.withConfiguredClientSecret(raw: AuthCredential?): AuthCredential =
  if (this is AuthCredential.OAuth2 && raw is AuthCredential.OAuth2) {
    copy(clientSecret = raw.clientSecret)
  } else {
    this
  }
