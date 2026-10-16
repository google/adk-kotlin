/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.adk.kt.webserver.routes

private val TRUE_QUERY_VALUES = setOf("1", "on", "t", "true", "y", "yes")

private val FALSE_QUERY_VALUES = setOf("0", "off", "f", "false", "n", "no")

/** Parses [value] as a case-insensitive boolean query parameter, or returns null if invalid. */
internal fun queryBooleanOrNull(value: String): Boolean? =
  when (value.lowercase()) {
    in TRUE_QUERY_VALUES -> true
    in FALSE_QUERY_VALUES -> false
    else -> null
  }
