/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.adk.kt.webserver.telemetry

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class ApiServerSpanExporterTest {

  @Test
  fun export_conversationIdOnly_linksItsTrace() {
    val exporter = exporterWith { endSpan("invoke_agent replier", CONVERSATION_ID to "s1") }

    assertThat(exporter.getSessionToTraceIdsMap()["s1"])
      .containsExactly(exporter.getAllExportedSpans().single().spanContext.traceId)
  }

  @Test
  fun export_sessionIdOnASpanOtherThanCallLlm_linksItsTrace() {
    val exporter = exporterWith { endSpan("execute_tool t", SESSION_ID to "s2") }

    assertThat(exporter.getSessionToTraceIdsMap()["s2"])
      .containsExactly(exporter.getAllExportedSpans().single().spanContext.traceId)
  }

  @Test
  fun export_traceLinkedBySeveralSpans_listsItsTraceIdOnce() {
    val exporter = exporterWith {
      val agent = endSpan("invoke_agent replier", CONVERSATION_ID to "s3")
      endSpan("call_llm", SESSION_ID to "s3", parent = agent)
      endSpan("call_llm", SESSION_ID to "s3", parent = agent)
    }

    assertThat(exporter.getSessionToTraceIdsMap()["s3"])
      .containsExactly(exporter.getAllExportedSpans().first().spanContext.traceId)
  }

  @Test
  fun export_sessionAndConversationIdsDiffer_linksBySessionId() {
    val exporter = exporterWith { endSpan("call_llm", SESSION_ID to "s4", CONVERSATION_ID to "c4") }

    assertThat(exporter.getSessionToTraceIdsMap().keys).containsExactly("s4")
  }

  @Test
  fun export_emptySessionId_fallsBackToConversationId() {
    val exporter = exporterWith { endSpan("call_llm", SESSION_ID to "", CONVERSATION_ID to "c5") }

    assertThat(exporter.getSessionToTraceIdsMap().keys).containsExactly("c5")
  }

  companion object {
    private const val SESSION_ID = "gcp.vertex.agent.session_id"
    private const val CONVERSATION_ID = "gen_ai.conversation.id"
  }
}
