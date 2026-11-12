/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:OptIn(FrameworkInternalApi::class)

package com.google.adk.kt.webserver

import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.auth.withoutCredentialSecrets
import com.google.adk.kt.serialization.adkJson
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement

/**
 * Returns [value] as JSON without credential secrets. Every response that carries sessions or
 * events must go through it.
 */
internal inline fun <reified T> redactedJson(value: T): JsonElement =
  adkJson.encodeToJsonElement(value).withoutCredentialSecrets()
