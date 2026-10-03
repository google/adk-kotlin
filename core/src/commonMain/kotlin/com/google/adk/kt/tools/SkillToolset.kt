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

import com.google.adk.kt.agents.ReadonlyContext
import com.google.adk.kt.annotations.AdkJavaInteropApi
import com.google.adk.kt.annotations.ExperimentalEnvironmentApi
import com.google.adk.kt.environment.Environment
import com.google.adk.kt.logging.Logger
import com.google.adk.kt.logging.LoggerFactory
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.skills.Frontmatter
import com.google.adk.kt.skills.SkillSource
import com.google.adk.kt.skills.SkillSourceException
import com.google.adk.kt.tools.environment.DEFAULT_MAX_OUTPUT_CHARS
import com.google.adk.kt.tools.environment.toEnvironmentErrorResponse
import com.google.adk.kt.tools.environment.truncateOutput
import com.google.adk.kt.types.FunctionDeclaration
import com.google.adk.kt.types.Schema as GenaiSchema
import com.google.adk.kt.types.Type
import kotlin.jvm.JvmStatic
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Builds the standard `{error}` response map used by all skill tools. */
private fun errorResponse(message: String): Map<String, Any?> =
  mapOf(SkillToolset.KEY_ERROR to message)

/**
 * Maps a [Result.failure] from a [SkillSource] method into a tool error response.
 *
 * The [SkillSource] contract states that the only exception a method should wrap in
 * [Result.failure] is [SkillSourceException]; its message is forwarded verbatim to the LLM.
 *
 * If a custom implementation wraps a different [Throwable] in [Result.failure] (a contract
 * violation), the failure is logged and re-thrown. Errors that the source throws bypass this helper
 * entirely and propagate to the caller.
 */
private fun Throwable.toSkillSourceErrorResponse(logger: Logger): Map<String, Any?> {
  if (this is SkillSourceException) {
    return errorResponse(message ?: "An unspecified skill source error occurred.")
  }
  logger.warn(this) {
    "SkillSource returned Result.failure wrapping an unrecognized exception type (${this::class.simpleName})."
  }
  throw this
}

/** BaseTool to list all available skills. */
internal class ListSkillsTool(private val toolset: SkillToolset) :
  BaseTool(
    name = SkillToolset.TOOL_NAME_LIST_SKILLS,
    description = "Lists all available skills with their names and descriptions.",
  ) {
  private val logger = LoggerFactory.getLogger(ListSkillsTool::class)

  override fun declaration(): FunctionDeclaration {
    return FunctionDeclaration(
      name = name,
      description = description,
      parameters = GenaiSchema(type = Type.OBJECT, properties = emptyMap()),
    )
  }

  override suspend fun run(context: ToolContext, args: Map<String, Any?>): Map<String, Any?> {
    return toolset.source
      .listFrontmatters()
      .fold(
        onSuccess = { frontmatters -> mapOf("skills" to frontmatters.map { it.frontmatterDsl() }) },
        onFailure = { e -> e.toSkillSourceErrorResponse(logger) },
      )
  }
}

private fun Frontmatter.frontmatterDsl() =
  mapOf(
    "name" to name,
    "description" to description,
    "license" to license,
    "compatibility" to compatibility,
    "allowed_tools" to allowedTools,
    "metadata" to metadata,
  )

/** BaseTool responsible for loading the instructions for a specific skill. */
internal class LoadSkillTool(private val toolset: SkillToolset) :
  BaseTool(
    name = SkillToolset.TOOL_NAME_LOAD_SKILL,
    description = "Loads the SKILL.md instructions for a given skill.",
  ) {
  private val logger = LoggerFactory.getLogger(LoadSkillTool::class)

  override fun declaration(): FunctionDeclaration {
    return FunctionDeclaration(
      name = name,
      description = description,
      parameters =
        GenaiSchema(
          type = Type.OBJECT,
          properties =
            mapOf(
              SkillToolset.PARAM_SKILL_NAME to
                GenaiSchema(type = Type.STRING, description = "The name of the skill to load.")
            ),
          required = listOf(SkillToolset.PARAM_SKILL_NAME),
        ),
    )
  }

  override suspend fun run(context: ToolContext, args: Map<String, Any?>): Map<String, Any?> {
    val skillName =
      args[SkillToolset.PARAM_SKILL_NAME] as? String
        ?: return errorResponse("Skill name is required.")

    val frontmatter =
      toolset.source.loadFrontmatter(skillName).getOrElse { e ->
        return e.toSkillSourceErrorResponse(logger)
      }

    val instructions =
      toolset.source.loadInstructions(skillName).getOrElse { e ->
        return e.toSkillSourceErrorResponse(logger)
      }

    return mapOf(
      SkillToolset.PARAM_SKILL_NAME to skillName,
      SkillToolset.KEY_INSTRUCTIONS to instructions,
      SkillToolset.KEY_FRONTMATTER to frontmatter.frontmatterDsl(),
    )
  }
}

