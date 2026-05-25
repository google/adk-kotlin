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

package com.google.adk.kt.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Comparator
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isExecutable
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.relativeTo
import kotlin.io.path.writeText

/**
 * Builds, bundles, and deploys an agent application to Google Cloud Run.
 *
 * Runs `gradle installDist` on the user's project, copies the resulting install image alongside a
 * generated Dockerfile into a bundle directory, and invokes `gcloud run deploy --source ...` to
 * push the bundle to Cloud Run. Runtime arguments and environment variables for the application can
 * be supplied via the repeatable `--app-arg` and `--app-env` options.
 */
class DeployCommand : CliktCommand(name = "deploy") {
  override fun help(context: Context): String =
    "Builds the agent app, bundles it with a Dockerfile, and deploys it to Google Cloud Run."

  private val projectDir: Path by
    argument(
        name = "PROJECT_DIR",
        help = "Directory containing the application entrypoint (a Gradle project).",
      )
      .path(mustExist = true, canBeFile = false, canBeDir = true)

  private val gcloudExtras: List<String> by
    argument(
        name = "GCLOUD_EXTRAS",
        help =
          "Extra arguments forwarded verbatim to `gcloud run deploy`. " +
            "Separate them from the CLI's own flags with `--`. " +
            "Example: `... ./my-project -- --memory 1Gi --cpu 2 --set-env-vars FOO=bar`.",
      )
      .multiple()

  private val region: String? by
    option("-r", "--region", help = "GCP region for the Cloud Run service.")

  private val projectName: String? by
    option("-p", "--project", help = "GCP project name to deploy to.")

  private val serviceName: String? by
    option("-s", "--service-name", help = "Cloud Run service name.")

  private val serverPort: Int by
    option("--server-port", help = "Port the Cloud Run container will listen on.")
      .int()
      .default(8080)

  private val skipDeploy: Boolean by
    option(
        "--skip-deploy",
        help =
          "Build the bundle and write the Dockerfile, but do NOT invoke gcloud. " +
            "Use for local smoke tests.",
      )
      .flag(default = false)

  private val dockerfileTemplate: Path? by
    option(
        "--dockerfile-template",
        help =
          "Path to a Dockerfile template overriding the built-in one. " +
            "Supports \${entryScript} and \${serverPort} placeholders.",
      )
      .path(mustExist = true, canBeDir = false, canBeFile = true)

  private val ingress: String by
    option(
        "--ingress",
        help =
          "Cloud Run ingress setting. One of: all, internal, internal-and-cloud-load-balancing. ",
      )
      .default("all")

  private val allowUnauthenticated: Boolean by
    option(
        "--allow-unauthenticated",
        help =
          "Make the deployed service callable without IAM auth (--no-allow-unauthenticated to " +
            "lock it down).",
      )
      .flag("--no-allow-unauthenticated", default = true)

  private val appArgs: List<String> by
    option(
        "--app-arg",
        help =
          "Runtime argument for the deployed application (repeatable). Forwarded to " +
            "`gcloud run deploy --args` so it lands as additional argv after the launcher. " +
            "Embedded commas in values are escaped by doubling. " +
            "Example: `--app-arg --agent-priority=high --app-arg --memory-limit=512m`.",
      )
      .multiple()

  private val appEnv: List<String> by
    option(
        "--app-env",
        help =
          "Environment variable for the deployed application as KEY=VAL (repeatable). " +
            "Forwarded to `gcloud run deploy --set-env-vars`. Embedded commas in values are " +
            "escaped by doubling. " +
            "Example: `--app-env LOG_LEVEL=debug --app-env REGION=us-central1`.",
      )
      .multiple()

