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

package com.google.adk.kt.cli.commands.deploy

import com.google.adk.kt.cli.commands.deploy.CloudRunDeployCommand.Companion.buildGcloudDeployArgs
import com.google.adk.kt.cli.commands.deploy.CloudRunDeployCommand.Companion.buildGcpEnvBlock
import com.google.adk.kt.cli.commands.deploy.CloudRunDeployCommand.Companion.invalidAppEnv
import com.google.adk.kt.cli.commands.deploy.CloudRunDeployCommand.Companion.offendingGcloudExtras
import com.google.adk.kt.cli.commands.deploy.CloudRunDeployCommand.Companion.renderDockerfile
import com.google.adk.kt.cli.commands.deploy.CloudRunDeployCommand.Companion.resolveEntryScript
import com.google.adk.kt.cli.commands.deploy.CloudRunDeployCommand.Companion.resolveInstallImage
import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Unit tests for the pure helpers extracted from [CloudRunDeployCommand].
 *
 * No Clikt, no subprocess, no `run()` orchestration. Each test exercises one function with
 * handcrafted inputs.
 */
class CloudRunDeployHelpersTest {

  @get:Rule val tmp = TemporaryFolder()

  // ---------- invalidAppEnv ----------

  @Test
  fun invalidAppEnv_empty_returnsEmpty() {
    assertThat(invalidAppEnv(emptyList())).isEmpty()
  }

  @Test
  fun invalidAppEnv_wellFormed_returnsEmpty() {
    // KEY=VAL where VAL contains additional `=` or `,` is valid; only KEY shape matters.
    assertThat(invalidAppEnv(listOf("K=v", "FOO=bar=baz", "X=", "LIST=a,b,c"))).isEmpty()
  }

  @Test
  fun invalidAppEnv_missingEquals_isInvalid() {
    assertThat(invalidAppEnv(listOf("NOEQUALS", "OK=1"))).containsExactly("NOEQUALS")
  }

  @Test
  fun invalidAppEnv_emptyKey_isInvalid() {
    assertThat(invalidAppEnv(listOf("=value", "OK=1", "=")))
      .containsExactly("=value", "=")
      .inOrder()
  }

  // ---------- offendingGcloudExtras ----------

  @Test
  fun offendingGcloudExtras_empty_returnsEmpty() {
    assertThat(offendingGcloudExtras(emptyList())).isEmpty()
  }

  @Test
  fun offendingGcloudExtras_unrelatedFlags_returnsEmpty() {
    assertThat(offendingGcloudExtras(listOf("--memory", "1Gi", "--cpu", "2", "--max-instances=5")))
      .isEmpty()
  }

  @Test
  fun offendingGcloudExtras_detectsLongForm() {
    assertThat(offendingGcloudExtras(listOf("--memory", "1Gi", "--region", "us-central1")))
      .containsExactly("--region")
  }

  @Test
  fun offendingGcloudExtras_detectsInlineForm() {
    assertThat(offendingGcloudExtras(listOf("--project=foo", "--cpu=2")))
      .containsExactly("--project=foo")
  }

  @Test
  fun offendingGcloudExtras_detectsShortAlias() {
    assertThat(offendingGcloudExtras(listOf("-r", "europe-west4"))).containsExactly("-r")
  }

  // ---------- resolveInstallImage ----------

  @Test
  fun resolveInstallImage_missingBuildInstallDir_returnsMissingDir() {
    val projectDir = tmp.newFolder("proj").toPath()
    val result = resolveInstallImage(projectDir)
    assertThat(result).isInstanceOf(InstallImageResult.MissingDir::class.java)
    assertThat((result as InstallImageResult.MissingDir).installRoot)
      .isEqualTo(projectDir.resolve("build").resolve("install"))
  }

  @Test
  fun resolveInstallImage_emptyInstallDir_returnsNone() {
    val projectDir = tmp.newFolder("proj").toPath()
    Files.createDirectories(projectDir.resolve("build").resolve("install"))
    assertThat(resolveInstallImage(projectDir)).isInstanceOf(InstallImageResult.None::class.java)
  }

  @Test
  fun resolveInstallImage_singleChild_returnsFound() {
    val projectDir = tmp.newFolder("proj").toPath()
    val image = projectDir.resolve("build/install/my-app")
    Files.createDirectories(image)
    val result = resolveInstallImage(projectDir)
    assertThat(result).isInstanceOf(InstallImageResult.Found::class.java)
    assertThat((result as InstallImageResult.Found).path).isEqualTo(image)
  }

