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

import com.google.genai.kotlin.GoogleCredentials as GenAiGoogleCredentials

/**
 * ADK-owned handle to Google Cloud credentials for Vertex AI (see [VertexCredentials]). On JVM and
 * Android it is a `typealias` for `com.google.auth.oauth2.GoogleCredentials`; [toGenaiSdk] bridges
 * it to the GenAI SDK.
 */
expect class GoogleCredentials

/** Bridges an ADK [GoogleCredentials] to the GenAI SDK credentials type. */
internal expect fun GoogleCredentials.toGenaiSdk(): GenAiGoogleCredentials
