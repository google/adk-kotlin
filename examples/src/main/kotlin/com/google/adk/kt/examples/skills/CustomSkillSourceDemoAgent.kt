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

package com.google.adk.kt.examples.skills

import com.google.adk.kt.agents.Instruction
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.models.Gemini
import com.google.adk.kt.skills.Frontmatter
import com.google.adk.kt.skills.SkillSource
import com.google.adk.kt.skills.SkillSourceException
import com.google.adk.kt.tools.SkillToolset

/**
 * Example agent whose skills come from a custom in-memory [SkillSource] rather than files.
 *
 * [InMemorySkillSource] implements [SkillSource] directly, serving a fixed set of skills defined in
 * code, and a [SkillToolset] exposes them as the `list_skills`, `load_skill`, and
 * `load_skill_resource` tools. This is the pattern for plugging a bespoke skill backend (a
 * database, a service, generated content) into an agent; [SkillsDemoAgent] instead loads `SKILL.md`
 * files.
 */
object CustomSkillSourceDemoAgent {
  @JvmField
  val rootAgent =
    LlmAgent(
      name = "field_guide",
      model = Gemini(name = "gemini-3.1-flash-lite"),
      instruction =
        Instruction(
          """
          You are a friendly field guide who helps identify things in nature.
          When asked what you can do, list your skills. To act, load the relevant skill and
          follow its instructions, loading any resource files it points to.
          """
            .trimIndent()
        ),
      toolsets = listOf(SkillToolset(InMemorySkillSource(FIELD_GUIDE_SKILLS))),
    )
}

/**
 * A single in-memory skill: its metadata, instruction body, and resources keyed by relative path.
 */
private class InMemorySkill(
  val frontmatter: Frontmatter,
  val instructions: String,
  val resources: Map<String, ByteArray> = emptyMap(),
)

/** A [SkillSource] that serves a fixed set of [skills] held in memory, keyed by skill name. */
private class InMemorySkillSource(private val skills: Map<String, InMemorySkill>) : SkillSource {
  override suspend fun listFrontmatters(): Result<List<Frontmatter>> =
    Result.success(skills.values.map { it.frontmatter })

  override suspend fun loadFrontmatter(skillName: String): Result<Frontmatter> =
    skills[skillName]?.let { Result.success(it.frontmatter) } ?: skillNotFound()

  override suspend fun loadInstructions(skillName: String): Result<String> =
    skills[skillName]?.let { Result.success(it.instructions) } ?: skillNotFound()

  override suspend fun listResources(
    skillName: String,
    resourceDirectoryPath: String,
  ): Result<List<String>> {
    val skill = skills[skillName] ?: return skillNotFound()
    val prefix = resourceDirectoryPath.trimEnd('/') + "/"
    return Result.success(skill.resources.keys.filter { it.startsWith(prefix) }.sorted())
  }

  override suspend fun loadResource(skillName: String, resourcePath: String): Result<ByteArray> {
    val skill = skills[skillName] ?: return skillNotFound()
    return skill.resources[resourcePath]?.let { Result.success(it) }
      ?: Result.failure(SkillSourceException("Resource not found in skill."))
  }

  // The requested name is model input, so the message stays generic; the model knows what it asked.
  private fun <T> skillNotFound(): Result<T> =
    Result.failure(SkillSourceException("Skill not found."))
}

private val FIELD_GUIDE_SKILLS: Map<String, InMemorySkill> =
  listOf(
      InMemorySkill(
        frontmatter =
          Frontmatter(
            name = "identify-bird",
            description = "Identifies a bird from a description.",
          ),
        instructions =
          "Ask about size, color, and song, then name the most likely species. Load " +
            "assets/checklist.txt for the fields worth gathering.",
        resources =
          mapOf(
            "assets/checklist.txt" to "Size\nPrimary color\nBeak shape\nSong".encodeToByteArray()
          ),
      ),
      InMemorySkill(
        frontmatter =
          Frontmatter(
            name = "identify-tree",
            description = "Identifies a tree from leaves and bark.",
          ),
        instructions = "Ask about leaf shape and bark texture, then name the most likely species.",
      ),
    )
    .associateBy { it.frontmatter.name }
