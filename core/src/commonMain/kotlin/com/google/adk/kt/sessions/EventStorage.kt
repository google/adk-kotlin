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

package com.google.adk.kt.sessions

import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.auth.isSignInRequest
import com.google.adk.kt.auth.isSignInResponse
import com.google.adk.kt.collections.concurrentMutableMapOf
import com.google.adk.kt.events.Event
import com.google.adk.kt.events.EventActions
import com.google.adk.kt.serialization.adkJson
import com.google.adk.kt.serialization.anyToJsonElement
import com.google.adk.kt.serialization.jsonElementToAny
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Part

/**
 * Returns this event as a session store writes it, with typed credentials keeping their secrets
 * only in its sign-in parts, state delta, output and requested auth configs, which ADK reads back.
 * Elsewhere they lose them, since a reloaded value is plain and would show them to the model.
 */
internal fun Event.forStorage(): Event =
  copy(
    content = content?.forStorage(),
    customMetadata = customMetadata?.mapValues { (_, value) -> value?.withoutCredentialSecrets() },
    actions = actions.forStorage(),
  )

private fun EventActions.forStorage(): EventActions =
  copy(
    requestedToolConfirmations =
      requestedToolConfirmations.mapValuesTo(concurrentMutableMapOf()) { (_, confirmation) ->
        confirmation.copy(payload = confirmation.payload?.withoutCredentialSecrets())
      },
    compaction = compaction?.let { it.copy(compactedContent = it.compactedContent.forStorage()) },
  )

private fun Content.forStorage(): Content =
  copy(
    parts =
      parts.map { part ->
        // A reloaded sign-in request must still equal its requested config, and a reply its token.
        val isSignIn =
          part.functionCall?.isSignInRequest == true ||
            part.functionResponse?.isSignInResponse == true
        if (isSignIn) part else part.withoutCredentialSecrets()
      }
  )

/**
 * Returns this part as [adkJson] writes it, its typed credentials as plain JSON without secrets.
 */
@OptIn(FrameworkInternalApi::class)
private fun Part.withoutCredentialSecrets(): Part =
  adkJson.decodeFromJsonElement(
    Part.serializer(),
    adkJson.encodeToJsonElement(Part.serializer(), this),
  )

/** Returns this free-form value as JSON gives it back, its typed credentials without secrets. */
@OptIn(FrameworkInternalApi::class)
private fun Any.withoutCredentialSecrets(): Any? = jsonElementToAny(anyToJsonElement(this))
