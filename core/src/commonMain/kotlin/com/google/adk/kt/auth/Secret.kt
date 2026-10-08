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

  override fun toString(): String = REDACTED
}

/** The placeholder that [Secret], [redacted] and [redactedUrl] print in place of a value. */
private const val REDACTED = "<redacted>"

/** Returns [REDACTED] if this value is set, showing that it is set but not what it is. */
internal fun String?.redacted(): String? = this?.let { REDACTED }

/**
 * Returns this URL with its user info and the values of its query and fragment parameters replaced
 * by [REDACTED]. The scheme, host, path and parameter names stay, so the URL remains recognizable
 * while the state, codes and tokens it may carry are hidden.
 */
internal fun String?.redactedUrl(): String? {
  if (this == null) return null
  // Every part of the pattern is optional, so it matches any string; the fallback hides it all.
  val parts = URI_PARTS.matchEntire(this) ?: return redacted()
  val (scheme, authority, path, query, fragment) = parts.destructured
  return scheme +
    authority.withRedactedUserInfo() +
    path +
    query.withRedactedParameters() +
    fragment.withRedactedParameters()
}

// RFC 3986 appendix B's parser; each part keeps its delimiter, and a fragment may span lines.
private val URI_PARTS = Regex("""([^:/?#]+:)?(//[^/?#]*)?([^?#]*)(\?[^#]*)?(#[\s\S]*)?""")

/** Replaces the `user:password` part of an authority such as `//user:password@host`. */
private fun String.withRedactedUserInfo(): String =
  if ('@' in this) "//" + REDACTED + substring(lastIndexOf('@')) else this

/** Keeps the leading `?` or `#` and each parameter name, and replaces each value. */
private fun String.withRedactedParameters(): String {
  if (isEmpty()) return this
  return take(1) +
    drop(1).split('&').joinToString("&") { parameter ->
      when {
        parameter.isEmpty() -> parameter
        '=' in parameter -> parameter.substringBefore('=') + "=" + REDACTED
        // Without `=` the text may itself be a value, so none of it is kept.
        else -> REDACTED
      }
    }
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
