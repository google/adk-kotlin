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

package com.google.adk.kt.skills

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Tests for the shared `SKILL.md` parsing helpers used by all filesystem-style [SkillSource]
 * implementations ([NewFileSystemSource] and `AssetSkillSource`).
 *
 * The helpers themselves are file-system agnostic, so this test exercises them with raw strings.
 */
@RunWith(JUnit4::class)
class SkillMdParsingTest {

  // ---------------------------------------------------------------------------
  // parseSkillMdContent
  // ---------------------------------------------------------------------------

  @Test
  fun parseSkillMdContent_validDocument_returnsMapAndBody() {
    val content =
      """
      ---
      name: cast-fireball
      description: Casts a fireball.
      ---
      Body text.
      """
        .trimIndent()

    val (map, body) = parseSkillMdContent(content)

    assertThat(map).containsEntry("name", "cast-fireball")
    assertThat(map).containsEntry("description", "Casts a fireball.")
    assertThat(body).isEqualTo("Body text.")
  }

  @Test
  fun parseSkillMdContent_bodyIsTrimmed() {
    val content = "---\nname: x\ndescription: y\n---\n\n\n  Body with surrounding whitespace.  \n"

    val (_, body) = parseSkillMdContent(content)

    assertThat(body).isEqualTo("Body with surrounding whitespace.")
  }

  @Test
  fun parseSkillMdContent_emptyBody_returnsEmptyString() {
    val content = "---\nname: x\ndescription: y\n---\n"

    val (_, body) = parseSkillMdContent(content)

    assertThat(body).isEmpty()
  }

  @Test
  fun parseSkillMdContent_extraFrontmatterSeparatorsInBody_arePreservedInBody() {
    // The split uses limit = 3 so a '---' inside the body must not be treated as a closing fence.
    val content = "---\nname: x\ndescription: y\n---\nBody before.\n---\nBody after.\n"

    val (_, body) = parseSkillMdContent(content)

    assertThat(body).isEqualTo("Body before.\n---\nBody after.")
  }

  @Test
  fun parseSkillMdContent_missingLeadingSeparator_throws() {
    val content = "name: x\ndescription: y\n"

    val exception =
      assertThrows(IllegalArgumentException::class.java) { parseSkillMdContent(content) }

    assertThat(exception.message).contains("must start with YAML frontmatter")
  }

  @Test
  fun parseSkillMdContent_missingClosingSeparator_throws() {
    val content = "---\nname: x\ndescription: y\n"

    val exception =
      assertThrows(IllegalArgumentException::class.java) { parseSkillMdContent(content) }

    assertThat(exception.message).contains("not properly closed")
  }

  @Test
  fun parseSkillMdContent_emptyFrontmatter_throws() {
    val content = "---\n---\nbody\n"

    val exception =
      assertThrows(IllegalArgumentException::class.java) { parseSkillMdContent(content) }

    assertThat(exception.message).contains("Frontmatter must not be empty")
  }

  @Test
  fun parseSkillMdContent_whitespaceOnlyFrontmatter_throws() {
    val content = "---\n   \n\t\n---\nbody\n"

    val exception =
      assertThrows(IllegalArgumentException::class.java) { parseSkillMdContent(content) }

    assertThat(exception.message).contains("Frontmatter must not be empty")
  }

  @Test
  fun parseSkillMdContent_invalidYaml_throws() {
    val content = "---\nname: [unterminated\n---\n"

    val exception =
      assertThrows(IllegalArgumentException::class.java) { parseSkillMdContent(content) }

    assertThat(exception.message).contains("Invalid YAML in frontmatter")
  }

  @Test
  fun parseSkillMdContent_nonMappingFrontmatter_throws() {
    // A YAML list at the root is parsed as a List, not a Map -> ClassCastException -> wrapped.
    val content = "---\n- a\n- b\n---\n"

    val exception =
      assertThrows(IllegalArgumentException::class.java) { parseSkillMdContent(content) }

    assertThat(exception.message).contains("Frontmatter must be a YAML mapping")
  }

