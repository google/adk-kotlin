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
import kotlin.jvm.JvmStatic

/**
 * Represents the frontmatter of a skill, containing metadata about the skill.
 *
 * Validation is enforced at construction time: any attempt to instantiate a `Frontmatter` with
 * fields that violate the specification (e.g. invalid name characters, oversized description) will
 * fail with [IllegalArgumentException]. As a result, every `Frontmatter` instance is guaranteed to
 * be well-formed.
 *
 * @property name The name of the skill.
 * @property description A description of the skill.
 * @property license The license of the skill.
 * @property compatibility The compatibility of the skill.
 * @property allowedTools The tools that are allowed to be used by the skill.
 * @property metadata Additional metadata about the skill.
 * @throws IllegalArgumentException if any field violates the frontmatter specification.
 */
data class Frontmatter(
  val name: String,
  val description: String,
  val license: String? = null,
  val compatibility: String? = null,
  val allowedTools: String? = null,
  val metadata: Map<String, Any?> = emptyMap(),
) {
  init {
    require(name.isNotEmpty() && name.length <= 64) {
      "name must be between 1 and 64 characters long"
    }
    require(!name.startsWith("-") && !name.endsWith("-")) {
      "name must not start or end with a hyphen"
    }
    require(!name.contains("--")) { "name must not contain consecutive hyphens" }
    require(name.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }) {
      "name may only contain lowercase alphanumeric characters (a-z, 0-9) and hyphens"
    }
    require(description.isNotEmpty() && description.length <= 1024) {
      "description must be between 1 and 1024 characters long"
    }
    require(compatibility == null || compatibility.length <= 500) {
      "compatibility must not exceed 500 characters"
    }
  }

  /**
   * Fluent builder for [Frontmatter], provided primarily for Java callers. Any property left unset
   * falls back to the same default as the constructor.
   */
  @AdkJavaInteropApi
  @Suppress("ScopeReceiverThis") // Java-style builder for Java interop.
  class Builder {
    private var name: String? = null
    private var description: String? = null
    private var license: String? = null
    private var compatibility: String? = null
    private var allowedTools: String? = null
    private var metadata: Map<String, Any?> = emptyMap()

    fun name(name: String): Builder = apply { this.name = name }

    fun description(description: String): Builder = apply { this.description = description }

    fun license(license: String?): Builder = apply { this.license = license }

    fun compatibility(compatibility: String?): Builder = apply {
      this.compatibility = compatibility
    }

    fun allowedTools(allowedTools: String?): Builder = apply { this.allowedTools = allowedTools }

    fun metadata(metadata: Map<String, Any?>): Builder = apply { this.metadata = metadata }

    fun build(): Frontmatter =
      Frontmatter(
        name = checkNotNull(name) { "Frontmatter.Builder requires name to be set." },
        description =
          checkNotNull(description) { "Frontmatter.Builder requires description to be set." },
        license = license,
        compatibility = compatibility,
        allowedTools = allowedTools,
        metadata = metadata,
      )
  }

  companion object {
    @AdkJavaInteropApi @JvmStatic fun builder(): Builder = Builder()
  }
}