/** BaseTool responsible for loading resources (references/assets/scripts) from a specific skill. */
internal class LoadSkillResourceTool(private val toolset: SkillToolset) :
  BaseTool(
    name = SkillToolset.TOOL_NAME_LOAD_SKILL_RESOURCE,
    description =
      "Loads a resource file (from references/, assets/, or scripts/) from within a skill.",
  ) {
  private val logger = LoggerFactory.getLogger(LoadSkillResourceTool::class)

  override fun declaration(): FunctionDeclaration {
    return FunctionDeclaration(
      name = name,
      description = description,
      parameters =
        GenaiSchema(
          type = Type.OBJECT,
          properties =
            mapOf(
              SkillToolset.PARAM_SKILL_NAME to
                GenaiSchema(type = Type.STRING, description = "The name of the skill."),
              SkillToolset.PARAM_PATH to
                GenaiSchema(
                  type = Type.STRING,
                  description =
                    "The relative path to the resource (e.g., 'references/my_doc.md', 'assets/template.txt', or 'scripts/setup.sh').",
                ),
            ),
          required = listOf(SkillToolset.PARAM_SKILL_NAME, SkillToolset.PARAM_PATH),
        ),
    )
  }

  override suspend fun run(context: ToolContext, args: Map<String, Any?>): Map<String, Any?> {
    val skillName =
      args[SkillToolset.PARAM_SKILL_NAME] as? String
        ?: return errorResponse("Skill name is required.")

    val resourcePath =
      args[SkillToolset.PARAM_PATH] as? String ?: return errorResponse("Resource path is required.")

    val bytes =
      toolset.source.loadResource(skillName, resourcePath).getOrElse { e ->
        return e.toSkillSourceErrorResponse(logger)
      }

    val content =
      try {
        bytes.decodeToString(throwOnInvalidSequence = true)
      } catch (e: CharacterCodingException) {
        logger.debug(e) {
          "Failed to decode resource $resourcePath for skill $skillName as string. Treating as binary."
        }
        null
      }

    val result =
      mutableMapOf<String, Any?>(
        SkillToolset.PARAM_SKILL_NAME to skillName,
        SkillToolset.PARAM_PATH to resourcePath,
      )

    if (content != null) {
      result[SkillToolset.KEY_CONTENT] = content
    } else {
      result[SkillToolset.KEY_STATUS] = SkillToolset.MSG_BINARY_FILE
    }

    return result
  }
}

/**
 * Whether [skillName] names a single directory, so that `skills/<skill_name>` stays inside the
 * skills directory.
 */
private fun isSingleSegmentSkillName(skillName: String): Boolean =
  skillName != "." && skillName != ".." && '/' !in skillName && '\\' !in skillName

/**
 * Resolves [filePath] to a path under a skill's `scripts/` directory, or returns `null` if it does
 * not name a file there.
 *
 * A skill refers to its own scripts by name, so both `run.sh` and `scripts/run.sh` are accepted, as
 * are nested paths such as `scripts/lib/run.sh`. A `..` segment is rejected rather than resolved:
 * no skill needs to reach outside its `scripts/` directory, and allowing traversal would make an
 * arbitrary file executable, including a non-script resource of the same skill.
 */
private fun resolveScriptPath(filePath: String): String? {
  if (filePath.startsWith("/")) return null

  val segments = mutableListOf<String>()
  for (segment in filePath.split('/')) {
    when (segment) {
      "",
      "." -> {}
      ".." -> return null
      else -> segments.add(segment)
    }
  }

  val relativeToScripts =
    if (segments.firstOrNull() == SkillSource.DIR_SCRIPTS) segments.drop(1) else segments
  // Require a file inside scripts/, rather than the directory itself.
  if (relativeToScripts.isEmpty()) return null
  return (listOf(SkillSource.DIR_SCRIPTS) + relativeToScripts).joinToString("/")
}

/**
 * BaseTool that runs a script from a skill's `scripts/` directory inside the toolset's environment.
 *
 * The skill's resources are copied into the environment before each run, then the model-written
 * command runs unchanged, as in ADK Python.
 */
