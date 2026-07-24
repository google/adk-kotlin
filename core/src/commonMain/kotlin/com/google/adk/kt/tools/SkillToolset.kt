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
import com.google.adk.kt.annotations.ExperimentalSkillTools
import com.google.adk.kt.logging.Logger
import com.google.adk.kt.logging.LoggerFactory
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.skills.Frontmatter
import com.google.adk.kt.skills.SkillSource
import com.google.adk.kt.skills.SkillSourceException
import com.google.adk.kt.types.FunctionDeclaration
import com.google.adk.kt.types.Schema as GenaiSchema
import com.google.adk.kt.types.Type

private inline fun <T : Any> Iterable<T>.forEachDistinctReference(action: (T) -> Unit) {
  val seen = mutableListOf<T>()
  for (value in this) {
    if (seen.none { it === value }) {
      seen += value
      action(value)
    }
  }
}

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

  override suspend fun run(context: ToolContext, args: Map<String, Any>): Map<String, Any?> {
    return toolset.source
      .listFrontmatters()
      .fold(
        onSuccess = { frontmatters -> mapOf("skills" to frontmatters.map { it.frontmatterDsl() }) },
        onFailure = { e -> e.toSkillSourceErrorResponse(logger) },
      )
  }
}

private fun Frontmatter.frontmatterDsl(): Map<String, Any?> =
  mapOf(
    "name" to name,
    "description" to description,
    "license" to license,
    "compatibility" to compatibility,
    "allowed_tools" to allowedTools,
    "metadata" to metadata,
  )

private fun Frontmatter.additionalToolNames(): List<String> {
  val value =
    metadata[SkillToolset.ADK_ADDITIONAL_TOOLS_METADATA_KEY] as? List<*>
      ?: return emptyList()
  return value.filterIsInstance<String>()
}