  override fun run() {
    val absoluteProjectDir = projectDir.toAbsolutePath().normalize()
    validateAppEnv(appEnv)
    logStartStop("Computing flags") {
      printFlags(
        listOf(
          "project dir" to absoluteProjectDir.toString(),
          "region" to (region ?: "<unset>"),
          "project" to (projectName ?: "<unset>"),
          "service" to (serviceName ?: "<unset>"),
          "server port" to serverPort.toString(),
          "ingress" to ingress,
          "allow-unauthenticated" to allowUnauthenticated.toString(),
          "skip-deploy" to skipDeploy.toString(),
          "gcloud extras" to
            if (gcloudExtras.isEmpty()) "<none>" else gcloudExtras.joinToString(" "),
          "app args" to if (appArgs.isEmpty()) "<none>" else appArgs.joinToString(" "),
          "app env" to if (appEnv.isEmpty()) "<none>" else appEnv.joinToString(" "),
        )
      )
    }

    logStartStop("Building project with Gradle") { runGradleBuild(absoluteProjectDir) }

    val installImage =
      logStartStopReturning("Locating install image") {
        findInstallImage(absoluteProjectDir).also { echo("  found: $it") }
      }

    val bundleDir = absoluteProjectDir.resolve("build").resolve("adk-bundle")
    val entryScript =
      logStartStopReturning("Preparing bundle at $bundleDir") {
        prepareBundleDir(bundleDir)
        val appDir = bundleDir.resolve("app")
        copyDirectory(installImage, appDir)
        findEntryScript(appDir.resolve("bin"))
      }

    logStartStop("Writing Dockerfile") { writeDockerfile(bundleDir, entryScript) }

    if (skipDeploy) {
      echo("--skip-deploy set; bundle ready at $bundleDir (entrypoint: bin/$entryScript).")
      return
    }

    val missing = buildList {
      if (region.isNullOrBlank()) add("--region")
      if (projectName.isNullOrBlank()) add("--project")
      if (serviceName.isNullOrBlank()) add("--service-name")
    }
    if (missing.isNotEmpty()) {
      echo(
        "Cannot deploy: missing required flag(s): ${missing.joinToString(", ")}. " +
          "Pass them or rerun with --skip-deploy.",
        err = true,
      )
      throw ProgramResult(1)
    }

    logStartStop("Deploying to Cloud Run") {
      gcloudDeploy(
        bundleDir = bundleDir,
        region = region!!,
        projectName = projectName!!,
        serviceName = serviceName!!,
      )
    }

    echo("Done. Service '$serviceName' deployed to region '$region' in project '$projectName'.")
  }

  private fun runGradleBuild(projectDir: Path) {
    val command = findGradleCommand(projectDir)
    val fullCommand = command + listOf("installDist", "--no-daemon")
    echo("  running: ${fullCommand.joinToString(" ")}")
    val exitCode =
      try {
        ProcessBuilder(fullCommand).directory(projectDir.toFile()).inheritIO().start().waitFor()
      } catch (e: Exception) {
        echo("Failed to launch gradle: ${e.message}", err = true)
        throw CliktError(
          message = "Failed to launch gradle.",
          cause = e,
          statusCode = 1,
          printError = false,
        )
      }
    if (exitCode != 0) {
      echo("Gradle build failed with exit code $exitCode", err = true)
      throw ProgramResult(exitCode)
    }
  }

  /**
   * Picks a gradle invocation for the given project.
   *
   * Prefers the project's gradle wrapper if present and executable, otherwise falls back to
   * `gradle` on `PATH`. Errors out if neither is usable.
   *
   * @param projectDir The project directory to search for a gradle wrapper in.
   */
  private fun findGradleCommand(projectDir: Path): List<String> {
    val isWindows = System.getProperty("os.name").orEmpty().lowercase().contains("win")
    val wrapperName = if (isWindows) "gradlew.bat" else "gradlew"
    val wrapper = projectDir.resolve(wrapperName)
    if (wrapper.exists() && wrapper.isRegularFile() && (isWindows || wrapper.isExecutable())) {
      return listOf(wrapper.toString())
    }
    if (isOnPath("gradle")) {
      return listOf("gradle")
    }
    echo(
      "Neither '$wrapperName' (in $projectDir) nor 'gradle' on PATH was found. " +
        "Please add a Gradle wrapper to your project or install Gradle.",
      err = true,
    )
    throw ProgramResult(1)
  }

  private fun isOnPath(executable: String): Boolean {
    val pathEnv = System.getenv("PATH") ?: return false
    val pathSep = System.getProperty("path.separator") ?: ":"
    return pathEnv.split(pathSep).any { dir ->
      val candidate = Path.of(dir, executable)
      candidate.exists() && candidate.isRegularFile()
    }
  }

  /**
   * Prints a list of label/value pairs as a left-aligned two-column table.
   *
   * The label column width is computed from the longest label in the input so that the separators
   * line up without any hardcoded padding in the call sites.
   *
   * @param entries The label/value pairs to print, in display order.
   */
  private fun printFlags(entries: List<Pair<String, String>>) {
    if (entries.isEmpty()) return
    val labelWidth = entries.maxOf { it.first.length }
    for ((label, value) in entries) {
      echo("  ${label.padEnd(labelWidth)} : $value")
    }
  }

