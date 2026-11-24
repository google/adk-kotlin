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

import com.google.adk.kt.auth.revealsSecrets
import com.google.adk.kt.auth.withDecodedSignInRequest
import com.google.adk.kt.auth.withDecodedSignInResponse
import kotlinx.serialization.KSerializer
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Reads and writes a [FunctionCall] with a sign-in request's arguments typed, as
 * [withDecodedSignInRequest] returns them, so that output leaves their secrets out.
 */
internal object FunctionCallSerializer :
  KSerializer<FunctionCall> by functionSerializer(
    FunctionCall.serializer(),
    FunctionCall::withDecodedSignInRequest,
  )

/**
 * Reads and writes a [FunctionResponse] with a sign-in response's body typed, as
 * [withDecodedSignInResponse] returns it, so that output leaves its secrets out.
 */
internal object FunctionResponseSerializer :
  KSerializer<FunctionResponse> by functionSerializer(
    FunctionResponse.serializer(),
    FunctionResponse::withDecodedSignInResponse,
  )

/**
 * Returns a serializer that delegates to [serializer], reading each value through [decode]. Unless
 * the encoder reveals secrets, it writes each value through [decode] too, so a sign-in call or
 * response built with plain values is written without its secrets.
 */
private fun <T> functionSerializer(serializer: KSerializer<T>, decode: (T) -> T): KSerializer<T> =
  object : KSerializer<T> by serializer {
    override fun serialize(encoder: Encoder, value: T) {
      // Storage keeps the value as built, even a malformed one; reading it back decodes it.
      serializer.serialize(encoder, if (encoder.revealsSecrets()) value else decode(value))
    }

    override fun deserialize(decoder: Decoder): T = decode(serializer.deserialize(decoder))
  }
