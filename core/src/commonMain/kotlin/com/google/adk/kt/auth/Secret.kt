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

package com.google.adk.kt.auth

import com.google.adk.kt.annotations.ExperimentalAuthApi
import com.google.adk.kt.annotations.SecretAccess
import kotlin.jvm.Transient
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * A secret string, such as a token or a client secret, that prints as a placeholder.
 *
 * [toString] never shows the value, and reading it requires opting in to [SecretAccess]. On the JVM
 * the value is a `transient` field, so reflection-based serializers skip it.
 */
@ExperimentalAuthApi
class Secret(@Transient private val value: String) {
  /** Returns the secret value. */
  @SecretAccess fun reveal(): String = value

  override fun equals(other: Any?): Boolean = other is Secret && other.value == value

  override fun hashCode(): Int = value.hashCode()

  override fun toString(): String = "<redacted>"
}

/**
 * Writes a [Secret] as its plain string value, as the credential JSON format requires. Internal so
 * that only ADK's own serializers reveal a secret without opting in to [SecretAccess].
 */
@OptIn(ExperimentalAuthApi::class, SecretAccess::class)
internal object SecretSerializer : KSerializer<Secret> {
  override val descriptor: SerialDescriptor =
    PrimitiveSerialDescriptor("com.google.adk.kt.auth.Secret", PrimitiveKind.STRING)

  override fun serialize(encoder: Encoder, value: Secret) {
    encoder.encodeString(value.reveal())
  }

  override fun deserialize(decoder: Decoder): Secret = Secret(decoder.decodeString())
}