  // ---------------------------------------------------------------------------
  // buildValidatedFrontmatter
  // ---------------------------------------------------------------------------

  @Test
  fun buildValidatedFrontmatter_minimalValidMap_returnsFrontmatter() {
    val fm =
      buildValidatedFrontmatter(
        skillName = "skill-a",
        frontmatterMap = mapOf("name" to "skill-a", "description" to "d"),
      )

    assertThat(fm.name).isEqualTo("skill-a")
    assertThat(fm.description).isEqualTo("d")
    assertThat(fm.license).isNull()
    assertThat(fm.compatibility).isNull()
    assertThat(fm.allowedTools).isNull()
    assertThat(fm.metadata).isEmpty()
  }

  @Test
  fun buildValidatedFrontmatter_allOptionalFields_arePopulated() {
    val fm =
      buildValidatedFrontmatter(
        skillName = "skill-a",
        frontmatterMap =
          mapOf(
            "name" to "skill-a",
            "description" to "d",
            "license" to "Apache-2.0",
            "compatibility" to "any",
            "allowed-tools" to "tool1,tool2",
            "metadata" to mapOf("k1" to "v1", "k2" to "v2"),
          ),
      )

    assertThat(fm.license).isEqualTo("Apache-2.0")
    assertThat(fm.compatibility).isEqualTo("any")
    assertThat(fm.allowedTools).isEqualTo("tool1,tool2")
    assertThat(fm.metadata).containsExactly("k1", "v1", "k2", "v2")
  }

  @Test
  fun buildValidatedFrontmatter_allowedToolsUnderscoreFallback_isAccepted() {
    val fm =
      buildValidatedFrontmatter(
        skillName = "skill-a",
        frontmatterMap =
          mapOf("name" to "skill-a", "description" to "d", "allowed_tools" to "tool1"),
      )

    assertThat(fm.allowedTools).isEqualTo("tool1")
  }

  @Test
  fun buildValidatedFrontmatter_hyphenAllowedToolsTakesPrecedenceOverUnderscore() {
    val fm =
      buildValidatedFrontmatter(
        skillName = "skill-a",
        frontmatterMap =
          mapOf(
            "name" to "skill-a",
            "description" to "d",
            "allowed-tools" to "preferred",
            "allowed_tools" to "ignored",
          ),
      )

    assertThat(fm.allowedTools).isEqualTo("preferred")
  }

  @Test
  fun buildValidatedFrontmatter_metadataWithNonStringEntries_areDropped() {
    val fm =
      buildValidatedFrontmatter(
        skillName = "skill-a",
        frontmatterMap =
          mapOf(
            "name" to "skill-a",
            "description" to "d",
            "metadata" to mapOf("ok" to "v", "bad" to 42, 7 to "also-bad"),
          ),
      )

    assertThat(fm.metadata).containsExactly("ok", "v")
  }

  @Test
  fun buildValidatedFrontmatter_nameMissing_throws() {
    val exception =
      assertThrows(SkillSourceException::class.java) {
        buildValidatedFrontmatter(
          skillName = "skill-a",
          frontmatterMap = mapOf("description" to "d"),
        )
      }

    assertThat(exception.message).contains("'name' field missing")
    assertThat(exception.message).contains("skill-a")
  }

  @Test
  fun buildValidatedFrontmatter_nameWrongType_isTreatedAsMissing() {
    val exception =
      assertThrows(SkillSourceException::class.java) {
        buildValidatedFrontmatter(
          skillName = "skill-a",
          frontmatterMap = mapOf("name" to 42, "description" to "d"),
        )
      }

    assertThat(exception.message).contains("'name' field missing")
  }

