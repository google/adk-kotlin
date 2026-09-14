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
 * Example translator agent demonstrating skills that ship a script.
 *
 * The bundled `translate` skill does not describe how to translate; it provides
 * `scripts/translate.py`, which looks phrases up in `references/phrasebook.tsv`. Because the
 * [SkillToolset] is given a [LocalEnvironment], it also exposes the `run_skill_script` tool: the
 * skill's files are copied into the environment and the script runs there, so it can read the
 * phrasebook next to it.
 *
 * The script is Python rather than shell to show that a skill is not limited to one language:
 * scripts are executed directly, so the shebang line picks the interpreter.
 *
 * The script takes a phrase followed by one argument per target language, which shows how a list of
 * arguments is passed through `run_skill_script`. Ask for something like "how do I say good morning
 * in French and Japanese?" to see a single call translate into both.
 *
 * The skill runs in a temporary workspace, which [main] has the runner remove on exit.
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

/**
 * Runs the agent in a REPL, closing the runner on exit.
 *
 * Closing a [com.google.adk.kt.runners.Runner] closes the toolsets reachable from its agent, which
 * is what lets [SkillToolset] discard the temporary workspace its skills ran in. The generated
 * debug runner does not close anything, so this example provides its own entry point.
 */
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