  @Test
  fun resolveInstallImage_multipleDirs_ignoresStrayFile_returnsMultiple() {
    val projectDir = tmp.newFolder("proj").toPath()
    Files.createDirectories(projectDir.resolve("build/install/app-a"))
    Files.createDirectories(projectDir.resolve("build/install/app-b"))
    // Loose file in install root must not count as a candidate.
    Files.createFile(projectDir.resolve("build/install/stray.txt"))
    val result = resolveInstallImage(projectDir)
    assertThat(result).isInstanceOf(InstallImageResult.Multiple::class.java)
    assertThat((result as InstallImageResult.Multiple).candidates)
      .containsExactly("app-a", "app-b")
      .inOrder()
  }

  // ---------- resolveEntryScript ----------

  @Test
  fun resolveEntryScript_missingDir_returnsMissingDir() {
    val bin = tmp.root.toPath().resolve("nonexistent/bin")
    assertThat(resolveEntryScript(bin)).isInstanceOf(EntryScriptResult.MissingDir::class.java)
  }

  @Test
  fun resolveEntryScript_emptyDir_returnsNone() {
    val bin = tmp.newFolder("bin").toPath()
    assertThat(resolveEntryScript(bin)).isInstanceOf(EntryScriptResult.None::class.java)
  }

  @Test
  fun resolveEntryScript_ignoresBatFile_returnsFound() {
    val bin = tmp.newFolder("bin").toPath()
    Files.createFile(bin.resolve("my-app"))
    Files.createFile(bin.resolve("my-app.bat"))
    val result = resolveEntryScript(bin)
    assertThat(result).isInstanceOf(EntryScriptResult.Found::class.java)
    assertThat((result as EntryScriptResult.Found).name).isEqualTo("my-app")
  }

  @Test
  fun resolveEntryScript_multipleNonBat_returnsMultiple() {
    val bin = tmp.newFolder("bin").toPath()
    Files.createFile(bin.resolve("app-one"))
    Files.createFile(bin.resolve("app-two"))
    // A `.bat` for one of them must not be counted.
    Files.createFile(bin.resolve("app-one.bat"))
    val result = resolveEntryScript(bin)
    assertThat(result).isInstanceOf(EntryScriptResult.Multiple::class.java)
    assertThat((result as EntryScriptResult.Multiple).candidates)
      .containsExactly("app-one", "app-two")
      .inOrder()
  }

  // ---------- buildGcpEnvBlock ----------

  @Test
  fun buildGcpEnvBlock_allUnset_returnsEmpty() {
    assertThat(buildGcpEnvBlock(null, null, useVertexAi = false)).isEmpty()
  }

  @Test
  fun buildGcpEnvBlock_blankValues_omitted() {
    // Blank is treated as unset; we deliberately avoid emitting `ENV FOO=` (which would actively
    // clear any inherited value at runtime, the opposite of "default").
    assertThat(buildGcpEnvBlock("  ", "", useVertexAi = false)).isEmpty()
  }

  @Test
  fun buildGcpEnvBlock_onlyVertex_emitsOnlyThatLine() {
    assertThat(buildGcpEnvBlock(null, null, useVertexAi = true))
      .isEqualTo("ENV GOOGLE_GENAI_USE_VERTEXAI=1")
  }

  @Test
  fun buildGcpEnvBlock_allSet_emitsThreeLinesInOrder() {
    assertThat(buildGcpEnvBlock("my-proj", "us-central1", useVertexAi = true))
      .isEqualTo(
        """
        ENV GOOGLE_CLOUD_PROJECT=my-proj
        ENV GOOGLE_CLOUD_LOCATION=us-central1
        ENV GOOGLE_GENAI_USE_VERTEXAI=1
        """
          .trimIndent()
      )
  }

  @Test
  fun buildGcpEnvBlock_projectOnly_emitsSingleLine() {
    assertThat(buildGcpEnvBlock("p", null, useVertexAi = false))
      .isEqualTo("ENV GOOGLE_CLOUD_PROJECT=p")
  }

  // ---------- renderDockerfile ----------

