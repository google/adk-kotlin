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
package com.google.adk.kt.processors

import com.google.adk.kt.agents.CallbackContext
import com.google.adk.kt.serialization.Json
import com.google.adk.kt.sessions.State

/** Helper to inject session state into instructions. */
internal object InstructionStateInjector {

  private val INSTRUCTION_PLACEHOLDER_PATTERN = Regex("""\{+[^\{\}]*\}+""")
  private val VALID_PREFIXES = setOf(State.APP_PREFIX, State.USER_PREFIX, State.TEMP_PREFIX)

  /**
   * Injects session state into the given instruction template.
   *
   * @param context The invocation context.
   * @param template The instruction template string.
   * @return The populated instruction string.
   */
  suspend fun injectSessionState(context: CallbackContext, template: String?): String {
    if (template.isNullOrEmpty()) return ""

    return buildString {
      var lastMatchEnd = 0

      for (match in INSTRUCTION_PLACEHOLDER_PATTERN.findAll(template)) {
        // 1. Append the text before the match
        append(template, lastMatchEnd, match.range.first)

        // 2. Resolve and append the dynamic value
        append(resolveMatch(context, match.value))

        lastMatchEnd = match.range.last + 1
      }

      // 3. Append whatever is left at the end of the template
      append(template, lastMatchEnd, template.length)
    }
  }

  private suspend fun resolveMatch(context: CallbackContext, placeholder: String): String {
    val varNameFromPlaceholder = placeholder.trim('{', '}').trim()
    val optional = varNameFromPlaceholder.endsWith("?")
    val varName = if (optional) varNameFromPlaceholder.dropLast(1) else varNameFromPlaceholder

    val (result, notFoundMessage) =
      when {
        varName.startsWith("artifact.") -> {
          val artifactName = varName.substringAfter("artifact.")
          val artifactJson =
            context.artifactService?.loadArtifact(context.session.key, artifactName)?.let {
              Json.toJsonString(it)
            }
          artifactJson to "Artifact $artifactName not found."
        }
        !isValidStateName(varName) -> return placeholder
        else -> {
          val stateValue =
            if (context.session.state.containsKey(varName)) {
              // Vertex AI sessions return a stored null as State.REMOVED.
              context.session.state[varName]?.takeUnless { it === State.REMOVED }?.toString() ?: ""
            } else {
              null
            }
          stateValue to "Context variable not found: `$varName`."
        }
      }

    return result ?: if (optional) "" else throw IllegalArgumentException(notFoundMessage)
  }

  /** Checks if a given string is a valid state variable name. */
  private fun isValidStateName(varName: String): Boolean {
    val colonIndex = varName.indexOf(':')
    if (colonIndex == -1) return isValidIdentifier(varName)

    val prefix = varName.substring(0, colonIndex + 1)
    val name = varName.substring(colonIndex + 1)
    return prefix in VALID_PREFIXES && isValidIdentifier(name)
  }

  /**
   * Checks if a given string is a valid identifier.
   *
   * Valid identifiers must start with a letter or an underscore. Subsequent characters may be
   * letters, digits, or underscores.
   */
  private fun isValidIdentifier(s: String): Boolean {
    val first = s.firstOrNull() ?: return false
    if (!first.isLetter() && first != '_') return false
    return s.all { it.isLetterOrDigit() || it == '_' }
  }
}