  /**
   * Validates that each `--app-env` entry is in `KEY=VAL` form.
   *
   * The key must be a non-empty string with no `=` characters; the value (everything after the
   * first `=`) is unrestricted and may itself contain `=` characters.
   *
   * @param entries The raw list of `--app-env` strings collected from the CLI.
   */
  private fun validateAppEnv(entries: List<String>) {
    val invalid = entries.filter { entry ->
      val eq = entry.indexOf('=')
      eq <= 0
    }
    if (invalid.isNotEmpty()) {
      echo(
        "Invalid --app-env value(s): ${invalid.joinToString(", ") { "\"$it\"" }}. " +
          "Each entry must be in KEY=VAL form with a non-empty KEY.",
        err = true,
      )
      throw ProgramResult(1)
    }
  }

  private fun findInstallImage(projectDir: Path): Path {
    val installRoot = projectDir.resolve("build").resolve("install")
    if (!installRoot.exists() || !installRoot.isDirectory()) {
      echo(
        "Expected install directory at $installRoot was not produced by Gradle. " +
          "Make sure your project applies the 'application' plugin.",
        err = true,
      )
      throw ProgramResult(1)
    }
    val children = installRoot.listDirectoryEntries().filter { it.isDirectory() }
    return when (children.size) {
      0 -> {
        echo("No install image found under $installRoot.", err = true)
        throw ProgramResult(1)
      }
      1 -> children.single()
      else -> {
        echo(
          "Multiple install images found under $installRoot: " +
            children.joinToString(", ") { it.name } +
            ". Cannot pick one automatically.",
          err = true,
        )
        throw ProgramResult(1)
      }
    }
  }

  /**
   * Recreates the bundle directory as an empty directory.
   *
   * If the directory already exists, its contents are recursively deleted before it is recreated.
   *
   * @param bundleDir The directory to recreate.
   */
  private fun prepareBundleDir(bundleDir: Path) {
    if (bundleDir.exists()) {
      Files.walk(bundleDir).use { stream ->
        stream.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) }
      }
    }
    Files.createDirectories(bundleDir)
  }

  /**
   * Recursively copies a directory tree.
   *
   * Existing files at the destination are overwritten and file attributes are preserved.
   *
   * @param src The source directory to copy from.
   * @param dst The destination directory to copy into.
   */
  private fun copyDirectory(src: Path, dst: Path) {
    Files.createDirectories(dst)
    Files.walk(src).use { stream ->
      stream.forEach { source ->
        val target = dst.resolve(source.relativeTo(src).toString())
        if (source.isDirectory()) {
          Files.createDirectories(target)
        } else {
          // `target.parent` is `Path?` because filesystem roots have no parent. Files copied
          // inside [dst] always have at least one parent component, so this is safe.
          target.parent?.let { Files.createDirectories(it) }
          Files.copy(
            source,
            target,
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.COPY_ATTRIBUTES,
          )
        }
      }
    }
  }

  /**
   * Finds the launcher script under the application's `bin/` directory.
   *
   * Selects the single non-`.bat` entry produced by Gradle's `application` plugin and errors out if
   * zero or more than one candidate is present.
   *
   * @param appBinDir The `bin/` directory inside the bundled install image.
   */
  private fun findEntryScript(appBinDir: Path): String {
    if (!appBinDir.exists() || !appBinDir.isDirectory()) {
      echo("Expected launcher directory at $appBinDir was not found.", err = true)
      throw ProgramResult(1)
    }
    val candidates =
      appBinDir
        .listDirectoryEntries()
        .filter { it.isRegularFile() && !it.name.endsWith(".bat") }
        .map { it.name }
    return when (candidates.size) {
      0 -> {
        echo("No launcher script found in $appBinDir.", err = true)
        throw ProgramResult(1)
      }
      1 -> candidates.single()
      else -> {
        echo(
          "Multiple launcher scripts found in $appBinDir: ${candidates.joinToString(", ")}. " +
            "Cannot pick one automatically.",
          err = true,
        )
        throw ProgramResult(1)
      }
    }
  }

  /**
   * Writes a Dockerfile into the bundle directory by loading and rendering a template.
   *
   * Uses either the explicit `--dockerfile-template` file or the built-in `Dockerfile.template`
   * classpath resource, then substitutes the `${entryScript}` and `${serverPort}` placeholders.
   *
   * @param bundleDir The bundle directory to write the rendered Dockerfile into.
   * @param entryScript The name of the launcher script to bake into the Dockerfile.
   */
  private fun writeDockerfile(bundleDir: Path, entryScript: String) {
    val template = loadDockerfileTemplate()
    val rendered =
      template
        .replace("\${entryScript}", entryScript)
        .replace("\${serverPort}", serverPort.toString())
    val outFile = bundleDir.resolve("Dockerfile")
    outFile.writeText(rendered)
    echo("  wrote: $outFile")
  }

  private fun loadDockerfileTemplate(): String {
    dockerfileTemplate?.let { path ->
      echo("  using Dockerfile template: $path")
      return Files.readString(path)
    }
    val resourceName = "Dockerfile.template"
    // `Class.getClassLoader()` is `ClassLoader?` (bootstrap-loaded classes return null). This
    // class is application-loaded so the loader is always non-null in practice; the safe call
    // simply keeps the compiler happy and degrades to the "not found" branch in the impossible
    // case it isn't.
    val stream =
      this::class.java.classLoader?.getResourceAsStream(resourceName)
        ?: run {
          echo(
            "Built-in Dockerfile template '$resourceName' not found on the classpath. " +
              "Pass --dockerfile-template explicitly.",
            err = true,
          )
          throw ProgramResult(1)
        }
    echo("  using built-in Dockerfile template (classpath: $resourceName)")
    return stream.use { it.readBytes().toString(Charsets.UTF_8) }
  }

  /**
   * Invokes `gcloud run deploy ...` from inside the bundle directory.
   *
   * Streams gcloud's output to the user's terminal and propagates non-zero exit codes as
   * [ProgramResult] failures.
   *
   * @param bundleDir The bundle directory to run gcloud from.
   * @param region The GCP region to deploy to.
   * @param projectName The GCP project name to deploy to.
   * @param serviceName The Cloud Run service name to deploy as.
   */
  private fun gcloudDeploy(
    bundleDir: Path,
    region: String,
    projectName: String,
    serviceName: String,
  ) {
    if (!isOnPath("gcloud")) {
      echo(
        "`gcloud` not found on PATH. Install the Google Cloud SDK or rerun with --skip-deploy.",
        err = true,
      )
      throw ProgramResult(1)
    }
    val command = buildList {
      add("gcloud")
      add("run")
      add("deploy")
      add(serviceName)
      add("--source")
      add(".")
      add("--region")
      add(region)
      add("--project")
      add(projectName)
      add("--ingress")
      add(ingress)
      add(if (allowUnauthenticated) "--allow-unauthenticated" else "--no-allow-unauthenticated")
      add("--port")
      add(serverPort.toString())
      // --quiet suppresses interactive prompts (e.g. enable-API confirmations) so the
      // CLI doesn't hang when stdin isn't a TTY.
      add("--quiet")
      // Forward --app-arg entries to gcloud --args. gcloud --args takes a comma-separated list;
      // embedded commas in values are escaped by doubling. The `--flag=value` form is required
      // here because app args often start with `--` themselves (e.g. `--greeting=Bonjour`), and
      // gcloud would otherwise treat them as the next flag instead of as the value of `--args`.
      if (appArgs.isNotEmpty()) {
        add("--args=" + appArgs.joinToString(",") { it.replace(",", ",,") })
      }
      // Forward --app-env KEY=VAL entries to gcloud --set-env-vars (same comma-doubling escape).
      // Use the `--flag=value` form so that env values containing spaces stay in a single argv
      // token when handed to gcloud.
      if (appEnv.isNotEmpty()) {
        add("--set-env-vars=" + appEnv.joinToString(",") { it.replace(",", ",,") })
      }
      // User-supplied passthrough arguments (everything after `--` on the CLI). Appended last so
      // they can override any of the defaults set above.
      addAll(gcloudExtras)
    }
    echo("  running (in $bundleDir): ${command.joinToString(" ")}")
    val exitCode =
      try {
        ProcessBuilder(command).directory(bundleDir.toFile()).inheritIO().start().waitFor()
      } catch (e: Exception) {
        echo("Failed to launch gcloud: ${e.message}", err = true)
        // Wrap the original exception as the cause so it shows up in stack traces / debug logs.
        // `printError = false` because we've already echoed a user-friendly message above.
        throw CliktError(
          message = "Failed to launch gcloud.",
          cause = e,
          statusCode = 1,
          printError = false,
        )
      }
    if (exitCode != 0) {
      echo("gcloud run deploy failed with exit code $exitCode", err = true)
      throw ProgramResult(exitCode)
    }
  }

  /**
   * Runs a block of work bracketed by a header and footer log line.
   *
   * Mirrors the Go `util.LogStartStop` helper used by the reference cloudrun deployer.
   *
   * @param label The label to print in the header and footer.
   * @param block The block of work to run between the header and footer.
   */
  private inline fun logStartStop(label: String, block: () -> Unit) {
    echo("==> $label ...")
    block()
    echo("<== $label done.")
  }

  /**
   * Runs a value-returning block of work bracketed by a header and footer log line.
   *
   * Mirrors the Go `util.LogStartStop` helper used by the reference cloudrun deployer and returns
   * the block's result.
   *
   * @param label The label to print in the header and footer.
   * @param block The block of work to run between the header and footer.
   */
  private inline fun <T> logStartStopReturning(label: String, block: () -> T): T {
    echo("==> $label ...")
    val result = block()
    echo("<== $label done.")
    return result
  }
}