  @Test
  fun renderDockerfile_substitutesAllPlaceholders() {
    val template =
      """
      FROM eclipse-temurin:21-jre
      ${'$'}{gcpEnvBlock}
      EXPOSE ${'$'}{serverPort}
      ENTRYPOINT ["/app/bin/${'$'}{entryScript}"]
      """
        .trimIndent()
    val out =
      renderDockerfile(
        template = template,
        entryScript = "my-app",
        serverPort = 8080,
        gcpEnvBlock = "ENV GOOGLE_CLOUD_PROJECT=p",
      )
    assertThat(out)
      .isEqualTo(
        """
        FROM eclipse-temurin:21-jre
        ENV GOOGLE_CLOUD_PROJECT=p
        EXPOSE 8080
        ENTRYPOINT ["/app/bin/my-app"]
        """
          .trimIndent()
      )
  }

  @Test
  fun renderDockerfile_repeatedPlaceholders_allReplaced() {
    // Both occurrences of `${entryScript}` must be substituted (no first-match-only bug).
    val template = "A ${'$'}{entryScript} B ${'$'}{entryScript}"
    assertThat(
        renderDockerfile(template = template, entryScript = "X", serverPort = 1, gcpEnvBlock = "")
      )
      .isEqualTo("A X B X")
  }

  // ---------- buildGcloudDeployArgs ----------

  private fun defaultArgs(
    appArgs: List<String> = emptyList(),
    appEnv: List<String> = emptyList(),
    gcloudExtras: List<String> = emptyList(),
    interactive: Boolean = false,
    allowUnauthenticated: Boolean = true,
  ) =
    GcloudDeployArgs(
      serviceName = "my-svc",
      region = "us-central1",
      projectName = "my-proj",
      ingress = "all",
      allowUnauthenticated = allowUnauthenticated,
      serverPort = 8080,
      interactive = interactive,
      appArgs = appArgs,
      appEnv = appEnv,
      gcloudExtras = gcloudExtras,
    )

  @Test
  fun buildGcloudDeployArgs_baseline_includesQuietAndStandardFlags() {
    assertThat(buildGcloudDeployArgs(defaultArgs()))
      .containsExactly(
        "gcloud",
        "run",
        "deploy",
        "my-svc",
        "--source",
        ".",
        "--region",
        "us-central1",
        "--project",
        "my-proj",
        "--ingress",
        "all",
        "--allow-unauthenticated",
        "--port",
        "8080",
        "--quiet",
        "--update-labels=created-by=adk-kotlin",
      )
      .inOrder()
  }

  @Test
  fun buildGcloudDeployArgs_interactive_omitsQuiet() {
    assertThat(buildGcloudDeployArgs(defaultArgs(interactive = true))).doesNotContain("--quiet")
  }

  @Test
  fun buildGcloudDeployArgs_noAllowUnauthenticated_emitsNegatedFlag() {
    val cmd = buildGcloudDeployArgs(defaultArgs(allowUnauthenticated = false))
    assertThat(cmd).contains("--no-allow-unauthenticated")
    assertThat(cmd).doesNotContain("--allow-unauthenticated")
  }

  @Test
  fun buildGcloudDeployArgs_emptyAppArgs_omitsArgsFlag() {
    assertThat(buildGcloudDeployArgs(defaultArgs()).none { it.startsWith("--args=") }).isTrue()
  }

  @Test
  fun buildGcloudDeployArgs_appArgs_joinedAndCommasDoubled() {
    // Each `,` inside a value becomes `,,` so gcloud doesn't split on it. The separator between
    // values is a single `,`.
    val cmd = buildGcloudDeployArgs(defaultArgs(appArgs = listOf("--csv=a,b,c", "--simple=x")))
    assertThat(cmd).contains("--args=--csv=a,,b,,c,--simple=x")
  }

  @Test
  fun buildGcloudDeployArgs_appEnv_joinedAndCommasDoubled() {
    val cmd = buildGcloudDeployArgs(defaultArgs(appEnv = listOf("LIST=a,b", "OK=1")))
    assertThat(cmd).contains("--set-env-vars=LIST=a,,b,OK=1")
  }

  @Test
  fun buildGcloudDeployArgs_gcloudExtras_appendedLast() {
    // Extras must be the suffix so user overrides win over CLI-set defaults.
    val cmd =
      buildGcloudDeployArgs(defaultArgs(gcloudExtras = listOf("--memory", "1Gi", "--cpu=2")))
    assertThat(cmd.takeLast(3)).containsExactly("--memory", "1Gi", "--cpu=2").inOrder()
  }

  @Test
  fun buildGcloudDeployArgs_labelsAlwaysPresent() {
    assertThat(buildGcloudDeployArgs(defaultArgs()))
      .contains("--update-labels=created-by=adk-kotlin")
  }
}
