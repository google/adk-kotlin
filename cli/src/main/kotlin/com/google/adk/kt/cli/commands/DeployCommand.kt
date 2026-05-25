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
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands
import com.google.adk.kt.cli.commands.deploy.CloudRunDeployCommand

/**
 * Parent `deploy` command that dispatches to a deployment-target subcommand.
 *
 * Currently only `cloud_run` is registered. Additional targets (e.g. `gke`, `compute_engine`) can
 * be added as siblings under `com.google.adk.kt.cli.commands.deploy` and wired in here.
 */
class DeployCommand : CliktCommand(name = "deploy") {
  init {
    subcommands(CloudRunDeployCommand())
  }

  override fun help(context: Context): String =
    "Deploys an ADK agent application to a chosen target. Pick a deploy target as a subcommand."

  override fun run() {
    // No-op; clikt invokes the chosen subcommand automatically.
  }
}
