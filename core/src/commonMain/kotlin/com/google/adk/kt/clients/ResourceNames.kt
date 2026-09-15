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

package com.google.adk.kt.clients

import com.google.errorprone.annotations.CanIgnoreReturnValue

/**
 * Allowed characters for a project, location, or corpus id, keeping each within a single URL path
 * segment (no `/`, `?`, `#`, or `..`).
 */
internal val RESOURCE_SEGMENT_PATTERN = Regex("^[a-zA-Z0-9_-]+$")

/** Requires [value] to match [RESOURCE_SEGMENT_PATTERN] before it goes into a URL path. */
@CanIgnoreReturnValue
internal fun validateSegment(value: String, label: String): String {
  require(RESOURCE_SEGMENT_PATTERN.matches(value)) {
    "Invalid $label: it must match ${RESOURCE_SEGMENT_PATTERN.pattern}."
  }
  return value
}
