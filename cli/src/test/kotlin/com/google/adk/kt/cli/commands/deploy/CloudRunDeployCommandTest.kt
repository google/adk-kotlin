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

import com.github.ajalt.clikt.testing.test
import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Integration-level coverage for [CloudRunDeployCommand] surface area that can't be reached from
 * the unit tests in [CloudRunDeployHelpersTest]:
 * - Clikt's own argv parsing (required positional, types, `mustExist`).
 * - Help text wiring.
 * - `--skip-deploy` orchestration short-circuit.
 *
 * Per-helper behavior (validation, install-image lookup, entry-script lookup, env-block, argv
 * construction, etc.) is covered by the unit tests; we deliberately don't duplicate it here.
 */
class CloudRunDeployCommandTest {

  @get:Rule val tmp = TemporaryFolder()

  private fun projectDir(): Path = tmp.newFolder("proj").toPath()

  // ---------- argv parsing (Clikt) ----------

  @Test
  fun missingProjectDir_failsWithUsageError() {
    val result = CloudRunDeployCommand().test("")
    assertThat(result.statusCode).isNotEqualTo(0)
    assertThat(result.stderr.lowercase()).contains("project_dir")
  }

  @Test
  fun nonexistentProjectDir_failsWithMustExist() {
    val ghost = tmp.root.toPath().resolve("does-not-exist")
    val result = CloudRunDeployCommand().test(listOf(ghost.toString()))
    assertThat(result.statusCode).isNotEqualTo(0)
    assertThat(result.stderr.lowercase()).contains("does not exist")
  }

  @Test
  fun projectDirAsFile_failsBecauseDirRequired() {
    val file = tmp.newFile("not-a-dir.txt").toPath()
    val result = CloudRunDeployCommand().test(listOf(file.toString()))
    assertThat(result.statusCode).isNotEqualTo(0)
  }

  @Test
  fun serverPort_nonInteger_failsWithTypeError() {
    val result =
      CloudRunDeployCommand()
        .test(listOf(projectDir().toString(), "--server-port", "not-a-number", "--skip-deploy"))
    assertThat(result.statusCode).isNotEqualTo(0)
    assertThat(result.stderr.lowercase()).contains("int")
  }

  // ---------- help ----------

  @Test
  fun help_listsAllUserFacingFlags() {
    val result = CloudRunDeployCommand().test(listOf("--help"))
    assertThat(result.statusCode).isEqualTo(0)
    val stdout = result.stdout
    listOf(
        "--region",
        "--project",
        "--service-name",
        "--server-port",
        "--skip-deploy",
        "--dockerfile-template",
        "--temp-folder",
        "--ingress",
        "--allow-unauthenticated",
        "--interactive",
        "--use-vertexai",
        "--app-arg",
        "--app-env",
      )
      .forEach { assertThat(stdout).contains(it) }
  }

  // ---------- skip-deploy short-circuit ----------

  /**
   * Writes a fake POSIX `gradlew` script that materializes a minimal install image layout so the
   * `--skip-deploy` path can run start-to-finish without invoking real Gradle.
   */
  private fun writeFakeGradlew(projectDir: Path) {
    val gradlew = projectDir.resolve("gradlew")
    Files.writeString(
      gradlew,
      """
      #!/bin/sh
      set -e
      mkdir -p build/install/my-app/bin
      touch build/install/my-app/bin/my-app
      """
        .trimIndent(),
    )
    Files.setPosixFilePermissions(
      gradlew,
      setOf(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE,
        PosixFilePermission.OWNER_EXECUTE,
      ),
    )
  }

  @Test
  fun skipDeploy_neverShellsOutToGcloudOrChecksRequiredFlags() {
    val proj = projectDir()
    writeFakeGradlew(proj)
    val result = CloudRunDeployCommand().test(listOf(proj.toString(), "--skip-deploy"))
    assertThat(result.statusCode).isEqualTo(0)
    // The orchestration code must short-circuit *before* the gcloud step and *before* the
    // missing-required-flags check fires.
    assertThat(result.output).doesNotContain("Deploying to Cloud Run")
    assertThat(result.output).doesNotContain("Cannot deploy: missing required flag(s)")
  }
}