  @Test
  fun buildValidatedFrontmatter_descriptionMissing_throws() {
    val exception =
      assertThrows(SkillSourceException::class.java) {
        buildValidatedFrontmatter(
          skillName = "skill-a",
          frontmatterMap = mapOf("name" to "skill-a"),
        )
      }

    assertThat(exception.message).contains("'description' field missing")
  }

  @Test
  fun buildValidatedFrontmatter_nameMismatch_throws() {
    val exception =
      assertThrows(SkillSourceException::class.java) {
        buildValidatedFrontmatter(
          skillName = "skill-a",
          frontmatterMap = mapOf("name" to "skill-b", "description" to "d"),
        )
      }

    assertThat(exception.message).contains("does not match directory name")
    assertThat(exception.message).contains("skill-a")
    assertThat(exception.message).contains("skill-b")
  }

  @Test
  fun buildValidatedFrontmatter_nameViolatesFrontmatterRules_wrapsValidationError() {
    // Underscores in `name` are rejected by `Frontmatter.init`; the helper rewraps as
    // SkillSourceException tagged with the skill name.
    val exception =
      assertThrows(SkillSourceException::class.java) {
        buildValidatedFrontmatter(
          skillName = "bad_name",
          frontmatterMap = mapOf("name" to "bad_name", "description" to "d"),
        )
      }

    assertThat(exception.message).contains("bad_name is malformed")
    assertThat(exception.message).contains("lowercase alphanumeric")
  }

  // ---------------------------------------------------------------------------
  // parseSkillMd (end-to-end helper)
  // ---------------------------------------------------------------------------

  @Test
  fun parseSkillMd_validDocument_returnsFrontmatterAndBody() {
    val content =
      """
      ---
      name: skill-x
      description: desc
      ---
      Hello body.
      """
        .trimIndent()

    val (fm, body) = parseSkillMd(skillName = "skill-x", content = content)

    assertThat(fm.name).isEqualTo("skill-x")
    assertThat(fm.description).isEqualTo("desc")
    assertThat(body).isEqualTo("Hello body.")
  }

  @Test
  fun parseSkillMd_malformedFrontmatter_isWrappedAsSkillSourceException() {
    val content = "no frontmatter here"

    val exception =
      assertThrows(SkillSourceException::class.java) {
        parseSkillMd(skillName = "skill-x", content = content)
      }

    assertThat(exception.message).contains("Skill skill-x is malformed: invalid frontmatter")
    assertThat(exception.message).contains("must start with YAML frontmatter")
    // The underlying IllegalArgumentException is preserved as the cause.
    assertThat(exception.cause).isInstanceOf(IllegalArgumentException::class.java)
  }

  @Test
  fun parseSkillMd_nameMismatch_returnsSkillTaggedFailure() {
    val content = "---\nname: other\ndescription: d\n---\n"

    val exception =
      assertThrows(SkillSourceException::class.java) {
        parseSkillMd(skillName = "skill-x", content = content)
      }

    assertThat(exception.message).contains("does not match directory name")
  }

  // ---------------------------------------------------------------------------
  // sourceRunCatching
  // ---------------------------------------------------------------------------

  @Test
  fun sourceRunCatching_successfulBlock_returnsSuccess() {
    val result: Result<Int> = sourceRunCatching { 42 }

    assertThat(result.isSuccess).isTrue()
    assertThat(result.getOrThrow()).isEqualTo(42)
  }

  @Test
  fun sourceRunCatching_skillSourceException_isWrappedAsFailure() {
    val thrown = SkillSourceException("boom")

    val result: Result<Int> = sourceRunCatching { throw thrown }

    assertThat(result.isFailure).isTrue()
    assertThat(result.exceptionOrNull()).isSameInstanceAs(thrown)
  }

  @Test
  fun sourceRunCatching_otherException_isRethrown() {
    val thrown =
      assertThrows(IllegalStateException::class.java) {
        sourceRunCatching<Int> { throw IllegalStateException("not a skill error") }
      }

    assertThat(thrown.message).isEqualTo("not a skill error")
  }
}
