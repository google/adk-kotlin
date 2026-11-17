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

import com.google.adk.kt.auth.withDecodedSignInRequest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.encoding.Decoder

/**
 * Reads a [FunctionCall] with a sign-in request's arguments typed, as [withDecodedSignInRequest]
 * returns them, so that a call read back from JSON leaves its secrets out of output.
 */
internal object FunctionCallSerializer :
  KSerializer<FunctionCall> by functionSerializer(
    FunctionCall.serializer(),
    FunctionCall::withDecodedSignInRequest,
  )

/** Returns a serializer that delegates to [serializer], reading each value through [decode]. */
private fun <T> functionSerializer(serializer: KSerializer<T>, decode: (T) -> T): KSerializer<T> =
  object : KSerializer<T> by serializer {
    override fun deserialize(decoder: Decoder): T = decode(serializer.deserialize(decoder))
  }
