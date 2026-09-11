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

package com.google.adk.kt.cli.builtinagents

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import kotlin.test.assertFailsWith
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * The path handling every assistant tool goes through. `resolveInProject` is the only thing
 * standing between a path the model made up and the user's filesystem, so its refusals are the
 * cases worth pinning.
 */
@RunWith(JUnit4::class)
class ProjectFilesTest {

  private lateinit var root: File
  private lateinit var state: Map<String, Any>

  @Before
  fun setUp() {
    root = Files.createTempDirectory("project_files_test").toFile().canonicalFile
    state = mapOf(ROOT_DIRECTORY_KEY to root.path)
  }

  @After
  fun tearDown() {
    root.deleteRecursively()
  }

  @Test
  fun sanitizePath_quotedPath_hasTheQuotesStripped() {
    assertThat(sanitizePath("'tools/my_tool.kt'")).isEqualTo("tools/my_tool.kt")
    assertThat(sanitizePath("\"tools/my_tool.kt\"")).isEqualTo("tools/my_tool.kt")
  }

  @Test
  fun sanitizePath_quotesAroundEachSegment_areStripped() {
    assertThat(sanitizePath("'tools'/'my_tool.kt'")).isEqualTo("tools/my_tool.kt")
  }

  @Test
  fun sanitizePath_quoteInsideASegment_isLeftAlone() {
    assertThat(sanitizePath("tools/it's.kt")).isEqualTo("tools/it's.kt")
  }

  @Test
  fun sanitizePath_blank_isEmpty() {
    assertThat(sanitizePath("   ")).isEmpty()
  }

  @Test
  fun sanitizePath_nothingButBoundaryCharacters_fallsBackToWhatWasGiven() {
    // Stripping everything would turn a path into the project root, which is not what was asked
    // for; handing the original back lets resolveInProject refuse it instead.
    assertThat(sanitizePath("''")).isEqualTo("''")
  }

  @Test
  fun projectRoot_stateWithoutTheKey_isTheWorkingDirectory() {
    assertThat(projectRoot(emptyMap())).isEqualTo(File(".").canonicalFile)
  }

  @Test
  fun projectRoot_paddedValue_isTrimmed() {
    assertThat(projectRoot(mapOf(ROOT_DIRECTORY_KEY to "  ${root.path}  "))).isEqualTo(root)
  }

  @Test
  fun resolveInProject_relativePath_resolvesUnderTheProject() {
    assertThat(resolveInProject("tools/my_tool.kt", state))
      .isEqualTo(File(root, "tools/my_tool.kt"))
  }

  @Test
  fun resolveInProject_quotedPath_resolvesToTheUnquotedFile() {
    assertThat(resolveInProject("'tools/my_tool.kt'", state))
      .isEqualTo(File(root, "tools/my_tool.kt"))
  }

  @Test
  fun resolveInProject_theProjectItself_isAllowed() {
    assertThat(resolveInProject(".", state)).isEqualTo(root)
  }

  @Test
  fun resolveInProject_absolutePathInsideTheProject_isAllowed() {
    val inside = File(root, "notes.md")
    assertThat(resolveInProject(inside.path, state)).isEqualTo(inside)
  }

  @Test
  fun resolveInProject_parentEscape_isRefused() {
    val failure =
      assertFailsWith<IllegalArgumentException> { resolveInProject("../outside.txt", state) }
    assertThat(failure).hasMessageThat().contains("outside the project directory")
  }

  @Test
  fun resolveInProject_parentEscapeBuriedInThePath_isRefused() {
    assertFailsWith<IllegalArgumentException> { resolveInProject("tools/../../outside.txt", state) }
  }

  @Test
  fun resolveInProject_absolutePathElsewhere_isRefused() {
    assertFailsWith<IllegalArgumentException> { resolveInProject("/etc/passwd", state) }
  }

  @Test
  fun resolveInProject_siblingDirectoryWhoseNameStartsWithTheProjectName_isRefused() {
    // The guard compares against the root plus a separator; a plain prefix test would let
    // `<root>-evil` through.
    File(root.parentFile, "${root.name}-evil").mkdirs()
    assertFailsWith<IllegalArgumentException> {
      resolveInProject("../${root.name}-evil/f.txt", state)
    }
    File(root.parentFile, "${root.name}-evil").deleteRecursively()
  }

  @Test
  fun globRegex_star_matchesAnyRunOfCharacters() {
    assertThat(globRegex("*.kt").matches("MyTool.kt")).isTrue()
    assertThat(globRegex("test_*.py").matches("test_agent.py")).isTrue()
  }

  @Test
  fun globRegex_dotIsLiteral() {
    assertThat(globRegex("*.kt").matches("MyToolxkt")).isFalse()
  }

  @Test
  fun globRegex_matchesTheWholeName() {
    assertThat(globRegex("*.kt").matches("MyTool.kt.bak")).isFalse()
  }

  @Test
  fun globRegex_questionMark_matchesOneCharacter() {
    assertThat(globRegex("v?.kt").matches("v1.kt")).isTrue()
    assertThat(globRegex("v?.kt").matches("v10.kt")).isFalse()
  }

  @Test
  fun stringList_absentOrWrongType_isEmpty() {
    assertThat(emptyMap<String, Any?>().stringList("paths")).isEmpty()
    assertThat(mapOf<String, Any?>("paths" to "a.kt").stringList("paths")).isEmpty()
  }

  @Test
  fun stringList_coercesEntriesAndDropsNulls() {
    val sent = mapOf<String, Any?>("paths" to listOf("a.kt", null, 7))
    assertThat(sent.stringList("paths")).containsExactly("a.kt", "7").inOrder()
  }

  @Test
  fun flag_readsBooleansAndTheirStrictStringSpellings() {
    assertThat(mapOf<String, Any?>("f" to false).flag("f", default = true)).isFalse()
    assertThat(mapOf<String, Any?>("f" to "false").flag("f", default = true)).isFalse()
    assertThat(mapOf<String, Any?>("f" to "true").flag("f", default = false)).isTrue()
  }

  @Test
  fun flag_absentOrUnreadable_isTheDefault() {
    assertThat(emptyMap<String, Any?>().flag("f", default = true)).isTrue()
    assertThat(mapOf<String, Any?>("f" to "TRUE").flag("f", default = false)).isFalse()
    assertThat(mapOf<String, Any?>("f" to 1).flag("f", default = false)).isFalse()
  }
}
