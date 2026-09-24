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

import com.google.adk.kt.annotations.AdkJavaInteropApi
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class FrontmatterTest {

  @Test
  fun init_validFields_succeeds() {
    val fm = Frontmatter(name = "valid-skill-1", description = "A valid description")

    assertThat(fm.name).isEqualTo("valid-skill-1")
    assertThat(fm.description).isEqualTo("A valid description")
  }

  @Test
  fun init_emptyName_throwsException() {
    val e =
      assertThrows(IllegalArgumentException::class.java) {
        Frontmatter(name = "", description = "A valid description")
      }

    assertThat(e).hasMessageThat().contains("name must be between 1 and 64 characters long")
  }

  @Test
  fun init_nameTooLong_throwsException() {
    val e =
      assertThrows(IllegalArgumentException::class.java) {
        Frontmatter(name = "a".repeat(65), description = "A valid description")
      }

    assertThat(e).hasMessageThat().contains("name must be between 1 and 64 characters long")
  }

  @Test
  fun init_nameStartsWithHyphen_throwsException() {
    val e =
      assertThrows(IllegalArgumentException::class.java) {
        Frontmatter(name = "-invalid", description = "A valid description")
      }

    assertThat(e).hasMessageThat().contains("name must not start or end with a hyphen")
  }

  @Test
  fun init_nameEndsWithHyphen_throwsException() {
    val e =
      assertThrows(IllegalArgumentException::class.java) {
        Frontmatter(name = "invalid-", description = "A valid description")
      }

    assertThat(e).hasMessageThat().contains("name must not start or end with a hyphen")
  }

  @Test
  fun init_nameContainsConsecutiveHyphens_throwsException() {
    val e =
      assertThrows(IllegalArgumentException::class.java) {
        Frontmatter(name = "invalid--name", description = "A valid description")
      }

    assertThat(e).hasMessageThat().contains("name must not contain consecutive hyphens")
  }

  @Test
  fun init_nameContainsUnderscore_throwsException() {
    val e =
      assertThrows(IllegalArgumentException::class.java) {
        Frontmatter(name = "invalid_name", description = "A valid description")
      }

    assertThat(e)
      .hasMessageThat()
      .contains("lowercase alphanumeric characters (a-z, 0-9) and hyphens")
  }

  @Test
  fun init_nameContainsUppercase_throwsException() {
    val e =
      assertThrows(IllegalArgumentException::class.java) {
        Frontmatter(name = "invalidName", description = "A valid description")
      }

    assertThat(e)
      .hasMessageThat()
      .contains("lowercase alphanumeric characters (a-z, 0-9) and hyphens")
  }

  @Test
  fun init_nameContainsSpecialChar_throwsException() {
    val e =
      assertThrows(IllegalArgumentException::class.java) {
        Frontmatter(name = "invalid!name", description = "A valid description")
      }

    assertThat(e)
      .hasMessageThat()
      .contains("lowercase alphanumeric characters (a-z, 0-9) and hyphens")
  }

  @Test
  fun init_emptyDescription_throwsException() {
    val e =
      assertThrows(IllegalArgumentException::class.java) {
        Frontmatter(name = "valid-name", description = "")
      }

    assertThat(e)
      .hasMessageThat()
      .contains("description must be between 1 and 1024 characters long")
  }

  @Test
  fun init_descriptionTooLong_throwsException() {
    val e =
      assertThrows(IllegalArgumentException::class.java) {
        Frontmatter(name = "valid-name", description = "a".repeat(1025))
      }

    assertThat(e)
      .hasMessageThat()
      .contains("description must be between 1 and 1024 characters long")
  }

  @Test
  fun init_compatibilityTooLong_throwsException() {
    val e =
      assertThrows(IllegalArgumentException::class.java) {
        Frontmatter(
          name = "valid-name",
          description = "valid description",
          compatibility = "a".repeat(501),
        )
      }

    assertThat(e).hasMessageThat().contains("compatibility must not exceed 500 characters")
  }

  @OptIn(AdkJavaInteropApi::class)
  @Test
  fun toBuilder_matchesCopy() {
    val frontmatter =
      Frontmatter(
        name = "my-skill",
        description = "Does things.",
        license = "Apache-2.0",
        compatibility = "Requires Python 3",
        allowedTools = "Bash Read",
        metadata = mapOf("author" to "adk"),
      )

    assertThat(frontmatter.toBuilder().build()).isEqualTo(frontmatter.copy())
    assertThat(frontmatter.toBuilder().license(null).build())
      .isEqualTo(frontmatter.copy(license = null))
  }
}
