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

package com.google.adk.kt.cli.builtinagents

import java.io.File

/**
 * The session-state key naming the project directory a conversation is about.
 *
 * None of the assistant's tools takes a directory argument, because the dev server knows which
 * project the user opened and the model does not, so the directory travels in session state
 * instead. The key keeps adk-python's spelling, since one session's state can be written by one SDK
 * and read by another.
 */
internal const val ROOT_DIRECTORY_KEY: String = "root_directory"

/** Characters a model wraps a path in that are not part of the path. */
private val BOUNDARY_CHARACTERS = charArrayOf(' ', '\t', '\r', '\n', '\'', '"', '`')

/**
 * The project directory named in [state], resolved.
 *
 * Falls back to the working directory when the state names none, which is the directory a user gets
 * who started the dev server inside the project they mean. Canonicalising stats the filesystem, so
 * callers run this off the caller's thread.
 */
internal fun projectRoot(state: Map<String, Any>): File {
  val named = state[ROOT_DIRECTORY_KEY]?.toString()?.trim().orEmpty()
  return File(named.ifEmpty { "." }).canonicalFile
}

/**
 * [path] resolved against the project directory named in [state].
 *
 * The model supplies these paths, so one that climbs out of the project with `..`, or names an
 * absolute path somewhere else entirely, is an ordinary thing to receive rather than an exotic one.
 * It is refused here, in the one place every tool resolves a path, so no tool has to remember to
 * check.
 *
 * @throws IllegalArgumentException if [path] resolves outside the project directory.
 */
internal fun resolveInProject(path: String, state: Map<String, Any>): File {
  val root = projectRoot(state)
  val cleaned = sanitizePath(path)
  val requested = File(cleaned)
  val candidate = (if (requested.isAbsolute) requested else File(root, cleaned)).canonicalFile
  require(candidate == root || candidate.path.startsWith(root.path + File.separator)) {
    "File path '$path' resolves outside the project directory $root."
  }
  return candidate
}

/**
 * [path] with the quotes and spaces a model puts around it stripped, segment by segment.
 *
 * A model writes `'tools/my_tool.kt'` often enough that taking it literally leaves the user with a
 * directory called `'tools`. Characters inside a segment are left alone.
 */
internal fun sanitizePath(path: String): String {
  val trimmed = path.trim()
  if (trimmed.isEmpty()) return trimmed

  val cleaned = StringBuilder()
  val segment = StringBuilder()
  for (character in trimmed) {
    if (character == '/' || character == '\\') {
      cleaned.append(segment.trim(*BOUNDARY_CHARACTERS)).append(character)
      segment.setLength(0)
    } else {
      segment.append(character)
    }
  }
  cleaned.append(segment.trim(*BOUNDARY_CHARACTERS))
  return cleaned.trim(*BOUNDARY_CHARACTERS).toString().ifEmpty { trimmed }
}

/** The strings the model sent under [name], or an empty list when it sent none. */
internal fun Map<String, Any?>.stringList(name: String): List<String> =
  (this[name] as? Collection<*>)?.mapNotNull { it?.toString() } ?: emptyList()

/** The flag the model sent under [name], or [default] when it sent none. */
internal fun Map<String, Any?>.flag(name: String, default: Boolean): Boolean =
  when (val value = this[name]) {
    is Boolean -> value
    is String -> value.toBooleanStrictOrNull() ?: default
    else -> default
  }

/** [pattern], a glob of the `*.kt` kind, as the expression that matches a file name against it. */
internal fun globRegex(pattern: String): Regex =
  Regex(
    pattern
      .map { character ->
        when (character) {
          '*' -> ".*"
          '?' -> "."
          else -> Regex.escape(character.toString())
        }
      }
      .joinToString("")
  )
