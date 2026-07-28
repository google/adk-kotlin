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
import com.google.adk.kt.annotations.ExperimentalEnvironmentApi
import com.google.adk.kt.environment.LocalEnvironment
import com.google.adk.kt.models.Gemini
import com.google.adk.kt.runners.ReplRunner
import com.google.adk.kt.skills.NewFileSystemSource
import com.google.adk.kt.tools.SkillToolset
import java.net.JarURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

/** Name of the bundled skills directory under `src/main/resources`. */
private const val TRANSLATION_SKILLS_RESOURCE_DIR = "translation_skills"

/**
 * Example translator agent whose `translate` skill runs a Python script over a bundled phrasebook.
 * Because its [SkillToolset] has a [LocalEnvironment], the model can call `run_skill_script`, which
 * copies the skill into a temporary workspace and runs the command there. Try "how do I say good
 * morning in French and Japanese?".
 */
object SkillScriptDemoAgent {
  /** The agent, equipped with a [SkillToolset] that can run its skills' scripts. */
  @JvmField
  @OptIn(ExperimentalEnvironmentApi::class)
  val rootAgent =
    LlmAgent(
      name = "translator",
      model = Gemini(name = "gemini-3.1-flash-lite"),
      instruction =
        Instruction(
          """
          You are a concise translator.
          When the user asks for a translation, use your translation skill rather than
          translating from memory, and report exactly what it returns.
          If the skill has no translation for a phrase or language, say so plainly.
          """
            .trimIndent()
        ),
      toolsets =
        listOf(
          SkillToolset(
            source = NewFileSystemSource(resolveTranslationSkillsDir()),
            environment = LocalEnvironment(),
          )
        ),
    )
}

/** Runs the agent in a REPL; closing the runner closes the toolset, deleting the workspace. */
fun main() {
  ReplRunner(SkillScriptDemoAgent.rootAgent).use { it.start() }
}

/**
 * Resolves the bundled skills resources to a real directory, since [NewFileSystemSource] needs a
 * filesystem path. Resources unpacked on disk (`file:`) are used directly; those inside a JAR are
 * extracted to a temp directory.
 */
private fun resolveTranslationSkillsDir(): String {
  val resource =
    SkillScriptDemoAgent::class.java.classLoader?.getResource(TRANSLATION_SKILLS_RESOURCE_DIR)
      ?: error(
        "Could not find the '$TRANSLATION_SKILLS_RESOURCE_DIR' resources on the classpath. " +
          "Ensure 'src/main/resources/$TRANSLATION_SKILLS_RESOURCE_DIR' is packaged with the application."
      )
  return when (resource.protocol) {
    "file" -> Paths.get(resource.toURI()).toString()
    "jar" -> extractTranslationSkillsToTempDir(resource).toString()
    else -> error("Unsupported skills resource location: $resource")
  }
}

/** Extracts every `$TRANSLATION_SKILLS_RESOURCE_DIR/...` JAR entry into a temp directory. */
private fun extractTranslationSkillsToTempDir(resource: URL): Path {
  val tempRoot =
    Files.createTempDirectory("adk-translation-skills").also { it.toFile().deleteOnExit() }
  val jarFile = (resource.openConnection() as JarURLConnection).jarFile
  val prefix = "$TRANSLATION_SKILLS_RESOURCE_DIR/"
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
  return tempRoot.resolve(TRANSLATION_SKILLS_RESOURCE_DIR)
}
