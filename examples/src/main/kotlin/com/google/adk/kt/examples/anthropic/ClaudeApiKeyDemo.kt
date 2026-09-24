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

package com.google.adk.kt.examples.anthropic

import com.google.adk.kt.agents.Instruction
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.models.anthropic.Claude

/**
 * Example agent that calls Claude through the Anthropic API, authenticated with an API key.
 *
 * The key comes from the `ANTHROPIC_API_KEY` environment variable, which [Claude] also reads when
 * no key is passed.
 */
object ClaudeApiKeyDemo {
  private const val MODEL = "claude-haiku-4-5"

  @JvmField
  val rootAgent =
    LlmAgent(
      name = "claude_api_key_demo",
      model = Claude(name = MODEL, apiKey = System.getenv("ANTHROPIC_API_KEY")),
      instruction = Instruction("You are a helpful assistant. Keep answers concise."),
    )
}
