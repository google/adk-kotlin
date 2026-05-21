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
package com.google.adk.kt.testing

import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.models.LlmResponse
import kotlinx.coroutines.flow.flowOf

/**
 * Builds an [LlmAgent] whose model unconditionally returns a single text part. Useful as a minimal
 * child when the test cares only about ordering or routing, not about tool calls.
 */
fun textAgent(name: String, text: String, description: String = ""): LlmAgent {
  val model = DummyModel("model-$name") { flowOf(LlmResponse(content = modelMessage(text))) }
  return LlmAgent(name = name, description = description, model = model)
}
