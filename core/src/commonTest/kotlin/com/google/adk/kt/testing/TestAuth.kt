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

package com.google.adk.kt.testing

import com.google.adk.kt.annotations.ExperimentalAuthApi
import com.google.adk.kt.auth.AuthConfig
import com.google.adk.kt.auth.AuthCredential
import com.google.adk.kt.auth.AuthScheme
import com.google.adk.kt.auth.CredentialKey
import com.google.adk.kt.auth.Secret

/**
 * Returns a config for an API key sent in the `X-Key` header, with [rawKey] as the configured key
 * and a derived credential key.
 */
fun testApiKeyConfig(rawKey: String): AuthConfig =
  AuthConfig(
    AuthScheme.ApiKey(name = "X-Key", location = AuthScheme.ApiKey.Location.HEADER),
    AuthCredential.ApiKey(Secret(rawKey)),
  )

/** Returns a config for an HTTP bearer token, with `key` as the credential key. */
fun testHttpBearerConfig(): AuthConfig =
  AuthConfig(AuthScheme.Http.bearer(), credentialKey = CredentialKey("key"))

/**
 * The state key under which the app's sign-in reply for this config lasts for one invocation,
 * spelled out rather than taken from the code under test.
 */
val AuthConfig.signInReplyStateKey: String
  get() = "temp:${credentialKey.value}"
