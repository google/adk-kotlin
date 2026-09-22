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
package com.google.adk.kt.models.springai

import com.google.adk.kt.agents.ReadonlyContext
import com.google.adk.kt.tools.BaseTool
import com.google.adk.kt.tools.Toolset
import org.springframework.ai.tool.ToolCallbackProvider

/**
 * Exposes every Spring AI tool from a [ToolCallbackProvider] as an ADK [Toolset], so a
 * dependency-injected provider (for example a Spring bean) contributes its tools to an ADK agent.
 * Each callback is wrapped with [SpringAiTool], and the provider is queried per request so tools it
 * adds later are picked up.
 *
 * @param toolCallbackProvider The Spring AI provider whose tools to expose.
 */
class SpringAiToolset(private val toolCallbackProvider: ToolCallbackProvider) : Toolset {
  override suspend fun getTools(readonlyContext: ReadonlyContext?): List<BaseTool> =
    toolCallbackProvider.toolCallbacks.map { SpringAiTool(it) }
}
