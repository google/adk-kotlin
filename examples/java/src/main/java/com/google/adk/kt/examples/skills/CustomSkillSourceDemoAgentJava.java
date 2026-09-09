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
import com.google.adk.kt.interop.BaseFutureSkillSource;
import com.google.adk.kt.models.Gemini;
import com.google.adk.kt.skills.Frontmatter;
import com.google.adk.kt.skills.SkillSourceException;
import com.google.adk.kt.tools.SkillToolset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Example agent whose skills come from a custom in-memory SkillSource rather than files.
 *
 * <p>{@code InMemorySkillSource} extends {@link BaseFutureSkillSource} (the Java-interop base for
 * the suspend-based SkillSource), serving a fixed set of skills defined in code, and a {@link
 * SkillToolset} exposes them as the {@code list_skills}, {@code load_skill}, and {@code
 * load_skill_resource} tools. Compare {@link SkillsDemoAgentJava}, which loads {@code SKILL.md}
 * files.
 */
public final class CustomSkillSourceDemoAgentJava {

  public static final BaseAgent rootAgent =
      LlmAgent.builder()
          .name("field_guide")
          .model(new Gemini("gemini-3.1-flash-lite"))
          .instruction(
              """
              You are a friendly field guide who helps identify things in nature.
              When asked what you can do, list your skills. To act, load the relevant skill and
              follow its instructions, loading any resource files it points to.\
              """)
          .toolsets(new SkillToolset(new InMemorySkillSource()))
          .build();

  /**
   * A skill held in memory: its metadata, instruction body, and resources keyed by relative path.
   */
  private record InMemorySkill(
      Frontmatter frontmatter, String instructions, Map<String, byte[]> resources) {}

  /**
   * A {@link BaseFutureSkillSource} serving a fixed set of skills held in memory, keyed by name.
   *
   * <p>The data is already in memory, so each method returns an already-completed future; a real
   * source would do its I/O inside the future it returns.
   */
  private static final class InMemorySkillSource extends BaseFutureSkillSource {
    private final Map<String, InMemorySkill> skills =
        Map.of(
            "identify-bird",
            new InMemorySkill(
                Frontmatter.builder()
                    .name("identify-bird")
                    .description("Identifies a bird from a description.")
                    .build(),
                "Ask about size, color, and song, then name the most likely species. Load"
                    + " assets/checklist.txt for the fields worth gathering.",
                Map.of(
                    "assets/checklist.txt",
                    "Size\nPrimary color\nBeak shape\nSong".getBytes(StandardCharsets.UTF_8))),
            "identify-tree",
            new InMemorySkill(
                Frontmatter.builder()
                    .name("identify-tree")
                    .description("Identifies a tree from leaves and bark.")
                    .build(),
                "Ask about leaf shape and bark texture, then name the most likely species.",
                Map.of()));

    @Override
    protected CompletableFuture<List<Frontmatter>> listFrontmattersAsync() {
      return CompletableFuture.completedFuture(
          skills.values().stream().map(InMemorySkill::frontmatter).toList());
    }

    @Override
    protected CompletableFuture<Frontmatter> loadFrontmatterAsync(String skillName) {
      InMemorySkill skill = skills.get(skillName);
      return skill == null
          ? skillNotFound()
          : CompletableFuture.completedFuture(skill.frontmatter());
    }

    @Override
    protected CompletableFuture<String> loadInstructionsAsync(String skillName) {
      InMemorySkill skill = skills.get(skillName);
      return skill == null
          ? skillNotFound()
          : CompletableFuture.completedFuture(skill.instructions());
    }

    @Override
    protected CompletableFuture<List<String>> listResourcesAsync(
        String skillName, String resourceDirectoryPath) {
      InMemorySkill skill = skills.get(skillName);
      if (skill == null) {
        return skillNotFound();
      }
      String prefix = resourceDirectoryPath.replaceAll("/+$", "") + "/";
      List<String> paths =
          skill.resources().keySet().stream().filter(p -> p.startsWith(prefix)).sorted().toList();
      return CompletableFuture.completedFuture(paths);
    }

    @Override
    protected CompletableFuture<byte[]> loadResourceAsync(String skillName, String resourcePath) {
      InMemorySkill skill = skills.get(skillName);
      if (skill == null) {
        return skillNotFound();
      }
      byte[] content = skill.resources().get(resourcePath);
      return content != null
          ? CompletableFuture.completedFuture(content)
          : failed(new SkillSourceException("Resource not found in skill."));
    }

    // The requested name is model input, so the message stays generic; the model knows what it
    // asked.
    private static <T> CompletableFuture<T> skillNotFound() {
      return failed(new SkillSourceException("Skill not found."));
    }

    private static <T> CompletableFuture<T> failed(Throwable error) {
      CompletableFuture<T> future = new CompletableFuture<>();
      future.completeExceptionally(error);
      return future;
    }
  }

  private CustomSkillSourceDemoAgentJava() {}
}
