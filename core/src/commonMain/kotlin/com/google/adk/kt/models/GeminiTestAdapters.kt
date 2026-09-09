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

import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.GenerateContentResponse
import com.google.adk.kt.types.fromGenaiSdk
import com.google.adk.kt.types.toGenaiSdk
import com.google.genai.kotlin.types.Content as GenAiContent
import com.google.genai.kotlin.types.GenerateContentResponse as GenAiGenerateContentResponse

// Opt-in-gated bridges to the ADK types [Gemini.GeminiModels] speaks, for a test double in another
// module that serializes over the SDK types and needs only the last step to and from ADK.

/**
 * Converts a GenAI SDK response to the ADK [GenerateContentResponse] [Gemini.GeminiModels] returns.
 */
@FrameworkInternalApi
fun GenAiGenerateContentResponse.toAdkResponse(): GenerateContentResponse = fromGenaiSdk()

/** Converts an ADK [Content] a [Gemini.GeminiModels] call received back to a GenAI SDK content. */
@FrameworkInternalApi fun Content.toGenAiContent(): GenAiContent = toGenaiSdk()
