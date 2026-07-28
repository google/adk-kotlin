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
import com.google.adk.kt.models.Gemini;
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
 * Example "Wizard's Apprentice" agent demonstrating the Skills workflow.
 *
 * <p>A {@link SkillToolset} exposes the skills under {@code src/main/resources/skills} as the
 * {@code list_skills}, {@code load_skill}, and {@code load_skill_resource} tools.
 */
public final class SkillsDemoAgentJava {

  /** Name of the bundled skills directory under {@code src/main/resources}. */
  private static final String SKILLS_RESOURCE_DIR = "skills";

  public static final BaseAgent rootAgent =
      LlmAgent.builder()
          .name("wizard_apprentice")
          .model(new Gemini("gemini-3.1-flash-lite"))
          // The instruction determines the agent's persona.
          .instruction(
              """
              You are a young, somewhat nerdy wizard's apprentice.
              You have a grimoire of spells (skills) that you can cast to help the user.
              When the user asks you to do something, you should see if you have a spell
              that can help. Be helpful, a bit clumsy perhaps, but eager to please!
              Speak like a fantasy novel character.\
              """)
          // Give the agent tools to inspect and load "skills".
          .toolsets(new SkillToolset(new NewFileSystemSource(resolveSkillsDir())))
          .build();

  /**
   * Resolves the bundled {@code skills} resources to a real directory, since {@link
   * NewFileSystemSource} needs a filesystem path. Resources unpacked on disk ({@code file:}) are
   * used directly; those inside a JAR are extracted to a temp directory.
   */
  private static String resolveSkillsDir() {
    URL resource = SkillsDemoAgentJava.class.getClassLoader().getResource(SKILLS_RESOURCE_DIR);
    if (resource == null) {
      throw new IllegalStateException(
          "Could not find the '"
              + SKILLS_RESOURCE_DIR
              + "' resources on the classpath. Ensure 'src/main/resources/"
              + SKILLS_RESOURCE_DIR
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
      case "jar" -> extractSkillsToTempDir(resource).toString();
      default ->
          throw new IllegalStateException("Unsupported skills resource location: " + resource);
    };
  }

  /** Extracts every {@code skills/...} entry from the containing JAR into a temp directory. */
  private static Path extractSkillsToTempDir(URL resource) {
    try {
      Path tempRoot = Files.createTempDirectory("adk-skills");
      tempRoot.toFile().deleteOnExit();
      String prefix = SKILLS_RESOURCE_DIR + "/";
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
      return tempRoot.resolve(SKILLS_RESOURCE_DIR);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to extract bundled skills from " + resource, e);
    }
  }

  private SkillsDemoAgentJava() {}
}
