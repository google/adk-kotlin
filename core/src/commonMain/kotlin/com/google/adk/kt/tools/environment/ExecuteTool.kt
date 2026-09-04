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

package com.google.adk.kt.tools.environment

import com.google.adk.kt.annotations.ExperimentalEnvironmentApi
import com.google.adk.kt.environment.Environment
import com.google.adk.kt.logging.LoggerFactory
import com.google.adk.kt.tools.BaseTool
import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.types.FunctionDeclaration
import com.google.adk.kt.types.Schema as GenaiSchema
import com.google.adk.kt.types.Type

/** Run a shell command in the environment's working directory. */
@ExperimentalEnvironmentApi
internal class ExecuteTool(private val environment: Environment, private val maxOutputChars: Int) :
  BaseTool(
    name = TOOL_EXECUTE,
    description =
      "Run a shell command in the environment. For running programs, tests, and build " +
        "commands ONLY. WARNING: Do NOT use for file reading -- use the $TOOL_READ_FILE tool " +
        "instead. Shell commands like 'cat, head, tail will produce inferior results. " +
        "Good: Execute(\"python3 script.py\"), Execute(\"pytest\"), Execute(\"find ...\"). " +
        "Bad: Execute(\"head ...\"), Execute(\"cat ...\").",
  ) {

  private val logger = LoggerFactory.getLogger(ExecuteTool::class)

  override fun declaration(): FunctionDeclaration =
    FunctionDeclaration(
      name = name,
      description = description,
      parameters =
        GenaiSchema(
          type = Type.OBJECT,
          properties =
            mapOf(
              PARAM_COMMAND to
                GenaiSchema(
                  type = Type.STRING,
                  description = "The shell command to execute. Chain dependent commands with &&.",
                )
            ),
          required = listOf(PARAM_COMMAND),
        ),
    )

  override suspend fun run(context: ToolContext, args: Map<String, Any>): Map<String, Any> {
    val command = args[PARAM_COMMAND] as? String
    if (command.isNullOrEmpty()) return errorResult("`command` is required.")
    logger.debug { "Execute command: $command" }
    val result =
      environment.execute(context, command, DEFAULT_TIMEOUT).getOrElse { e ->
        logger.debug(e) { "Execute failed: ${e.message}" }
        return e.toEnvironmentErrorResponse(logger)
      }
    logger.debug {
      "Execute result: exit_code=${result.exitCode}, timed_out=${result.timedOut}, " +
        "stdout=${result.stdout.take(200)}, stderr=${result.stderr.take(200)}"
    }
    return buildMap {
      put(KEY_STATUS, STATUS_OK)
      if (result.stdout.isNotEmpty()) put(KEY_STDOUT, truncateOutput(result.stdout, maxOutputChars))
      if (result.stderr.isNotEmpty()) put(KEY_STDERR, truncateOutput(result.stderr, maxOutputChars))
      if (result.exitCode != 0) {
        put(KEY_STATUS, STATUS_ERROR)
        put(KEY_EXIT_CODE, result.exitCode)
      }
      if (result.timedOut) {
        put(KEY_STATUS, STATUS_ERROR)
        put(KEY_ERROR, "Command timed out after ${DEFAULT_TIMEOUT.inWholeSeconds}s.")
      }
    }
  }
}
