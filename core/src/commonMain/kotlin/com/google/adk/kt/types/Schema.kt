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
 */
@Serializable
data class Schema(
  val type: Type? = null,
  val properties: Map<String, Schema>? = null,
  val items: Schema? = null,
  val required: List<String>? = null,
  val description: String? = null,
  val enum: List<String>? = null,
)