@OptIn(ExperimentalEnvironmentApi::class)
internal class RunSkillScriptTool(
  private val toolset: SkillToolset,
  private val environment: Environment,
  private val scriptTimeout: Duration,
) :
  BaseTool(
    name = SkillToolset.TOOL_NAME_RUN_SKILL_SCRIPT,
    description = "Executes a script from a skill's scripts/ directory.",
  ) {
  private val logger = LoggerFactory.getLogger(RunSkillScriptTool::class)

  override fun declaration(): FunctionDeclaration {
    return FunctionDeclaration(
      name = name,
      description = description,
      parameters =
        GenaiSchema(
          type = Type.OBJECT,
          properties =
            mapOf(
              SkillToolset.PARAM_SKILL_NAME to
                GenaiSchema(type = Type.STRING, description = "The name of the skill."),
              SkillToolset.PARAM_FILE_PATH to
                GenaiSchema(
                  type = Type.STRING,
                  description = "The relative path to the script (e.g., 'scripts/setup.py').",
                ),
              SkillToolset.PARAM_COMMAND to
                GenaiSchema(
                  type = Type.STRING,
                  description = "The command to execute in the environment.",
                ),
            ),
          required =
            listOf(
              SkillToolset.PARAM_SKILL_NAME,
              SkillToolset.PARAM_FILE_PATH,
              SkillToolset.PARAM_COMMAND,
            ),
        ),
    )
  }

  override suspend fun run(context: ToolContext, args: Map<String, Any?>): Map<String, Any?> {
    val skillName = args[SkillToolset.PARAM_SKILL_NAME] as? String
    if (skillName.isNullOrEmpty()) return errorResponse("Skill name is required.")
    if (!isSingleSegmentSkillName(skillName)) {
      return errorResponse(
        "Invalid `${SkillToolset.PARAM_SKILL_NAME}`: $skillName must name a single skill."
      )
    }

    val filePath = args[SkillToolset.PARAM_FILE_PATH] as? String
    if (filePath.isNullOrEmpty()) return errorResponse("Script path is required.")

    val scriptPath =
      resolveScriptPath(filePath)
        ?: return errorResponse(
          "Invalid script path: $filePath must be within '${SkillSource.DIR_SCRIPTS}/'."
        )

    val command = args[SkillToolset.PARAM_COMMAND] as? String
    if (command.isNullOrBlank()) {
      return errorResponse("`${SkillToolset.PARAM_COMMAND}` is required.")
    }

    // Verifies both the skill and the script exist before touching the environment.
    val unused =
      toolset.source.loadResource(skillName, scriptPath).getOrElse { e ->
        return e.toSkillSourceErrorResponse(logger)
      }
    val copyError = copySkill(context, skillName)
    if (copyError != null) return copyError
    val result =
      environment.execute(context, command, scriptTimeout).getOrElse { e ->
        return e.toEnvironmentErrorResponse()
      }

    val status =
      if (result.exitCode == 0 && !result.timedOut) {
        SkillToolset.STATUS_OK
      } else {
        SkillToolset.STATUS_ERROR
      }

    return buildMap {
      put(SkillToolset.PARAM_SKILL_NAME, skillName)
      put(SkillToolset.PARAM_FILE_PATH, scriptPath)
      put(SkillToolset.KEY_STATUS, status)
      put(SkillToolset.KEY_STDOUT, truncateOutput(result.stdout, DEFAULT_MAX_OUTPUT_CHARS))
      put(SkillToolset.KEY_STDERR, truncateOutput(result.stderr, DEFAULT_MAX_OUTPUT_CHARS))
      put(SkillToolset.KEY_EXIT_CODE, result.exitCode)
      if (result.timedOut) {
        put(SkillToolset.KEY_ERROR, "Script timed out after $scriptTimeout.")
      }
    }
  }

  /**
   * Copies every resource of [skillName] into `skills/<skill_name>/` in the environment, returning
   * an error response if a read or write fails. Files removed from the skill stay in the
   * environment, since [Environment] has no delete.
   */
  private suspend fun copySkill(context: ToolContext, skillName: String): Map<String, Any?>? {
    val skillDir = "${SkillToolset.ENV_SKILLS_DIR}/$skillName"
    for (directory in SkillSource.VALID_RESOURCE_DIRS) {
      val resourcePaths =
        toolset.source.listResources(skillName, directory).getOrElse { e ->
          // A source reports a resource directory the skill lacks as a failure.
          if (e !is SkillSourceException) return e.toSkillSourceErrorResponse(logger)
          emptyList()
        }
      for (resourcePath in resourcePaths) {
        val bytes =
          toolset.source.loadResource(skillName, resourcePath).getOrElse { e ->
            return e.toSkillSourceErrorResponse(logger)
          }
        environment.writeFile(context, "$skillDir/$resourcePath", bytes).getOrElse { e ->
          return e.toEnvironmentErrorResponse()
        }
      }
    }
    return null
  }
}

