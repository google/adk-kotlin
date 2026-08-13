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

import kotlin.jvm.JvmStatic
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * Schema is used to define the format of input/output data.
 *
 * Represents a select subset of an
 * [OpenAPI 3.0 schema object](https://spec.openapis.org/oas/v3.0.3#schema-object).
 *
 * @property type Data type of the schema.
 * @property properties Describes the properties of an object. The keys are property names and
 *   values are schemas for corresponding properties. Applicable only if `type` is [Type.OBJECT].
 * @property items Describes the schema of items in an array. Applicable only if `type` is
 *   [Type.ARRAY].
 * @property required A list of required property names. Applicable only if `type` is [Type.OBJECT].
 * @property description A human-readable description of the schema.
 * @property enum Restricts a value to a fixed set of values.
 * @property format The format of the data, refining [type]. The field is free-form, but Gemini
 *   accepts only `int32` or `int64` for [Type.INTEGER] and [Type.NUMBER], and `enum` or `date-time`
 *   for [Type.STRING]; it rejects a declaration carrying any other value.
 * @property nullable Whether the value may be null.
 * @property default The value to assume when the property is absent. Must be JSON-native -- a
 *   `String`, number, `Boolean`, `Map` or `List` -- since anything else fails to serialize.
 *   Serializing a [Schema] that sets this needs a `Json` whose `serializersModule` carries a
 *   contextual serializer for `Any`; a plain `Json` throws. JSON also has a single number type, so
 *   a numeric default that crosses a JSON boundary comes back as whatever Kotlin type the reader
 *   picks, not necessarily the one it was written as.
 * @property anyOf The value must validate against at least one of these subschemas.
 * @property title A title for the schema.
 * @property pattern A regular expression the value must match. Applicable only if `type` is
 *   [Type.STRING].
 * @property minimum The smallest allowed value. Applicable only if `type` is [Type.INTEGER] or
 *   [Type.NUMBER].
 * @property maximum The largest allowed value. Applicable only if `type` is [Type.INTEGER] or
 *   [Type.NUMBER].
 * @property minLength The shortest allowed string. Applicable only if `type` is [Type.STRING].
 * @property maxLength The longest allowed string. Applicable only if `type` is [Type.STRING].
 * @property minItems The fewest allowed items. Applicable only if `type` is [Type.ARRAY].
 * @property maxItems The most allowed items. Applicable only if `type` is [Type.ARRAY].
 */
@Serializable
data class Schema(
  val type: Type? = null,
  val properties: Map<String, Schema>? = null,
  val items: Schema? = null,
  val required: List<String>? = null,
  val description: String? = null,
  val enum: List<String>? = null,
  val format: String? = null,
  val nullable: Boolean? = null,
  val default: @Contextual Any? = null,
  val anyOf: List<Schema>? = null,
  val title: String? = null,
  val pattern: String? = null,
  val minimum: Double? = null,
  val maximum: Double? = null,
  val minLength: Long? = null,
  val maxLength: Long? = null,
  val minItems: Long? = null,
  val maxItems: Long? = null,
) {
  /**
   * Fluent builder for [Schema], provided primarily for Java callers. Any property left unset falls
   * back to the same default as the constructor.
   */
  @Suppress("ScopeReceiverThis") // Java-style builder for Java interop.
  class Builder {
    private var type: Type? = null
    private var properties: Map<String, Schema>? = null
    private var items: Schema? = null
    private var required: List<String>? = null
    private var description: String? = null
    private var enum: List<String>? = null

    fun type(type: Type?): Builder = apply { this.type = type }

    fun properties(properties: Map<String, Schema>?): Builder = apply {
      this.properties = properties
    }

    fun items(items: Schema?): Builder = apply { this.items = items }

    fun required(required: List<String>?): Builder = apply { this.required = required }

    fun required(vararg required: String): Builder = apply { this.required = required.toList() }

    fun description(description: String?): Builder = apply { this.description = description }

    fun enum(enum: List<String>?): Builder = apply { this.enum = enum }

    fun enum(vararg enum: String): Builder = apply { this.enum = enum.toList() }

    /** Sets [enum]. Java callers need this because `enum` is a reserved word in Java. */
    fun enumValues(enumValues: List<String>?): Builder = apply { this.enum = enumValues }

    /** Sets [enum]. Java callers need this because `enum` is a reserved word in Java. */
    fun enumValues(vararg enumValues: String): Builder = apply { this.enum = enumValues.toList() }

    fun build(): Schema =
      Schema(
        type = type,
        properties = properties,
        items = items,
        required = required,
        description = description,
        enum = enum,
      )
  }

  companion object {
    @JvmStatic fun builder(): Builder = Builder()
  }
}
