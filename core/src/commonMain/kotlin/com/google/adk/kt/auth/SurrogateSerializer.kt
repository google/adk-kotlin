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

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Returns a serializer that writes a [T] as its [surrogate] form. A failure to read the surrogate
 * reports only "Malformed auth data.", and a check that fails while building the [T] or a nested
 * value keeps its message; neither keeps a cause, which could quote secrets in the input. It covers
 * only errors inside the value; a caller that decodes a larger document must hide errors around it,
 * such as trailing text.
 */
internal fun <T, S> surrogateSerializer(
  surrogate: KSerializer<S>,
  toSurrogate: (T) -> S,
  fromSurrogate: (S) -> T,
): KSerializer<T> =
  object : KSerializer<T> {
    override val descriptor: SerialDescriptor = surrogate.descriptor

    override fun serialize(encoder: Encoder, value: T) {
      encoder.encodeSerializableValue(surrogate, toSurrogate(value))
    }

    override fun deserialize(decoder: Decoder): T {
      val decoded =
        try {
          decoder.decodeSerializableValue(surrogate)
        } catch (e: AuthDataValidationException) {
          // A nested value's failed check, whose message is already safe.
          throw e
        } catch (e: IllegalArgumentException) {
          // Includes SerializationException, whose message can quote the input.
          throw SerializationException("Malformed auth data.")
        }
      return try {
        fromSurrogate(decoded)
      } catch (e: IllegalArgumentException) {
        // These checks name no input, so their message is safe to keep.
        throw AuthDataValidationException(e.message)
      }
    }
  }

/** A check that decoded auth data failed; its message names no input. */
private class AuthDataValidationException(message: String?) : SerializationException(message)
