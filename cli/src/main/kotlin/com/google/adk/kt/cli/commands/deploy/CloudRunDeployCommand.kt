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
class CloudRunDeployCommand : CliktCommand(name = "cloud_run") {

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
    option(
      "-r",
      "--region",
      help =
        "GCP region for the Cloud Run service. Required for deploy; falls back to " +
          "`gcloud config get-value run/region` then `compute/region`. Unlike the Python ADK, " +
          "this CLI does NOT let gcloud interactively prompt for a missing region (would hang " +
          "in CI); it fails fast instead.",
    )

  private val projectName: String? by
    option(
      "-p",
      "--project",
      help =
        "GCP project to deploy to. Required for deploy; falls back to " +
          "`gcloud config get-value core/project`.",
    )

  private val serviceName: String? by
    option(
      "-s",
      "--service-name",
      help =
        "Cloud Run service name. Required for deploy (no fallback). Unlike the Python ADK, " +
          "this CLI does NOT derive a default from the project directory or let gcloud prompt; " +
          "scripted callers must name the service explicitly.",
    )

  private val serverPort: Int by
    option(
        "--server-port",
        help =
          "Port the Cloud Run container will listen on. Defaults to 8080, matching Cloud Run's " +
            "default container port (https://cloud.google.com/run/docs/container-contract#port). " +
            "Note: the Python ADK defaults this to 8000 instead, because its `adk api_server` " +
            "command listens on 8000 locally; this CLI aligns with Cloud Run's convention so the " +
            "deployed container Just Works without an extra `--port` round-trip. Override " +
            "explicitly if your app binds a different port.",
      )
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

  private val tempFolder: Path? by
    option(
        "--temp-folder",
        help =
          "Directory to write the build bundle (install image + Dockerfile) into. When unset, " +
            "a timestamped temp directory is created and deleted after the deploy finishes " +
            "(matching the Python ADK's behavior). When set, the directory is used as-is and " +
            "preserved on exit so it can be inspected.",
      )
      .path(canBeDir = true, canBeFile = false)

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

  private val interactive: Boolean by
    option(
        "--interactive",
        help =
          "Allow gcloud to prompt for confirmations (e.g. enabling APIs on first deploy, " +
            "creating a missing service). By default we pass `--quiet` to gcloud so the deploy " +
            "never blocks on stdin (CI-safe). Pass `--interactive` from a TTY to opt into the " +
            "prompts.",
      )
      .flag(default = false)