private fun additionalToolNamesBySkill(value: Any?): Map<String, List<String>> = buildMap {
  for ((skillName, toolNames) in (value as? Map<*, *>).orEmpty()) {
    val validSkillName = skillName as? String ?: continue
    val validToolNames = (toolNames as? List<*>)?.filterIsInstance<String>() ?: continue
    put(validSkillName, validToolNames)
  }
}

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

  override suspend fun run(context: ToolContext, args: Map<String, Any>): Map<String, Any?> {
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

    recordSkillActivation(context, skillName, frontmatter.additionalToolNames())

    return mapOf(
      SkillToolset.PARAM_SKILL_NAME to skillName,
      SkillToolset.KEY_INSTRUCTIONS to instructions,
      SkillToolset.KEY_FRONTMATTER to frontmatter.frontmatterDsl(),
    )
  }

  /**
   * Records [skillName] and its [additionalToolNames] in the per-agent activation state.
   *
   * The activation state is split across two stores in adk-kotlin: the pending
   * [ToolContext.actions.stateDelta] (not yet applied to the session) and the already-applied
   * [ToolContext.context.state]. To preserve other skills that have been activated earlier in the
   * same invocation, both stores are merged before writing the updated values back into
   * `stateDelta`. Activated skill names and their additional-tool snapshots use separate keys so
   * subsequent model steps can expose the tools without loading the skill frontmatter again. A
   * later load of the same skill replaces that skill's tool-name snapshot.
   *
   * Note: when the LLM emits multiple `load_skill` calls in the same step, ADK runs them in
   * parallel and the per-call deltas are merged with last-write-wins semantics
   * ([EventActions.mergeWith]). Only the activation state recorded by the final merge survives the
   * step. This matches adk-python's behavior and is a known limitation; downstream tool exposure
   * still works for serial `load_skill` calls across steps.
   */
  private fun recordSkillActivation(
    context: ToolContext,
    skillName: String,
    additionalToolNames: List<String>,
  ) {
    appendUniqueStateValues(
      context,
      SkillToolset.activatedSkillStateKey(context.context.agentName),
      listOf(skillName),
    )
    replaceAdditionalToolNames(context, skillName, additionalToolNames)
  }

  private fun appendUniqueStateValues(
    context: ToolContext,
    stateKey: String,
    newValues: List<String>,
  ) {
    val pending = (context.actions.stateDelta[stateKey] as? List<*>).orEmpty()
    val applied = (context.context.state[stateKey] as? List<*>).orEmpty()
    context.actions.stateDelta[stateKey] =
      (applied + pending + newValues).filterIsInstance<String>().distinct()
  }

  private fun replaceAdditionalToolNames(
    context: ToolContext,
    skillName: String,
    additionalToolNames: List<String>,
  ) {
    val stateKey = SkillToolset.activatedSkillToolsStateKey(context.context.agentName)
    val merged =
      LinkedHashMap<String, List<String>>().apply {
        putAll(additionalToolNamesBySkill(context.context.state[stateKey]))
        putAll(additionalToolNamesBySkill(context.actions.stateDelta[stateKey]))
        put(skillName, additionalToolNames.toList())
      }
    context.actions.stateDelta[stateKey] = merged
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

  override suspend fun run(context: ToolContext, args: Map<String, Any>): Map<String, Any?> {
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
 * Toolset that manages and provides access to a collection of skills.
 *
 * The constructors accepting [additionalTools] or [additionalToolsets] transfer ownership of those
 * instances to this toolset. [close] closes every directly provided tool and toolset once by
 * reference. Callers must not provide the same owned resource through multiple containers.
 *
 * @param source Source from which skills are loaded.
 * @param additionalTools Tools hidden until an activated skill names them in
 *   `metadata.adk_additional_tools`.
 * @param additionalToolsets Toolsets that can supply hidden tools after activation.
 */
class SkillToolset
@ExperimentalSkillTools
constructor(
  internal val source: SkillSource,
  additionalTools: List<BaseTool>,
  additionalToolsets: List<Toolset>,
) : Toolset {

  @OptIn(ExperimentalSkillTools::class)
  constructor(source: SkillSource) : this(source, emptyList(), emptyList())

  /**
   * Creates a skill toolset that owns [additionalTools] and closes them when [close] is called.
   */
  @ExperimentalSkillTools
  constructor(
    source: SkillSource,
    additionalTools: List<BaseTool>,
  ) : this(source, additionalTools, emptyList())

  companion object {
    /** The name of the tool used to list available skills. */
    const val TOOL_NAME_LIST_SKILLS = "list_skills"
    /** The name of the tool used to load a skill's instructions. */
    const val TOOL_NAME_LOAD_SKILL = "load_skill"
    /** The name of the tool used to load a skill's resource file. */
    const val TOOL_NAME_LOAD_SKILL_RESOURCE = "load_skill_resource"

    /** Parameter key for the skill name. */
    const val PARAM_SKILL_NAME = "skill_name"
    /** Parameter key for the resource path used in the load_skill_resource tool. */
    const val PARAM_PATH = "path"

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

    /** Message indicating that a loaded resource is a binary file. */
    const val MSG_BINARY_FILE = "Binary file detected. Content not shown."

    /** Metadata key used by adk-python skills to declare tools activated by `load_skill`. */
    internal const val ADK_ADDITIONAL_TOOLS_METADATA_KEY = "adk_additional_tools"

    /**
     * Prefix for the per-agent state key under which the SkillToolset records which skills the LLM
     * has activated by calling `load_skill`. Mirrors adk-python's `_adk_activated_skill_` prefix.
     */
    internal const val STATE_KEY_PREFIX_ACTIVATED_SKILL = "_adk_activated_skill_"

    /** Prefix for the per-agent state containing tools enabled by activated skills. */
    internal const val STATE_KEY_PREFIX_ACTIVATED_SKILL_TOOLS =
      "_adk_activated_skill_tools_"

    /** Builds the activation-state key for the given agent name. */
    internal fun activatedSkillStateKey(agentName: String): String =
      STATE_KEY_PREFIX_ACTIVATED_SKILL + agentName

    /** Builds the additional-tool activation-state key for the given agent name. */
    internal fun activatedSkillToolsStateKey(agentName: String): String =
      STATE_KEY_PREFIX_ACTIVATED_SKILL_TOOLS + agentName
  }

  private val logger = LoggerFactory.getLogger(SkillToolset::class)

  /**
   * Additional tools that are hidden from the LLM until a skill declares them in its frontmatter's
   * `adk_additional_tools` and that skill has been loaded via `load_skill`. Keyed by tool name;
   * duplicates are dropped with a warning (last one wins).
   */
  private val providedTools = additionalTools.toList()

  internal val providedToolsByName: Map<String, BaseTool> =
    providedTools.associateBy { it.name }.also {
      providedTools.groupingBy { it.name }.eachCount().forEach { (name, count) ->
        if (count > 1) {
          logger.warn { "Duplicate additional tool name '$name'; last one wins." }
        }
      }
    }

  /**
   * Additional toolsets that can contribute tools after activation. This mirrors adk-python's
   * ability to resolve additional tools from both tools and toolsets, while keeping the Kotlin API
   * strongly typed.
   */
  private val providedToolsets: List<Toolset> = additionalToolsets.toList()

  private val tools: List<BaseTool> =
    listOf(ListSkillsTool(this), LoadSkillTool(this), LoadSkillResourceTool(this))

  private val forbiddenToolNames: Set<String>
    get() = tools.mapTo(mutableSetOf()) { it.name }

  override suspend fun getTools(readonlyContext: ReadonlyContext?): List<BaseTool> =
    tools + resolveAdditionalToolsFromState(readonlyContext)

  /** Returns whether [tool] was resolved from this skill toolset's additional tool candidates. */
  internal fun isAdditionalTool(tool: BaseTool): Boolean = tools.none { it === tool }

  /**
   * Resolves the additional tools that should be exposed for this invocation, based on which skills
   * have been activated via `load_skill`.
   *
   * Additional tool names are read from the per-skill snapshots in [ReadonlyContext.state] under
   * the agent-specific key built by [activatedSkillToolsStateKey]. Tool names with no candidate
   * match are skipped silently. Names that collide with the core skill tools are dropped with a
   * warning.
   */
  private suspend fun resolveAdditionalToolsFromState(
    readonlyContext: ReadonlyContext?,
  ): List<BaseTool> {
    if (readonlyContext == null) return emptyList()
    if (providedToolsByName.isEmpty() && providedToolsets.isEmpty()) return emptyList()

    val additionalToolNames =
      additionalToolNamesBySkill(
          readonlyContext.state[activatedSkillToolsStateKey(readonlyContext.agentName)]
        )
        .values
        .flatten()
        .distinct()
    if (additionalToolNames.isEmpty()) return emptyList()

    val candidateTools = getCandidateTools(readonlyContext)
    val resolved = mutableListOf<BaseTool>()
    for (toolName in additionalToolNames) {
      if (toolName in forbiddenToolNames) {
        logger.warn {
          "Tool name collision: additional tool '$toolName' shadows a core skill tool; skipping."
        }
        continue
      }
      candidateTools[toolName]?.let { resolved += it }
    }
    return resolved
  }

  private suspend fun getCandidateTools(readonlyContext: ReadonlyContext): Map<String, BaseTool> =
    providedToolsByName +
      providedToolsets.flatMap { it.getTools(readonlyContext) }.associateBy { it.name }

  override fun close() {
    (tools + providedTools).forEachDistinctReference { it.close() }
    providedToolsets.forEachDistinctReference { it.close() }
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
  suspend fun getSkillCatalogInstruction(): String? {
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
3. The `load_skill_resource` tool is for viewing files within a skill's directory (e.g., `references/*`, `assets/*`, `scripts/*`). Do NOT use other tools to access these files.

$skillsXml
"""
      .trimIndent()
  }
}
