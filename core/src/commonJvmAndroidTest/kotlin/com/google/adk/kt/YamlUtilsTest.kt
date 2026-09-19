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

package com.google.adk.kt

import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Part
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files
import kotlin.test.assertFailsWith
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class YamlUtilsTest {

  private lateinit var tempDir: File

  @Before
  fun setUp() {
    tempDir = Files.createTempDirectory("yaml_utils_test").toFile()
  }

  @After
  fun tearDown() {
    tempDir.deleteRecursively()
  }

  @Test
  fun loadYamlFile_mapping_readsScalarsAsOrdinaryKotlinValues() {
    val file = write("agent.yaml", "name: helper\nenabled: true\nretries: 3\nratio: 0.5\n")

    val document = YamlUtils.loadYamlFile(file.path) as Map<*, *>

    assertThat(document["name"]).isEqualTo("helper")
    assertThat(document["enabled"]).isEqualTo(true)
    assertThat(document["retries"]).isEqualTo(3)
    assertThat(document["ratio"]).isEqualTo(0.5)
  }

  @Test
  fun loadYamlFile_sequence_readsAsAList() {
    val file = write("tools.yaml", "tools:\n  - name: search\n  - name: fetch\n")

    val document = YamlUtils.loadYamlFile(file.path) as Map<*, *>

    assertThat(document["tools"])
      .isEqualTo(listOf(mapOf("name" to "search"), mapOf("name" to "fetch")))
  }

  @Test
  fun loadYamlFile_emptyDocument_isNull() {
    assertThat(YamlUtils.loadYamlFile(write("empty.yaml", "").path)).isNull()
  }

  @Test
  fun loadYamlFile_missingFile_throwsWithThePathInTheMessage() {
    val missing = File(tempDir, "not-there.yaml").path

    val failure = assertFailsWith<FileNotFoundException> { YamlUtils.loadYamlFile(missing) }

    assertThat(failure).hasMessageThat().contains(missing)
  }

  @Test
  fun loadYamlFile_directory_isTreatedAsMissing() {
    assertFailsWith<FileNotFoundException> { YamlUtils.loadYamlFile(tempDir.path) }
  }

  @Test
  fun loadYamlFile_tagNamingATypeToBuild_isRefused() {
    // A config file is picked up from disk, so it is on the far side of a trust boundary and a tag
    // asking for a type to be constructed has to be refused rather than obeyed.
    val file = write("tagged.yaml", "payload: !!java.io.File [/etc/passwd]\n")

    assertFailsWith<RuntimeException> { YamlUtils.loadYamlFile(file.path) }
  }

  @Test
  fun dumpToYaml_omitsFieldsLeftAtTheirDefault() {
    val file = File(tempDir, "content.yaml")

    YamlUtils.dumpToYaml(Content(parts = listOf(Part(text = "hello"))), file.path)

    val written = file.readText()
    assertThat(written).contains("hello")
    assertThat(written).doesNotContain("role")
    assertThat(written).doesNotContain("inlineData")
    assertThat(written).doesNotContain("functionCall")
  }

  @Test
  fun dumpToYaml_keepsAFieldSetAwayFromItsDefaultEvenWhenFalse() {
    // "not the default" and "falsy" are different questions; `thought` defaults to absent.
    val file = File(tempDir, "thought.yaml")

    YamlUtils.dumpToYaml(Content(parts = listOf(Part(text = "x", thought = false))), file.path)

    assertThat(file.readText()).contains("thought: false")
  }

  @Test
  fun dumpToYaml_sortsKeysAlphabetically() {
    val file = File(tempDir, "sorted.yaml")

    YamlUtils.dumpToYaml(Content(role = "user", parts = listOf(Part(text = "x"))), file.path)

    val written = file.readText()
    assertThat(written.indexOf("parts:")).isLessThan(written.indexOf("role:"))
  }

  @Test
  fun dumpToYaml_multilineString_staysMultiline() {
    val file = File(tempDir, "instruction.yaml")

    YamlUtils.dumpToYaml(Content(parts = listOf(Part(text = "line one\nline two"))), file.path)

    val written = file.readText()
    // A quoted scalar would escape the newline into `\n` and put both lines on one line.
    assertThat(written).doesNotContain("\\n")
    assertThat(written.lines().map { it.trim() }).containsAtLeast("line one", "line two").inOrder()
  }

  @Test
  fun dumpToYaml_createsMissingParentDirectories() {
    val file = File(tempDir, "nested/deeper/content.yaml")

    YamlUtils.dumpToYaml(Content(parts = listOf(Part(text = "x"))), file.path)

    assertThat(file.isFile).isTrue()
  }

  @Test
  fun dumpToYaml_thenLoadYamlFile_roundTripsTheDocument() {
    val file = File(tempDir, "round-trip.yaml")

    YamlUtils.dumpToYaml(
      Content(role = "user", parts = listOf(Part(text = "line one\nline two"))),
      file.path,
    )

    assertThat(YamlUtils.loadYamlFile(file.path))
      .isEqualTo(mapOf("parts" to listOf(mapOf("text" to "line one\nline two")), "role" to "user"))
  }

  private fun write(name: String, body: String): File =
    File(tempDir, name).apply { writeText(body) }
}
