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

@file:OptIn(ExperimentalAuthApi::class, FrameworkInternalApi::class)

package com.google.adk.kt.auth

import com.google.adk.kt.annotations.ExperimentalAuthApi
import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.types.FunctionCall
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.elementDescriptors
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Returns this JSON without credential secrets, which serialize as plain text, so traces, logs and
 * web responses can't leak them: each credential, each auth config that holds one, and the auth
 * config of each `adk_request_credential` call or reply keeps only the fields its serializer
 * declares, minus those typed as a [Secret]. Values outside those parts are kept, even under a
 * secret's key name.
 */
@FrameworkInternalApi
fun JsonElement.withoutCredentialSecrets(): JsonElement =
  when (this) {
    is JsonObject ->
      when {
        isCredential() -> redactedAs(AUTH_CREDENTIAL)
        this[FunctionCall.NAME_KEY] == REQUEST_CREDENTIAL_NAME -> credentialCallWithoutSecrets()
        isAuthConfig() -> redactedAs(AUTH_CONFIG)
        else -> JsonObject(mapValues { it.value.withoutCredentialSecrets() })
      }
    is JsonArray -> JsonArray(map { it.withoutCredentialSecrets() })
    is JsonPrimitive -> this
  }

/**
 * Returns whether this object is a credential: its auth type names a credential type, in any case
 * and with or without underscores.
 */
private fun JsonObject.isCredential(): Boolean = any { (key, value) ->
  val type = AUTH_CREDENTIAL.fieldType(key)
  type != null &&
    type.kind == SerialKind.ENUM &&
    value is JsonPrimitive &&
    value.isString &&
    type.elementNames.any { it.normalized() == value.content.normalized() }
}

/** Returns whether this object is an auth config: it has a config field that can hold a secret. */
private fun JsonObject.isAuthConfig(): Boolean = keys.any {
  AUTH_CONFIG.fieldType(it)?.canHoldSecret() == true
}

/** Returns this `adk_request_credential` call or response without its auth config's secrets. */
private fun JsonObject.credentialCallWithoutSecrets(): JsonObject =
  JsonObject(
    mapValues { (key, value) ->
      when (key) {
        // The auth config is found by name, so one without a credential still loses its secrets.
        FunctionCall.ARGS_KEY ->
          if (value is JsonObject) value.redactedAs(REQUEST_CREDENTIAL_ARGS, keepUndeclared = true)
          else value.withoutCredentialSecrets()
        // Only declared fields stay, so a token in `result` or a non-object reply is dropped.
        RESPONSE_KEY ->
          if (value is JsonObject)
            value.redactedAs(if (value.isCredential()) AUTH_CREDENTIAL else AUTH_CONFIG)
          else JsonObject(emptyMap())
        else -> value.withoutCredentialSecrets()
      }
    }
  )

/**
 * Returns this object with only the fields [type] declares, without those that hold a secret. An
 * undeclared field is dropped, or walked like any other JSON if [keepUndeclared].
 */
private fun JsonObject.redactedAs(
  type: SerialDescriptor,
  keepUndeclared: Boolean = false,
): JsonObject =
  JsonObject(
    mapNotNull { (key, value) ->
        val fieldType = type.fieldType(key)
        val redacted =
          when {
            fieldType != null -> value.redactedAsOrNull(fieldType)
            keepUndeclared -> value.withoutCredentialSecrets()
            else -> null
          }
        redacted?.let { key to it }
      }
      .toMap()
  )

/**
 * Returns this value without the secrets [type] declares, or null if it is a secret or isn't the
 * shape [type] declares. A structured value whose type holds no secret, such as a scheme, is walked
 * like any other JSON.
 */
private fun JsonElement.redactedAsOrNull(type: SerialDescriptor): JsonElement? =
  when {
    type.canHoldSecret() ->
      if (this is JsonObject && type.kind == StructureKind.CLASS) redactedAs(type) else null
    type.kind is PrimitiveKind || type.kind == SerialKind.ENUM -> this as? JsonPrimitive
    type.kind == StructureKind.LIST ->
      (this as? JsonArray)?.let { list ->
        JsonArray(list.mapNotNull { it.redactedAsOrNull(type.getElementDescriptor(0)) })
      }
    else -> withoutCredentialSecrets()
  }

/** Returns the type of the field that [key] names, by its name or a `@JsonNames` alias, or null. */
private fun SerialDescriptor.fieldType(key: String): SerialDescriptor? =
  (0 until elementsCount)
    .firstOrNull { index ->
      getElementName(index) == key ||
        getElementAnnotations(index).any { it is JsonNames && key in it.names }
    }
    ?.let(::getElementDescriptor)

/** Returns whether a secret occurs anywhere in this type. */
private fun SerialDescriptor.canHoldSecret(
  seen: MutableSet<SerialDescriptor> = mutableSetOf()
): Boolean =
  this in SECRET_TYPES || (seen.add(this) && elementDescriptors.any { it.canHoldSecret(seen) })

private fun String.normalized(): String = replace("_", "").lowercase()

private val REQUEST_CREDENTIAL_NAME: JsonPrimitive =
  JsonPrimitive(FunctionCall.REQUEST_EUC_FUNCTION_CALL_NAME)
private val REQUEST_CREDENTIAL_ARGS: SerialDescriptor =
  RequestCredentialArgs.serializer().descriptor
private const val RESPONSE_KEY = "response"

private val AUTH_CREDENTIAL: SerialDescriptor = AuthCredentialSerializer.descriptor
private val AUTH_CONFIG: SerialDescriptor = AuthConfigSerializer.descriptor

// A nullable field's type wraps the secret's.
private val SECRET_TYPES: Set<SerialDescriptor> =
  setOf(SecretSerializer.descriptor, SecretSerializer.nullable.descriptor)
