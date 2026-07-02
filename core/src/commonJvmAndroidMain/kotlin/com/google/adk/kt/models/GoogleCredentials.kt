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
package com.google.adk.kt.models

import com.google.auth.oauth2.GoogleCredentials as JavaGoogleCredentials
import com.google.genai.kotlin.GoogleCredentials as GenAiGoogleCredentials

/** On JVM and Android, ADK credentials are the Java Auth Library's `GoogleCredentials`. */
actual typealias GoogleCredentials = JavaGoogleCredentials

// The ADK alias, the GenAI SDK alias, and the Java type are the same class here, so this is
// identity.
internal actual fun GoogleCredentials.toGenaiSdk(): GenAiGoogleCredentials = this
