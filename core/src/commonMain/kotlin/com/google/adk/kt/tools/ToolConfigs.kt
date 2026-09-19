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

package com.google.adk.kt.tools

/**
 * The base class for a tool's own config.
 *
 * A tool that is configurable from an agent document declares its own config type on top of this
 * one and builds it from the free-form [ToolArgsConfig] the document carried, reading the keys it
 * declares and leaving the rest alone:
 * ```
 * class WeatherToolConfig(val city: String, val units: String) : BaseToolConfig() {
 *   companion object {
 *     fun fromArgs(args: ToolArgsConfig) =
 *       WeatherToolConfig(
 *         city = requireNotNull(args.values["city"] as? String) { "a weather tool needs a city" },
 *         units = args.values["units"] as? String ?: "metric",
 *       )
 *   }
 * }
 * ```
 */
abstract class BaseToolConfig

/**
 * The free key-value pairs a document carries for one tool, in [ToolConfig.args].
 *
 * The keys and the value types are whatever the tool asked for, so they arrive exactly as the
 * parser produced them and are read by the tool itself, typically into a [BaseToolConfig] subclass.
 */
data class ToolArgsConfig(val values: Map<String, Any?> = emptyMap())

/**
 * One tool as it is written down in an agent document: a [name] and, optionally, the [args] for
 * whatever that name resolves to.
 *
 * The same two keys cover every way a tool can be named:
 * ```yaml
 * tools:
 *   # An ADK built-in, by its bare name.
 *   - name: google_search
 *   # An ADK built-in that takes arguments.
 *   - name: AgentTool
 *     args:
 *       agent: ./another_agent.yaml
 *       skip_summarization: true
 *   # A tool instance, a tool class, a tool-generating function or a function tool the user
 *   # defined, by fully qualified path, with the arguments to build it where it takes any.
 *   - name: my_package.my_module.my_tool
 *   - name: my_package.my_module.MyToolClass
 *     args:
 *       my_tool_arg1: value1
 * ```
 *
 * A name with nothing after it has no [args] at all, which is distinct from a name whose args are
 * empty: the first says "use this tool as it is", the second "build one with no arguments".
 */
data class ToolConfig(val name: String, val args: ToolArgsConfig? = null) {

  companion object {
    private const val NAME_KEY = "name"
    private const val ARGS_KEY = "args"

    /**
     * Reads one entry of a document's `tools:` list, as a YAML or JSON parser hands it back.
     *
     * The failure messages name keys and types but never a value, because an `args` value can hold
     * a credential.
     *
     * @throws IllegalArgumentException if the entry has no `name`, if its `args` are not a mapping
     *   keyed by strings, or if it carries a key the format does not define. Ignoring an undefined
     *   key would load a document that says `arg:` as a tool with none of its arguments, and report
     *   nothing.
     */
    fun fromMap(entry: Map<String, Any?>): ToolConfig {
      val undefined = entry.keys - NAME_KEY - ARGS_KEY
      require(undefined.isEmpty()) { "unknown key(s) in tool config: ${undefined.sorted()}" }
      val name = entry[NAME_KEY]
      require(name is String) { "a tool config needs a string $NAME_KEY" }
      return ToolConfig(name = name, args = entry[ARGS_KEY]?.let(::toArgs))
    }

    private fun toArgs(raw: Any): ToolArgsConfig {
      require(raw is Map<*, *>) { "$ARGS_KEY must be a mapping, got ${raw::class.simpleName}" }
      return ToolArgsConfig(
        raw.entries.associate { (key, value) ->
          require(key is String) { "$ARGS_KEY keys must be strings" }
          key to value
        }
      )
    }
  }
}
