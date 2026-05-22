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
import com.google.adk.kt.skills.NewFileSystemSource
import com.google.adk.kt.tools.SkillToolset
import java.nio.file.Files
import java.nio.file.Path

/**
 * Example Wizard's Apprentice agent demonstrating the Skills workflow using the Kotlin ADK.
 *
 * This example showcases how to use `SkillToolset` to provide an agent with dynamically loaded
 * capabilities defined in SKILL.md files and accompanying scripts.
 *
 * How it works:
 * 1. The agent is configured with a `SkillToolset` pointing to a directory of skill folders.
 * 2. The `SkillToolset` dynamically adds tools like `list_skills`, `load_skill`, and
 *    `run_skill_script` to the agent.
 * 3. The agent is instructed to explore these tools to understand its capabilities (its "spells").
 */
object SkillsDemoAgent {
  /**
   * Defines the central [LlmAgent] that will be run.
   *
   * The agent uses a [GeminiModel] behind the scenes and is dynamically equipped with a
   * [SkillToolset] that loads magical "spells" from the filesystem.
   */
  @JvmField
  val rootAgent =
    LlmAgent(
      name = "wizard_apprentice",
      model = Gemini(name = "gemini-3.1-flash-lite"),
      // The `instruction` determines the agent's persona.
      instruction =
        Instruction(
          """
          You are a young, somewhat nerdy wizard's apprentice.
          You have a grimoire of spells (skills) that you can cast to help the user.
          When the user asks you to do something, you should see if you have a spell
          that can help. Be helpful, a bit clumsy perhaps, but eager to please!
          Speak like a fantasy novel character.
          """
            .trimIndent()
        ),
      // Give the agent tools to inspect and execute "skills".
      // Note: We resolve the skills directory relative to the bazel runfiles.
      // This path is specific to the example structure.
      toolsets = listOf(SkillToolset(NewFileSystemSource(getSkillsDir()))),
    )

  private fun getSkillsDir(): String {
    // Derive the skills directory from this class's code source so the path
    // does not need to be hard-coded. The resourcessit alongside the compiled
    // output following the standard module layout
    // (<module>/src/main/resources/skills next to <module>/<artifact>).
    val codeSource =
      SkillsDemoAgent::class.java.protectionDomain.codeSource
        ?: error("Unable to determine code source location for SkillsDemoAgent")
    val codeLocation = Path.of(codeSource.location.toURI())
    val moduleRoot = if (Files.isDirectory(codeLocation)) codeLocation else codeLocation.parent
    val skillsDir = moduleRoot.resolve("src/main/resources/skills")
    check(Files.isDirectory(skillsDir)) {
      "Skills directory not found at $skillsDir (derived from $codeLocation)"
    }
    return skillsDir.toAbsolutePath().toString()
  }
}
