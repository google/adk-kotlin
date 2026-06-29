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

package com.google.adk.kt.examples.github.adkreleasedocs

/** Configuration sourced from environment variables. */
object Settings {

  /** GitHub token with `issues:write` on the docs repository. */
  val githubToken: String? = System.getenv("GITHUB_TOKEN")

  val docOwner: String = envOrDefault("DOC_OWNER", "google")
  val codeOwner: String = envOrDefault("CODE_OWNER", "google")
  val docRepo: String = envOrDefault("DOC_REPO", "adk-docs")
  val codeRepo: String = envOrDefault("CODE_REPO", "adk-kotlin")

  /** Implementation language documented by this analyzer; docs stay single-language. */
  val codeLanguage: String = envOrDefault("CODE_LANGUAGE", "Kotlin")

  /** Only changes under this path in the code repo are analyzed. */
  val codeSourcePathFilter: String = envOrDefault("CODE_SOURCE_PATH_FILTER", "core/src/")

  val model: String = envOrDefault("MODEL", "gemini-pro-latest")

  private fun envOrDefault(name: String, fallback: String): String {
    val value = System.getenv(name)
    return if (value.isNullOrEmpty()) fallback else value
  }
}
