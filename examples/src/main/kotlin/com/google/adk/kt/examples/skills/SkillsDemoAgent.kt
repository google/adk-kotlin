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
import com.google.adk.kt.models.VertexCredentials
import com.google.adk.kt.skills.NewFileSystemSource
import com.google.adk.kt.tools.SkillToolset
import java.net.JarURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

/** Name of the bundled skills directory under `src/main/resources`. */
private const val SKILLS_RESOURCE_DIR = "skills"

/**
 * Example "Wizard's Apprentice" agent demonstrating the Skills workflow.
 *
 * A [SkillToolset] exposes the skills under `src/main/resources/skills` (each a `SKILL.md` plus
 * optional assets) as the `list_skills`, `load_skill`, and `load_skill_resource` tools. Skills are
 * loaded from the runtime classpath, so the example works from the Dev UI, Gradle, an IDE, or a
 * packaged JAR.
 */
object SkillsDemoAgent {
  /** The agent, equipped with a [SkillToolset] that loads "spells" from the skills directory. */
  @JvmField
  val rootAgent =
    LlmAgent(
      name = "wizard_apprentice",
      model =
        Gemini(
          name = "gemini-3.8-flash",
          vertexCredentials = VertexCredentials(location = "global"),
        ),
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
      // Give the agent tools to inspect and load "skills".
      toolsets = listOf(SkillToolset(NewFileSystemSource(resolveSkillsDir()))),
    )
}

/**
 * Resolves the bundled `skills` resources to a real directory, since [NewFileSystemSource] needs a
 * filesystem path. Resources unpacked on disk (`file:`) are used directly; those inside a JAR are
 * extracted to a temp directory.
 */
private fun resolveSkillsDir(): String {
  val resource =
    SkillsDemoAgent::class.java.classLoader?.getResource(SKILLS_RESOURCE_DIR)
      ?: error(
        "Could not find the '$SKILLS_RESOURCE_DIR' resources on the classpath. " +
          "Ensure 'src/main/resources/$SKILLS_RESOURCE_DIR' is packaged with the application."
      )
  return when (resource.protocol) {
    "file" -> Paths.get(resource.toURI()).toString()
    "jar" -> extractSkillsToTempDir(resource).toString()
    else -> error("Unsupported skills resource location: $resource")
  }
}

/**
 * Extracts every `$SKILLS_RESOURCE_DIR/...` entry from the containing JAR into a temp directory.
 */
private fun extractSkillsToTempDir(resource: URL): Path {
  val tempRoot = Files.createTempDirectory("adk-skills").also { it.toFile().deleteOnExit() }
  val jarFile = (resource.openConnection() as JarURLConnection).jarFile
  val prefix = "$SKILLS_RESOURCE_DIR/"
  jarFile
    .entries()
    .asSequence()
    .filter { it.name.startsWith(prefix) }
    .forEach { entry ->
      val target = tempRoot.resolve(entry.name)
      if (entry.isDirectory) {
        Files.createDirectories(target)
      } else {
        target.parent?.let { Files.createDirectories(it) }
        jarFile.getInputStream(entry)?.use { stream ->
          Files.copy(stream, target, StandardCopyOption.REPLACE_EXISTING)
        }
      }
      target.toFile().deleteOnExit()
    }
  return tempRoot.resolve(SKILLS_RESOURCE_DIR)
}
