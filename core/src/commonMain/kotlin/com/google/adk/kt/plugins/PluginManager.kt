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

package com.google.adk.kt.plugins

import com.google.adk.kt.callbacks.AfterAgentCallback
import com.google.adk.kt.callbacks.AfterModelCallback
import com.google.adk.kt.callbacks.AfterRunCallback
import com.google.adk.kt.callbacks.AfterToolCallback
import com.google.adk.kt.callbacks.BeforeAgentCallback
import com.google.adk.kt.callbacks.BeforeModelCallback
import com.google.adk.kt.callbacks.BeforeRunCallback
import com.google.adk.kt.callbacks.BeforeToolCallback
import com.google.adk.kt.callbacks.OnEventCallback
import com.google.adk.kt.callbacks.OnModelErrorCallback
import com.google.adk.kt.callbacks.OnToolErrorCallback
import com.google.adk.kt.callbacks.OnUserMessageCallback
import com.google.adk.kt.logging.LoggerFactory

/**
 * Manages the pre-aggregation of typed functional callbacks.
 *
 * @property plugins The list of registered plugins managed by this instance.
 * @property skipClosingPlugins When `true`, [close] becomes a no-op so that plugins owned by
 *   another (parent) manager are not torn down by this sub-manager. Intended for cases where a
 *   sub-runner shares the parent runner's [Plugin] instances (e.g.
 *   [com.google.adk.kt.tools.AgentTool] propagating parent plugins to the wrapped agent's runner):
 *   the sub-runner must not close plugins it does not own.
 */
class PluginManager(
  val plugins: List<Plugin> = emptyList(),
  val skipClosingPlugins: Boolean = false,
) : AutoCloseable {

  init {
    val duplicates = plugins.groupingBy { it.name }.eachCount().filterValues { it > 1 }.keys
    require(duplicates.isEmpty()) {
      "Duplicate plugin names found: ${duplicates.joinToString { "'$it'" }}."
    }
    for (plugin in plugins) {
      logger.trace { "Plugin '${plugin.name}' registered." }
    }
  }

  internal val onUserMessageCallbacks: List<OnUserMessageCallback> = plugins.map { plugin ->
    OnUserMessageCallback { ctx, msg -> plugin.onUserMessage(ctx, msg) }
  }
  internal val beforeRunCallbacks: List<BeforeRunCallback> = plugins.map { plugin ->
    BeforeRunCallback { ctx -> plugin.beforeRun(ctx) }
  }
  internal val onEventCallbacks: List<OnEventCallback> = plugins.map { plugin ->
    OnEventCallback { ctx, event -> plugin.onEvent(ctx, event) }
  }
  internal val afterRunCallbacks: List<AfterRunCallback> = plugins.map { plugin ->
    AfterRunCallback { ctx -> plugin.afterRun(ctx) }
  }
  internal val beforeAgentCallbacks: List<BeforeAgentCallback> = plugins.map { plugin ->
    BeforeAgentCallback { ctx -> plugin.beforeAgent(ctx) }
  }
  internal val afterAgentCallbacks: List<AfterAgentCallback> = plugins.map { plugin ->
    AfterAgentCallback { ctx -> plugin.afterAgent(ctx) }
  }
  internal val beforeModelCallbacks: List<BeforeModelCallback> = plugins.map { plugin ->
    BeforeModelCallback { ctx, req -> plugin.beforeModel(ctx, req) }
  }
  internal val afterModelCallbacks: List<AfterModelCallback> = plugins.map { plugin ->
    AfterModelCallback { ctx, resp -> plugin.afterModel(ctx, resp) }
  }
  internal val onModelErrorCallbacks: List<OnModelErrorCallback> = plugins.map { plugin ->
    OnModelErrorCallback { ctx, req, err -> plugin.onModelError(ctx, req, err) }
  }
  internal val beforeToolCallbacks: List<BeforeToolCallback> = plugins.map { plugin ->
    BeforeToolCallback { ctx, tool, args -> plugin.beforeTool(ctx, tool, args) }
  }
  internal val afterToolCallbacks: List<AfterToolCallback> = plugins.map { plugin ->
    AfterToolCallback { ctx, tool, args, res -> plugin.afterTool(ctx, tool, args, res) }
  }
  internal val onToolErrorCallbacks: List<OnToolErrorCallback> = plugins.map { plugin ->
    OnToolErrorCallback { ctx, tool, args, err -> plugin.onToolError(ctx, tool, args, err) }
  }

  fun getPlugin(pluginName: String): Plugin? {
    return plugins.find { it.name == pluginName }
  }

  override fun close() {
    if (skipClosingPlugins) {
      logger.trace { "Skipping close() for ${plugins.size} plugin(s); not owned by this manager." }
      return
    }
    val exceptions = mutableListOf<Exception>()
    for (plugin in plugins) {
      try {
        plugin.close()
      } catch (e: Exception) {
        logger.error(e) { "[${plugin.name}] Error during callback 'close'" }
        exceptions.add(e)
      }
    }

    if (exceptions.isNotEmpty()) {
      throw exceptions.fold(RuntimeException("Multiple exceptions occurred during close")) { acc, e
        ->
        acc.addSuppressed(e)
        acc
      }
    }
  }

  companion object {
    private val logger = LoggerFactory.getLogger(PluginManager::class)
  }
}
