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

package com.google.adk.kt

import com.google.adk.kt.logging.LoggerFactory
import com.google.adk.kt.platform.getEnv
import kotlinx.atomicfu.atomic

/**
 * Reads boolean flags out of the process environment, by the one rule the whole SDK shares.
 *
 * A variable is enabled when its value is `"true"` in any case, or `"1"`; every other value, and a
 * variable that is not set, is disabled. Mirrors `utils/env_utils.py` in the Python ADK.
 */
object EnvUtils {

  /** The variable that says which Google backend the SDK talks to. */
  private const val ENTERPRISE_ENV_VAR = "GOOGLE_GENAI_USE_ENTERPRISE"

  /** The former spelling of [ENTERPRISE_ENV_VAR], which most ADK setup instructions still name. */
  private const val DEPRECATED_ENTERPRISE_ENV_VAR = "GOOGLE_GENAI_USE_VERTEXAI"

  private val logger = LoggerFactory.getLogger(EnvUtils::class)

  /** Guards the deprecation warning so a repeated read does not repeat it. */
  private val warnedAboutDeprecatedEnterpriseVar = atomic(false)

  /** Returns whether the environment variable [envVarName] is set to an enabled value. */
  fun isEnvEnabled(envVarName: String): Boolean = isValueEnabled(getEnv(envVarName))

  /**
   * Returns whether Google GenAI enterprise mode is enabled.
   *
   * [ENTERPRISE_ENV_VAR] decides it whenever it is set, including when it says off, so a deployment
   * that has moved to the current variable is not dragged back to the enterprise backend by a stale
   * [DEPRECATED_ENTERPRISE_ENV_VAR] still sitting in its environment. The deprecated variable is
   * consulted only when the current one is unset, and reading it warns once.
   */
  fun isEnterpriseModeEnabled(): Boolean =
    isEnterpriseModeEnabled(getEnv(ENTERPRISE_ENV_VAR), getEnv(DEPRECATED_ENTERPRISE_ENV_VAR))

  /**
   * Returns whether enterprise mode is enabled, given the raw value of the current and of the
   * deprecated variable, either of which is null when that variable is not set.
   *
   * Split from the lookup so every row of the rule is testable: a process cannot put a variable
   * into its own environment.
   */
  internal fun isEnterpriseModeEnabled(
    enterpriseValue: String?,
    deprecatedValue: String?,
  ): Boolean {
    if (enterpriseValue != null) {
      return isValueEnabled(enterpriseValue)
    }
    if (deprecatedValue != null) {
      if (warnedAboutDeprecatedEnterpriseVar.compareAndSet(false, true)) {
        logger.warn {
          "$DEPRECATED_ENTERPRISE_ENV_VAR is deprecated, please use $ENTERPRISE_ENV_VAR instead"
        }
      }
      return isValueEnabled(deprecatedValue)
    }
    return false
  }

  /**
   * Returns whether [value], as read from an environment variable, is enabled, where null means the
   * variable is not set.
   *
   * The value is compared as it was given rather than trimmed first, so `" true "` is not enabled,
   * which is what the Python and Java ADKs answer for it.
   */
  internal fun isValueEnabled(value: String?): Boolean =
    value == "1" || value.equals("true", ignoreCase = true)
}