  private val useVertexAi: Boolean by
    option(
        "--use-vertexai",
        help =
          "Bake `ENV GOOGLE_GENAI_USE_VERTEXAI=1` into the Dockerfile (matches the Python ADK's " +
            "cloud_run default). Pass `--no-use-vertexai` to omit it, e.g. when the agent uses " +
            "the AI Studio backend instead.",
      )
      .flag("--no-use-vertexai", default = true)

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
            "Example: `--app-env LOG_LEVEL=debug --app-env ENV_VAR=ENV_VAL`.",
      )
      .multiple()

  /**
   * Resolved bundle directory + cleanup policy.
   *
   * @property dir Absolute path to the bundle directory.
   * @property userSupplied True if the user passed `--temp-folder` (we preserve it on exit).
   * @property cleanupOnExit True if we should `rm -rf` [dir] in the `finally` block.
   */
  private data class BundlePlan(
    val dir: Path,
    val userSupplied: Boolean,
    val cleanupOnExit: Boolean,
  )

  override fun run() {
    val absoluteProjectDir = projectDir.toAbsolutePath().normalize()
    validateAppEnv(appEnv)
    validateGcloudExtras(gcloudExtras)

    // Resolve project/region up front (gcloud-config fallback). We need them *before* writing
    // the Dockerfile so we can bake GOOGLE_CLOUD_PROJECT / GOOGLE_CLOUD_LOCATION into the
    // image (matches the Python ADK). They may legitimately stay null when --skip-deploy is
    // set and the user hasn't configured gcloud; in that case we just emit blank ENV lines.
    val resolvedRegion =
      region?.takeIf { it.isNotBlank() }
        ?: gcloudConfigGet("run/region")
        ?: gcloudConfigGet("compute/region")
    val resolvedProject = projectName?.takeIf { it.isNotBlank() } ?: gcloudConfigGet("core/project")

    val bundle = planBundleDir()
    logStartStop("Computing flags") { logComputedFlags(absoluteProjectDir, bundle) }

    try {
      buildAndBundle(absoluteProjectDir, bundle.dir, resolvedProject, resolvedRegion).also {
        entryScript ->
        if (skipDeploy) {
          echo("--skip-deploy set; bundle ready at ${bundle.dir} (entrypoint: bin/$entryScript).")
          return
        }
      }
      deployBundle(bundle.dir, resolvedRegion, resolvedProject)
    } finally {
      if (bundle.cleanupOnExit) {
        deleteDirectoryRecursively(bundle.dir)
      }
    }
  }

  /** Picks the bundle directory and decides whether we'll clean it up on exit. */
  private fun planBundleDir(): BundlePlan {
    val userSupplied: Path? = tempFolder?.toAbsolutePath()?.normalize()
    val dir = userSupplied ?: Files.createTempDirectory("adk-deploy-").toAbsolutePath().normalize()
    // Only auto-wipe directories we created ourselves, and only on a real deploy (skip-deploy
    // users typically want to inspect the bundle).
    val cleanupOnExit = userSupplied == null && !skipDeploy
    return BundlePlan(dir = dir, userSupplied = userSupplied != null, cleanupOnExit = cleanupOnExit)
  }

  /** Prints the table of effective settings shown at the top of every run. */
  private fun logComputedFlags(absoluteProjectDir: Path, bundle: BundlePlan) {
    val bundleOrigin =
      when {
        bundle.userSupplied -> "user-supplied, preserved"
        bundle.cleanupOnExit -> "auto, wiped on exit"
        else -> "auto, preserved (skip-deploy)"
      }
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
        "bundle dir" to "${bundle.dir} ($bundleOrigin)",
        "gcloud extras" to if (gcloudExtras.isEmpty()) "<none>" else gcloudExtras.joinToString(" "),
        "app args" to if (appArgs.isEmpty()) "<none>" else appArgs.joinToString(" "),
        "app env" to if (appEnv.isEmpty()) "<none>" else appEnv.joinToString(" "),
      )
    )
  }

  /**
   * Runs Gradle, copies the install image into [bundleDir]/app, and writes the Dockerfile.
   *
   * @return The launcher script name (under `bin/`) baked into the Dockerfile.
   */
  private fun buildAndBundle(
    absoluteProjectDir: Path,
    bundleDir: Path,
    resolvedProject: String?,
    resolvedRegion: String?,
  ): String {
    logStartStop("Building project with Gradle") { runGradleBuild(absoluteProjectDir) }

    val installImage =
      logStartStopReturning("Locating install image") {
        findInstallImage(absoluteProjectDir).also { echo("  found: $it") }
      }

    val entryScript =
      logStartStopReturning("Preparing bundle at $bundleDir") {
        prepareBundleDir(bundleDir)
        val appDir = bundleDir.resolve("app")
        copyDirectory(installImage, appDir)
        findEntryScript(appDir.resolve("bin"))
      }

    logStartStop("Writing Dockerfile") {
      writeDockerfile(bundleDir, entryScript, resolvedProject, resolvedRegion)
    }
    return entryScript
  }

  /** Validates resolved deploy targets and shells out to `gcloud run deploy`. */
  private fun deployBundle(bundleDir: Path, resolvedRegion: String?, resolvedProject: String?) {
    // Required-flag policy (intentional divergence from the Python ADK):
    //
    // Python forwards a missing --region / --service to `gcloud run deploy`, which then
    // *interactively prompts* the user. That's nice for ad-hoc terminal use but actively
    // harmful in CI: the deploy hangs on stdin until the job times out, and the failure mode
    // ("why is my build stuck?") is hard to debug.
    //
    // This CLI is built to be scripted (Gradle/CI/agents), so we fail fast with a clear
    // message instead.
    val missing = buildList {
      if (resolvedRegion.isNullOrBlank()) add("--region")
      if (resolvedProject.isNullOrBlank()) add("--project")
      if (serviceName.isNullOrBlank()) add("--service-name")
    }
    if (missing.isNotEmpty()) {
      echo(
        "Cannot deploy: missing required flag(s): ${missing.joinToString(", ")}. " +
          "Pass them, set them via `gcloud config set` (core/project, run/region or " +
          "compute/region), or rerun with --skip-deploy.",
        err = true,
      )
      throw ProgramResult(1)
    }

    logStartStop("Deploying to Cloud Run") {
      gcloudDeploy(
        bundleDir = bundleDir,
        region = resolvedRegion!!,
        projectName = resolvedProject!!,
        serviceName = serviceName!!,
      )
    }

    echo(
      "Done. Service '$serviceName' deployed to region '$resolvedRegion' in project " +
        "'$resolvedProject'."
    )
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
    val invalid = invalidAppEnv(entries)
    if (invalid.isNotEmpty()) {
      echo(
        "Invalid --app-env value(s): ${invalid.joinToString(", ") { "\"$it\"" }}. " +
          "Each entry must be in KEY=VAL form with a non-empty KEY.",
        err = true,
      )
      throw ProgramResult(1)
    }
  }

  /**
   * Rejects passthrough arguments that conflict with flags this command sets itself.
   *
   * Matches the Python ADK's `_validate_gcloud_extra_args` behavior: certain gcloud flags (region,
   * project, source, port, verbosity) are owned by the CLI itself and forwarding them via the `--`
   * passthrough would either duplicate them (which gcloud rejects) or silently override the
   * typed-flag value (which is worse). We error out early with a clear message instead of letting
   * gcloud fail with a less helpful one. Both the long form (`--region`) and the inline form
   * (`--region=foo`) are detected.
   *
   * @param extras The raw token list collected after `--` on the CLI.
   */
  private fun validateGcloudExtras(extras: List<String>) {
    val offending = offendingGcloudExtras(extras)
    if (offending.isNotEmpty()) {
      echo(
        "The following passthrough argument(s) conflict with flags this CLI sets itself: " +
          "${offending.joinToString(", ") { "\"$it\"" }}. " +
          "Use the dedicated CLI option instead (e.g. --region, --project, --server-port).",
        err = true,
      )
      throw ProgramResult(1)
    }
  }

  private fun findInstallImage(projectDir: Path): Path =
    when (val result = resolveInstallImage(projectDir)) {
      is InstallImageResult.Found -> result.path
      is InstallImageResult.MissingDir -> {
        echo(
          "Expected install directory at ${result.installRoot} was not produced by Gradle. " +
            "Make sure your project applies the 'application' plugin.",
          err = true,
        )
        throw ProgramResult(1)
      }
      is InstallImageResult.None -> {
        echo("No install image found under ${result.installRoot}.", err = true)
        throw ProgramResult(1)
      }
      is InstallImageResult.Multiple -> {
        echo(
          "Multiple install images found under ${result.installRoot}: " +
            "${result.candidates.joinToString(", ")}. Cannot pick one automatically.",
          err = true,
        )
        throw ProgramResult(1)
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
      deleteDirectoryRecursively(bundleDir)
    }
    Files.createDirectories(bundleDir)
  }

  private fun deleteDirectoryRecursively(dir: Path) {
    if (!dir.exists()) return
    Files.walk(dir).use { stream ->
      stream.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) }
    }
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
  private fun findEntryScript(appBinDir: Path): String =
    when (val result = resolveEntryScript(appBinDir)) {
      is EntryScriptResult.Found -> result.name
      is EntryScriptResult.MissingDir -> {
        echo("Expected launcher directory at ${result.binDir} was not found.", err = true)
        throw ProgramResult(1)
      }
      is EntryScriptResult.None -> {
        echo("No launcher script found in ${result.binDir}.", err = true)
        throw ProgramResult(1)
      }
      is EntryScriptResult.Multiple -> {
        echo(
          "Multiple launcher scripts found in ${result.binDir}: " +
            "${result.candidates.joinToString(", ")}. Cannot pick one automatically.",
          err = true,
        )
        throw ProgramResult(1)
      }
    }

  /**
   * Writes a Dockerfile into the bundle directory by loading and rendering a template.
   *
   * Uses either the explicit `--dockerfile-template` file or the built-in `Dockerfile.template`
   * classpath resource, then substitutes the `${entryScript}`, `${serverPort}`, and
   * `${gcpEnvBlock}` placeholders.
   *
   * @param bundleDir The bundle directory to write the rendered Dockerfile into.
   * @param entryScript The name of the launcher script to bake into the Dockerfile.
   * @param resolvedProject The resolved GCP project to bake as `GOOGLE_CLOUD_PROJECT`, or null.
   * @param resolvedRegion The resolved GCP region to bake as `GOOGLE_CLOUD_LOCATION`, or null.
   */
  private fun writeDockerfile(
    bundleDir: Path,
    entryScript: String,
    resolvedProject: String?,
    resolvedRegion: String?,
  ) {
    val rendered =
      renderDockerfile(
        template = loadDockerfileTemplate(),
        entryScript = entryScript,
        serverPort = serverPort,
        gcpEnvBlock = buildGcpEnvBlock(resolvedProject, resolvedRegion, useVertexAi),
      )
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
    val command =
      buildGcloudDeployArgs(
        GcloudDeployArgs(
          serviceName = serviceName,
          region = region,
          projectName = projectName,
          ingress = ingress,
          allowUnauthenticated = allowUnauthenticated,
          serverPort = serverPort,
          interactive = interactive,
          appArgs = appArgs,
          appEnv = appEnv,
          gcloudExtras = gcloudExtras,
        )
      )
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
   * Reads a value from the local `gcloud config` (e.g. `core/project`, `run/region`).
   *
   * Returns `null` if `gcloud` isn't on PATH, the property is unset (gcloud prints the literal
   * string `(unset)` to stderr and exits 0 with empty stdout), the invocation fails, or the value
   * is blank. Never throws: callers treat a missing config value the same as "user didn't pass the
   * flag".
   *
   * @param property The dotted gcloud config property to read (e.g. `core/project`).
   */
  private fun gcloudConfigGet(property: String): String? {
    if (!isOnPath("gcloud")) return null
    return try {
      val process =
        ProcessBuilder("gcloud", "config", "get-value", property)
          .redirectErrorStream(false)
          .redirectOutput(ProcessBuilder.Redirect.PIPE)
          .redirectError(ProcessBuilder.Redirect.DISCARD)
          .start()
      val stdout = process.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
      val exitCode = process.waitFor()
      if (exitCode != 0) return null
      stdout.trim().takeIf { it.isNotEmpty() && it != "(unset)" }
    } catch (_: Exception) {
      null
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

  companion object {
    /** `--update-labels` value applied to every deployed Cloud Run service. */
    const val CREATED_BY_LABEL = "created-by=adk-kotlin"

    /**
     * gcloud flags this CLI owns and refuses to accept via the `--` passthrough.
     *
     * Forwarding any of these via `--` would either be rejected by gcloud as a duplicate or
     * silently override the typed-flag value. We catch them up front with a clear error.
     */
    val RESERVED_GCLOUD_FLAGS: Set<String> =
      setOf("--region", "-r", "--project", "-p", "--source", "--port", "--verbosity")

    /**
     * Returns the subset of [entries] that are NOT valid `KEY=VAL` pairs.
     *
     * A valid entry has a non-empty key (no `=` in it) and any value (possibly containing further
     * `=`). Empty result means "all entries are well-formed".
     */
    fun invalidAppEnv(entries: List<String>): List<String> = entries.filter { entry ->
      entry.indexOf('=') <= 0
    }

    /**
     * Returns the subset of `--` passthrough tokens that collide with [RESERVED_GCLOUD_FLAGS].
     *
     * Detects both `--region foo` and `--region=foo` forms by splitting on the first `=`.
     */
    fun offendingGcloudExtras(extras: List<String>): List<String> = extras.filter { token ->
      token.substringBefore('=') in RESERVED_GCLOUD_FLAGS
    }

    /**
     * Locates the single install image under `$projectDir/build/install/`.
     *
     * Returns a structured result instead of throwing so the caller can map each failure mode to a
     * specific user-facing error message.
     */
    fun resolveInstallImage(projectDir: Path): InstallImageResult {
      val installRoot = projectDir.resolve("build").resolve("install")
      if (!installRoot.exists() || !installRoot.isDirectory()) {
        return InstallImageResult.MissingDir(installRoot)
      }
      val children = installRoot.listDirectoryEntries().filter { it.isDirectory() }
      return when (children.size) {
        0 -> InstallImageResult.None(installRoot)
        1 -> InstallImageResult.Found(children.single())
        else -> InstallImageResult.Multiple(installRoot, children.map { it.name }.sorted())
      }
    }

    /**
     * Finds the single non-`.bat` launcher script under [appBinDir].
     *
     * Gradle's `application` plugin produces a pair: `<name>` (POSIX) and `<name>.bat` (Windows).
     * We always run inside a Linux container, so we deliberately ignore the `.bat` variant.
     */
    fun resolveEntryScript(appBinDir: Path): EntryScriptResult {
      if (!appBinDir.exists() || !appBinDir.isDirectory()) {
        return EntryScriptResult.MissingDir(appBinDir)
      }
      val candidates =
        appBinDir
          .listDirectoryEntries()
          .filter { it.isRegularFile() && !it.name.endsWith(".bat") }
          .map { it.name }
          .sorted()
      return when (candidates.size) {
        0 -> EntryScriptResult.None(appBinDir)
        1 -> EntryScriptResult.Found(candidates.single())
        else -> EntryScriptResult.Multiple(appBinDir, candidates)
      }
    }

    /**
     * Builds the `ENV ...` block injected into the Dockerfile.
     *
     * Mirrors the Python ADK's cloud_run deployer: bakes `GOOGLE_CLOUD_PROJECT`,
     * `GOOGLE_CLOUD_LOCATION`, and (optionally) `GOOGLE_GENAI_USE_VERTEXAI=1` into the image so
     * agent code can read them from `os.environ` without callers passing `--app-env` on every
     * deploy. Cloud Run's deploy-time `--set-env-vars` still overrides at container start.
     *
     * Lines with empty/blank values are omitted entirely (emitting `ENV FOO=` would actively clear
     * any inherited value, which is the opposite of "default").
     */
    fun buildGcpEnvBlock(project: String?, region: String?, useVertexAi: Boolean): String =
      buildList {
          project?.takeIf { it.isNotBlank() }?.let { add("ENV GOOGLE_CLOUD_PROJECT=$it") }
          region?.takeIf { it.isNotBlank() }?.let { add("ENV GOOGLE_CLOUD_LOCATION=$it") }
          if (useVertexAi) add("ENV GOOGLE_GENAI_USE_VERTEXAI=1")
        }
        .joinToString("\n")

    /** Renders [template] by substituting the three placeholders we support. */
    fun renderDockerfile(
      template: String,
      entryScript: String,
      serverPort: Int,
      gcpEnvBlock: String,
    ): String =
      template
        .replace("\${entryScript}", entryScript)
        .replace("\${serverPort}", serverPort.toString())
        .replace("\${gcpEnvBlock}", gcpEnvBlock)

    /**
     * Builds the `gcloud run deploy ...` argv (without invoking it).
     *
     * Externalized so unit tests can assert flag ordering, the `--quiet` toggle, comma-escaping of
     * `--args` / `--set-env-vars`, and the rule that user passthrough always lands last (so it can
     * override CLI defaults).
     */
    fun buildGcloudDeployArgs(args: GcloudDeployArgs): List<String> = buildList {
      add("gcloud")
      add("run")
      add("deploy")
      add(args.serviceName)
      add("--source")
      add(".")
      add("--region")
      add(args.region)
      add("--project")
      add(args.projectName)
      add("--ingress")
      add(args.ingress)
      add(
        if (args.allowUnauthenticated) "--allow-unauthenticated" else "--no-allow-unauthenticated"
      )
      add("--port")
      add(args.serverPort.toString())
      // Default to --quiet so the deploy doesn't hang on stdin in non-TTY contexts.
      // --interactive suppresses this for users running from a real terminal who want gcloud
      // prompts.
      if (!args.interactive) {
        add("--quiet")
      }
      // gcloud --args takes a comma-separated list; embedded commas in values are escaped by
      // doubling. The `--flag=value` form is required because app args often start with `--`
      // (e.g. `--greeting=Bonjour`) and gcloud would otherwise treat them as the next flag.
      if (args.appArgs.isNotEmpty()) {
        add("--args=" + args.appArgs.joinToString(",") { it.replace(",", ",,") })
      }
      // Same comma-doubling for --set-env-vars; `--flag=value` form keeps values with spaces
      // intact when handed to gcloud.
      if (args.appEnv.isNotEmpty()) {
        add("--set-env-vars=" + args.appEnv.joinToString(",") { it.replace(",", ",,") })
      }
      add("--update-labels=$CREATED_BY_LABEL")
      // User-supplied passthrough lands last so it can override any of the defaults above.
      addAll(args.gcloudExtras)
    }
  }
}

/** Outcome of looking for the single install image produced by Gradle's `application` plugin. */
sealed class InstallImageResult {
  data class Found(val path: Path) : InstallImageResult()

  /** The `build/install` directory itself wasn't produced (or isn't a directory). */
  data class MissingDir(val installRoot: Path) : InstallImageResult()

  /** `build/install` exists but contains no subdirectories. */
  data class None(val installRoot: Path) : InstallImageResult()

  /** `build/install` contains more than one candidate; we refuse to guess. */
  data class Multiple(val installRoot: Path, val candidates: List<String>) : InstallImageResult()
}

/** Outcome of looking for the launcher script under `app/bin/`. */
sealed class EntryScriptResult {
  data class Found(val name: String) : EntryScriptResult()

  data class MissingDir(val binDir: Path) : EntryScriptResult()

  data class None(val binDir: Path) : EntryScriptResult()

  data class Multiple(val binDir: Path, val candidates: List<String>) : EntryScriptResult()
}

/** Inputs to [CloudRunDeployCommand.Companion.buildGcloudDeployArgs]. */
data class GcloudDeployArgs(
  val serviceName: String,
  val region: String,
  val projectName: String,
  val ingress: String,
  val allowUnauthenticated: Boolean,
  val serverPort: Int,
  val interactive: Boolean,
  val appArgs: List<String>,
  val appEnv: List<String>,
  val gcloudExtras: List<String>,
)
