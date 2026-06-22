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

package com.google.adk.kt.artifacts

import android.content.Context
import java.io.File

/**
 * Builds a [FileArtifactService] rooted at `<app-specific external files dir>/<subDir>`.
 *
 * This is the recommended factory for artifacts: app-specific external storage
 * ([Context.getExternalFilesDir]) persists until the app is uninstalled and is not swept by the
 * system cache cleaner, and needs no runtime permission. It is still app-private.
 *
 * External storage can be unavailable (e.g. unmounted), in which case [Context.getExternalFilesDir]
 * returns `null` and this throws [IllegalStateException]. The location is chosen explicitly rather
 * than silently falling back, so artifacts always live at a stable path; callers that want internal
 * storage should use [fromInternalFilesDir], and callers that want their own fallback can write it
 * explicitly, e.g. `runCatching { fromExternalFilesDir(ctx) }.getOrElse { fromInternalFilesDir(ctx)
 * }`.
 *
 * Multiple agents in the same app can isolate their artifacts by passing a distinct [subDir]. Uses
 * [Context.getApplicationContext] internally to avoid leaking an `Activity`.
 *
 * @throws IllegalStateException if external storage is currently unavailable.
 */
fun FileArtifactService.Companion.fromExternalFilesDir(
  context: Context,
  subDir: String = FileArtifactService.DEFAULT_ARTIFACTS_SUBDIR,
): FileArtifactService {
  val root =
    checkNotNull(context.applicationContext.getExternalFilesDir(null)) {
      "External storage is unavailable; use fromInternalFilesDir(context) or pass an explicit path."
    }
  return FileArtifactService(File(root, subDir).path)
}

/**
 * Builds a [FileArtifactService] rooted at `<internal files dir>/<subDir>` ([Context.getFilesDir]).
 *
 * Internal storage is always available and app-private. Prefer [fromExternalFilesDir] for artifacts
 * (user content); use this when you specifically want internal storage.
 *
 * Multiple agents in the same app can isolate their artifacts by passing a distinct [subDir]. Uses
 * [Context.getApplicationContext] internally to avoid leaking an `Activity`.
 */
fun FileArtifactService.Companion.fromInternalFilesDir(
  context: Context,
  subDir: String = FileArtifactService.DEFAULT_ARTIFACTS_SUBDIR,
): FileArtifactService = FileArtifactService(File(context.applicationContext.filesDir, subDir).path)
