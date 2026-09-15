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

package com.google.adk.kt.types

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

/**
 * Represents one of the possible values within a [PartialArg].
 *
 * This sealed interface ensures that only one value type (boolean, number, string, or null) can be
 * represented at a time.
 */
@Serializable
sealed interface PartialArgValue {
  /** Represents a boolean value. */
  @Serializable data class BoolValue(val value: Boolean) : PartialArgValue

  /** Represents a double value. */
  @Serializable data class NumberValue(val value: Double) : PartialArgValue

  /** Represents a string value. */
  @Serializable data class StringValue(val value: String) : PartialArgValue

  /** Represents a null value. */
  @Serializable object NullValue : PartialArgValue
}

/**
 * Partial argument value of the function call.
 *
 * The value of the partial argument is represented by [value], which uses [PartialArgValue] to
 * ensure that only one of boolean, number, string, or null is represented at a time.
 *
 * @property value Holds the primitive value of this partial argument, if any.
 * @property jsonPath A JSON Path (RFC 9535) to the argument being streamed.
 * @property willContinue Whether this is not the last part of the same json_path.
 */
@Serializable
data class PartialArg(
  val value: PartialArgValue? = null,
  @JsonNames("json_path") val jsonPath: String? = null,
  @JsonNames("will_continue") val willContinue: Boolean? = null,
) {
  /**
   * Represents a boolean value, if this partial argument is of boolean type, null otherwise.
   *
   * @see PartialArgValue.BoolValue
   */
  val boolValue: Boolean?
    get() = (value as? PartialArgValue.BoolValue)?.value

  /**
   * Represents a double value, if this partial argument is of number type, null otherwise.
   *
   * @see PartialArgValue.NumberValue
   */
  val numberValue: Double?
    get() = (value as? PartialArgValue.NumberValue)?.value

  /**
   * Represents a string value, if this partial argument is of string type, null otherwise.
   *
   * @see PartialArgValue.StringValue
   */
  val stringValue: String?
    get() = (value as? PartialArgValue.StringValue)?.value

  /**
   * Is true if this partial argument represents a null value, null otherwise.
   *
   * @see PartialArgValue.NullValue
   */
  val nullValue: Boolean?
    get() = if (value is PartialArgValue.NullValue) true else null
}
