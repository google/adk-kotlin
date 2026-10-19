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

@file:OptIn(ExperimentalWorkflowApi::class, FrameworkInternalApi::class)

package com.google.adk.kt.workflow

import com.google.adk.kt.annotations.AdkJavaInteropApi
import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.events.Event
import com.google.adk.kt.ids.Uuid
import com.google.adk.kt.serialization.adkJson
import com.google.adk.kt.serialization.anyToJsonElement
import com.google.adk.kt.serialization.jsonElementToAny
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.google.adk.kt.types.Schema
import kotlin.jvm.JvmStatic
import kotlin.jvm.JvmSynthetic
import kotlin.reflect.KType
import kotlin.reflect.typeOf
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.serialization.json.encodeToJsonElement

/**
 * A node's request for user input, which pauses the workflow until a matching response arrives.
 *
 * Normalized into a long-running `adk_request_input` function call; the response is a
 * [com.google.adk.kt.types.FunctionResponse] with matching [interruptId].
 *
 * @property interruptId Identifier for this request and its matching response.
 * @property message Prompt message shown to the user.
 * @property payload Additional structured data for rendering the request.
 * @property responseSchema Optional schema used to validate the user's response. Non-object
 *   responses are unwrapped from the `result` key and parsed from JSON unless the schema accepts a
 *   string. The answer is checked and saved with the same rules as a node input; a null answer
 *   fails unless the schema admits null.
 */
@ExperimentalWorkflowApi
data class RequestInput(
  val interruptId: String = Uuid.random(),
  val message: String? = null,
  val payload: Any? = null,
  val responseSchema: Schema? = null,
) {
  /**
   * Fluent builder for [RequestInput], provided primarily for Java callers. Any property left unset
   * falls back to the same default as the constructor.
   */
  @AdkJavaInteropApi
  @Suppress("ScopeReceiverThis") // Java-style builder for Java interop.
  class Builder {
    private var interruptId: String = Uuid.random()
    private var message: String? = null
    private var payload: Any? = null
    private var responseSchema: Schema? = null

    fun interruptId(interruptId: String): Builder = apply { this.interruptId = interruptId }

    fun message(message: String?): Builder = apply { this.message = message }

    fun payload(payload: Any?): Builder = apply { this.payload = payload }

    fun responseSchema(responseSchema: Schema?): Builder = apply {
      this.responseSchema = responseSchema
    }

    fun build(): RequestInput =
      RequestInput(
        interruptId = interruptId,
        message = message,
        payload = payload,
        responseSchema = responseSchema,
      )
  }

  /**
   * Returns a [Builder] initialized with this instance's properties, primarily for Java callers.
   * Prefer it over `copy` from Java: `copy` takes every property positionally, so its signature
   * changes whenever a property is added.
   */
  @AdkJavaInteropApi
  fun toBuilder(): Builder =
    Builder()
      .interruptId(interruptId)
      .message(message)
      .payload(payload)
      .responseSchema(responseSchema)

  companion object {
    /** Function-call name used for [RequestInput] interrupt events. */
    const val FUNCTION_CALL_NAME: String = FunctionCall.REQUEST_INPUT_FUNCTION_CALL_NAME

    internal const val INTERRUPT_ID_KEY: String = "interruptId"
    internal const val MESSAGE_KEY: String = "message"
    internal const val PAYLOAD_KEY: String = "payload"
    internal const val RESPONSE_SCHEMA_KEY: String = "response_schema"

    @AdkJavaInteropApi @JvmStatic fun builder(): Builder = Builder()
  }
}

/**
 * From a node's body, asks the user for input once per run and ends it. With `rerunOnResume = true`
 * the body reruns and reads the answer from `ctx.resumeInputs[interruptId]`; `ctx.resumeInputs[id]
 * ?: requestInput(id)` asks again on a null answer [responseSchema] allows. Otherwise the answer,
 * checked against any [responseSchema], is the node's output.
 *
 * Catching the throw (`runCatching`, `catch (t: Throwable)`) cancels the request, and the rest of
 * the body runs; `catch (e: Exception)` misses it. The receiver keeps it out of code that has no
 * node flow, such as a tool.
 */
@ExperimentalWorkflowApi
@JvmSynthetic
fun FlowCollector<Nothing>.requestInput(
  interruptId: String = Uuid.random(),
  message: String? = null,
  responseSchema: Schema? = null,
): Nothing =
  throw NodeInterruptedException(RequestInput(interruptId, message, null, responseSchema))

/**
 * Like the overload without a payload, and also sends [payload] in its JSON form under [P]'s
 * serializer, as `Context.setOutput` writes an output.
 *
 * @throws IllegalArgumentException if [P] has no serializer or [payload] has no JSON form.
 */
@ExperimentalWorkflowApi
@JvmSynthetic
// typeOf<P>() is a compiler intrinsic and does not require kotlin-reflect.
@Suppress("KotlinReflectNeeded")
inline fun <reified P> FlowCollector<Nothing>.requestInput(
  interruptId: String = Uuid.random(),
  message: String? = null,
  payload: P,
  responseSchema: Schema? = null,
): Nothing = requestInputWithPayload(interruptId, message, payload, typeOf<P>(), responseSchema)

@PublishedApi
internal fun requestInputWithPayload(
  interruptId: String,
  message: String?,
  payload: Any?,
  payloadType: KType,
  responseSchema: Schema?,
): Nothing {
  val json = ValueCodec<Any?>(payloadType).schemaForm(payload, "payload of requestInput")
  throw NodeInterruptedException(RequestInput(interruptId, message, json, responseSchema))
}

/** Converts this request into a long-running `adk_request_input` model [Event]. */
internal fun RequestInput.toEvent(): Event {
  val call =
    FunctionCall(
      name = RequestInput.FUNCTION_CALL_NAME,
      id = interruptId,
      args =
        mapOf(
          RequestInput.INTERRUPT_ID_KEY to interruptId,
          RequestInput.MESSAGE_KEY to message,
          // A payload with no JSON form fails here, in the asking node, not in the session service.
          RequestInput.PAYLOAD_KEY to payload?.let { jsonElementToAny(anyToJsonElement(it)) },
          RequestInput.RESPONSE_SCHEMA_KEY to
            responseSchema?.let { jsonElementToAny(adkJson.encodeToJsonElement(it)) },
        ),
    )
  return Event(
    // Model role prevents history rewriters from dropping the event as empty content.
    content = Content(role = Role.MODEL, parts = listOf(Part(functionCall = call))),
    longRunningToolIds = setOf(interruptId),
  )
}
