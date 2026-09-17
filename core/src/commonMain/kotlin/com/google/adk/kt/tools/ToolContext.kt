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

package com.google.adk.kt.tools

import com.google.adk.kt.agents.Context
import com.google.adk.kt.agents.InvocationContext
import com.google.adk.kt.events.EventActions
import com.google.adk.kt.events.ToolConfirmation

/**
 * Context passed to tool executions.
 *
 * This subclass is kept for backward compatibility. Prefer [Context] in new code.
 */
class ToolContext(
  invocationContext: InvocationContext,
  actions: EventActions = EventActions(),
  functionCallId: String? = null,
  toolConfirmation: ToolConfirmation? = null,
  eventId: String? = null,
) : Context(invocationContext, actions, functionCallId, toolConfirmation, eventId)
