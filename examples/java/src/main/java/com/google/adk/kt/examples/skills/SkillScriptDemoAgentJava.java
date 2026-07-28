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

package com.google.adk.kt.examples.skills;

import com.google.adk.kt.agents.BaseAgent;
import com.google.adk.kt.agents.LlmAgent;
import com.google.adk.kt.environment.LocalEnvironment;
import com.google.adk.kt.models.Gemini;
import com.google.adk.kt.runners.ReplRunner;
import com.google.adk.kt.skills.NewFileSystemSource;
import com.google.adk.kt.tools.SkillToolset;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Example translator agent whose {@code translate} skill runs a Python script over a bundled
 * phrasebook.
 *
 * <p>Because its {@link SkillToolset} has a {@link LocalEnvironment}, the model can call {@code
 * run_skill_script}, which copies the skill into a temporary workspace and runs the command there.
 * Try "how do I say good morning in French and Japanese?".
 */
public final class SkillScriptDemoAgentJava {

  /** Name of the bundled skills directory under {@code src/main/resources}. */
  private static final String TRANSLATION_SKILLS_RESOURCE_DIR = "translation_skills";

  public static final BaseAgent rootAgent =
      LlmAgent.builder()
          .name("translator")
          .model(new Gemini("gemini-3.1-flash-lite"))
          .instruction(
              """
              You are a concise translator.
              When the user asks for a translation, use your translation skill rather than
              translating from memory, and report exactly what it returns.
              If the skill has no translation for a phrase or language, say so plainly.\
              """)
          .toolsets(
              SkillToolset.builder()
                  .source(new NewFileSystemSource(resolveTranslationSkillsDir()))
                  .environment(new LocalEnvironment())
                  .build())
          .build();

  /** Runs the agent in a REPL; closing the runner closes the toolset, deleting the workspace. */
  public static void main(String[] args) {
    try (ReplRunner runner = new ReplRunner(rootAgent)) {
      runner.start();
    }
  }

  /**
   * Resolves the bundled skills resources to a real directory, since {@link NewFileSystemSource}
   * needs a filesystem path. Resources unpacked on disk ({@code file:}) are used directly; those
   * inside a JAR are extracted to a temp directory.
   */
  private static String resolveTranslationSkillsDir() {
    URL resource =
        SkillScriptDemoAgentJava.class
            .getClassLoader()
            .getResource(TRANSLATION_SKILLS_RESOURCE_DIR);
    if (resource == null) {
      throw new IllegalStateException(
          "Could not find the '"
              + TRANSLATION_SKILLS_RESOURCE_DIR
              + "' resources on the classpath. Ensure 'src/main/resources/"
              + TRANSLATION_SKILLS_RESOURCE_DIR
              + "' is packaged with the application.");
    }
    return switch (resource.getProtocol()) {
      case "file" -> {
        try {
          yield Paths.get(resource.toURI()).toString();
        } catch (URISyntaxException e) {
          throw new IllegalStateException("Invalid skills resource URI: " + resource, e);
        }
      }
      case "jar" -> extractTranslationSkillsToTempDir(resource).toString();
      default ->
          throw new IllegalStateException("Unsupported skills resource location: " + resource);
    };
  }

  /** Extracts every {@code translation_skills/...} JAR entry into a temp directory. */
  private static Path extractTranslationSkillsToTempDir(URL resource) {
    try {
      Path tempRoot = Files.createTempDirectory("adk-translation-skills");
      tempRoot.toFile().deleteOnExit();
      String prefix = TRANSLATION_SKILLS_RESOURCE_DIR + "/";
      JarFile jarFile = ((JarURLConnection) resource.openConnection()).getJarFile();
      Enumeration<JarEntry> entries = jarFile.entries();
      while (entries.hasMoreElements()) {
        JarEntry entry = entries.nextElement();
        if (!entry.getName().startsWith(prefix)) {
          continue;
        }
        Path target = tempRoot.resolve(entry.getName());
        if (entry.isDirectory()) {
          Files.createDirectories(target);
        } else {
          Path parent = target.getParent();
          if (parent != null) {
            Files.createDirectories(parent);
          }
          try (InputStream stream = jarFile.getInputStream(entry)) {
            Files.copy(stream, target, StandardCopyOption.REPLACE_EXISTING);
          }
        }
        target.toFile().deleteOnExit();
      }
      return tempRoot.resolve(TRANSLATION_SKILLS_RESOURCE_DIR);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to extract bundled skills from " + resource, e);
    }
  }

  private SkillScriptDemoAgentJava() {}
}
