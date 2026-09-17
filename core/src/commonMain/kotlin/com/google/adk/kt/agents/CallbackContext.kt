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

package com.google.adk.kt.agents

import com.google.adk.kt.events.EventActions

/**
 * Context passed to agent and model callbacks.
 *
 * This subclass is kept for backward compatibility. Prefer [Context] in new code.
 */
class CallbackContext(invocationContext: InvocationContext, eventActions: EventActions? = null) :
  Context(invocationContext, eventActions)

/**
 * Creates a callback context for the current invocation.
 *
 * @param eventActions Optional initial event actions.
 */
internal fun InvocationContext.toCallbackContext(
  eventActions: EventActions? = null
): CallbackContext = CallbackContext(this, eventActions)