/**
 * Toolset exposing the skills a [SkillSource] provides. When [environment] is set, it also exposes
 * `run_skill_script`, which copies the skill into `skills/<skill_name>/` in the environment and
 * runs the model's command there.
 *
 * @param source Where skills and their resources are read from.
 * @param environment Environment used to execute skill scripts.
 * @param scriptTimeout Maximum execution time for a single skill script, 300 seconds by default.
 */
@OptIn(ExperimentalEnvironmentApi::class)
class SkillToolset
@ExperimentalEnvironmentApi
constructor(
  internal val source: SkillSource,
  private val environment: Environment?,
  private val scriptTimeout: Duration = DEFAULT_SCRIPT_TIMEOUT,
) : Toolset {

  /** Creates a toolset that reads skills from [source] but cannot execute their scripts. */
  constructor(source: SkillSource) : this(source, environment = null)

  companion object {
    /** The name of the tool used to list available skills. */
    const val TOOL_NAME_LIST_SKILLS = "list_skills"
    /** The name of the tool used to load a skill's instructions. */
    const val TOOL_NAME_LOAD_SKILL = "load_skill"
    /** The name of the tool used to load a skill's resource file. */
    const val TOOL_NAME_LOAD_SKILL_RESOURCE = "load_skill_resource"
    /** The name of the tool used to run a skill's script. */
    internal const val TOOL_NAME_RUN_SKILL_SCRIPT = "run_skill_script"

    /** Parameter key for the skill name. */
    const val PARAM_SKILL_NAME = "skill_name"
    /** Parameter key for the resource path used in the load_skill_resource tool. */
    const val PARAM_PATH = "path"
    /** Parameter key for the script path used in the run_skill_script tool. */
    internal const val PARAM_FILE_PATH = "file_path"
    /** Parameter key for the command used in the run_skill_script tool. */
    internal const val PARAM_COMMAND = "command"

    /** Response map key containing the human-readable error message. */
    const val KEY_ERROR = "error"
    /** Response map key containing the loaded skill instructions. */
    const val KEY_INSTRUCTIONS = "instructions"
    /** Response map key containing the skill's frontmatter metadata. */
    const val KEY_FRONTMATTER = "frontmatter"
    /** Response map key containing the loaded resource content. */
    const val KEY_CONTENT = "content"
    /** Response map key containing the status of a script execution or resource loading. */
    const val KEY_STATUS = "status"
    /** Response map key containing a script's standard output. */
    internal const val KEY_STDOUT = "stdout"
    /** Response map key containing a script's standard error. */
    internal const val KEY_STDERR = "stderr"
    /** Response map key containing a script's exit code. */
    internal const val KEY_EXIT_CODE = "exit_code"

    /** Status value reported when a script exits with code zero within the timeout. */
    internal const val STATUS_OK = "ok"
    /** Status value reported when a script exits with a non-zero code or times out. */
    internal const val STATUS_ERROR = "error"

    /** Message indicating that a loaded resource is a binary file. */
    const val MSG_BINARY_FILE = "Binary file detected. Content not shown."

    /** Directory, relative to the environment's working directory, holding the copied skills. */
    internal const val ENV_SKILLS_DIR = "skills"

    /** Default maximum execution time for a single skill script. */
    internal val DEFAULT_SCRIPT_TIMEOUT: Duration = 300.seconds

    @AdkJavaInteropApi @ExperimentalEnvironmentApi @JvmStatic fun builder(): Builder = Builder()
  }

  /**
   * Fluent builder for [SkillToolset], provided primarily for Java callers. Any property left unset
   * falls back to the same default as the constructor.
   */
  @AdkJavaInteropApi
  @ExperimentalEnvironmentApi
  @Suppress("ScopeReceiverThis") // Java-style builder for Java interop.
  class Builder {
    private var source: SkillSource? = null
    private var environment: Environment? = null
    private var scriptTimeout: Duration = DEFAULT_SCRIPT_TIMEOUT

    fun source(source: SkillSource): Builder = apply { this.source = source }

    fun environment(environment: Environment?): Builder = apply { this.environment = environment }

    fun scriptTimeout(scriptTimeout: Duration): Builder = apply {
      this.scriptTimeout = scriptTimeout
    }

    /**
     * Sets [scriptTimeout] in milliseconds. Java cannot call [scriptTimeout], whose JVM name is
     * mangled because [Duration] is a value class.
     */
    fun scriptTimeoutMillis(scriptTimeoutMillis: Long): Builder = apply {
      this.scriptTimeout = scriptTimeoutMillis.milliseconds
    }

    fun build(): SkillToolset =
      SkillToolset(
        source = checkNotNull(source) { "source is required." },
        environment = environment,
        scriptTimeout = scriptTimeout,
      )
  }

  private val logger = LoggerFactory.getLogger(SkillToolset::class)

  private val tools: List<BaseTool> =
    listOfNotNull(
      ListSkillsTool(this),
      LoadSkillTool(this),
      LoadSkillResourceTool(this),
      environment?.let { RunSkillScriptTool(this, it, scriptTimeout) },
    )

  override suspend fun getTools(readonlyContext: ReadonlyContext?): List<BaseTool> {
    // Not cached: the environment's initialize() is idempotent and knows what is prepared.
    environment?.initialize()
    return tools
  }

  /** Closes the environment, if one was supplied. [Environment.close] is idempotent. */
  override fun close() {
    environment?.close()
  }

  override suspend fun processLlmRequest(
    toolContext: ToolContext,
    llmRequest: LlmRequest,
  ): LlmRequest {
    val catalogInstruction = getSkillCatalogInstruction() ?: return llmRequest
    val instructionContent =
      com.google.adk.kt.types.Content(
        parts = listOf(com.google.adk.kt.types.Part(text = catalogInstruction))
      )
    return llmRequest.appendInstructions(instructionContent)
  }

  /** Generates instructions detailing the available skills to append to LLM requests. */
  internal suspend fun getSkillCatalogInstruction(): String? {
    val frontmatters =
      source.listFrontmatters().getOrElse { e ->
        logger.warn(e) { "Failed to list skill frontmatters; omitting skill catalog instruction." }
        return null
      }
    if (frontmatters.isEmpty()) return null

    val skillsXml = buildString {
      appendLine("<available_skills>")
      for (fm in frontmatters) {
        appendLine("  <skill name=\"${fm.name}\">")
        appendLine("    <description>${fm.description}</description>")
        // Optionally include other frontmatter fields
        appendLine("  </skill>")
      }
      appendLine("</available_skills>")
    }

    val scriptRules =
      if (environment == null) {
        ""
      } else {
        "\n" +
          """
          4. Use `run_skill_script` to run scripts from a skill's `scripts/` directory. Do NOT use other tools to run these scripts. Before each run the skill's files are copied into the environment under `$ENV_SKILLS_DIR/<skill_name>/`, so write the `command` the way the skill's SKILL.md says to run the script, starting with `cd $ENV_SKILLS_DIR/<skill_name> &&` so that its `references/` and `assets/` paths resolve. For example: `cd $ENV_SKILLS_DIR/<skill_name> && python3 scripts/<script_name> <arguments>`.
          5. Do NOT call `run_skill_script` twice for the same skill in one response: the calls run in parallel and each one re-copies the whole skill, so they would overwrite the files the other is running. Wait for the result before running another script from that skill. Scripts from *different* skills may be run together.
          6. If `run_skill_script` returns an error, do not retry the same script or guess a different script path. Report the error to the user and stop.
          """
            .trimIndent()
      }

    return """
You can use specialized 'skills' to help you with complex tasks. You MUST use the skill tools to interact with these skills.

Skills are folders of instructions and resources that extend your capabilities for specialized tasks. Each skill folder contains:
- **SKILL.md** (required): The main instruction file with skill metadata and detailed markdown instructions.
- **references/** (Optional): Additional documentation or examples for skill usage.
- **assets/** (Optional): Templates, scripts or other resources used by the skill.
- **scripts/** (Optional): Executable scripts that can be run via bash.

This is very important:

1. If a skill seems relevant to the current user query, you MUST use the `load_skill` tool with `skill_name=\"<SKILL_NAME>\"` to read its full instructions before proceeding.
2. Once you have read the instructions, follow them exactly as documented before replying to the user. For example, If the instruction lists multiple steps, please make sure you complete all of them in order.
3. The `load_skill_resource` tool is for viewing files within a skill's directory (e.g., `references/*`, `assets/*`, `scripts/*`). Do NOT use other tools to access these files.$scriptRules

$skillsXml
"""
      .trimIndent()
  }
}
