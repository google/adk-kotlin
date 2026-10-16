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

package com.google.adk.kt.plugins.agentanalytics

import com.fasterxml.jackson.databind.JsonNode
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.plugins.agentanalytics.JsonFormatter.SanitizedText
import com.google.adk.kt.types.CodeExecutionResult
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.ExecutableCode
import com.google.adk.kt.types.FileData
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.FunctionResponse
import com.google.adk.kt.types.GenerateContentConfig
import com.google.adk.kt.types.Language
import com.google.adk.kt.types.Outcome
import com.google.adk.kt.types.Part
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ParserTest {

  private val parser = Parser(maxLength = 1000)

  private fun parse(vararg parts: Part, maxLength: Int = 1000): Parser.ParsedContent =
    Parser(maxLength).parse(Content(role = "user", parts = parts.toList()))

  private fun fileUriPart(uri: String): JsonNode =
    parse(Part(fileData = FileData(fileUri = uri, mimeType = "application/pdf"))).parts.single()

  private fun partAttributes(part: JsonNode): JsonNode =
    JsonFormatter.mapper.readTree(part.get("part_attributes").asText())

  @Test
  fun fileUri_signedUrl_redactsTheCredentialQueryValues() {
    val parsed =
      parse(
        Part(
          fileData =
            FileData(
              fileUri =
                "https://storage.googleapis.com/bucket/report.pdf" +
                  "?X-Goog-Algorithm=GOOG4-RSA-SHA256" +
                  "&X-Goog-Credential=sa%40project.iam.gserviceaccount.com%2F20260101" +
                  "&X-Goog-Expires=900&X-Goog-Signature=0a1b2c3d",
              mimeType = "application/pdf",
            )
        )
      )

    val part = parsed.parts.single()
    assertEquals(
      "https://storage.googleapis.com/bucket/report.pdf?X-Goog-Algorithm=GOOG4-RSA-SHA256" +
        "&X-Goog-Credential=%5BREDACTED%5D&X-Goog-Expires=900&X-Goog-Signature=%5BREDACTED%5D",
      part.get("uri").asText(),
    )
    assertEquals("EXTERNAL_URI", part.get("storage_mode").asText())
    assertTrue(parsed.isTruncated)
  }

  @Test
  fun fileUri_withoutCredentials_isKeptAndNotFlagged() {
    val parsed =
      parse(Part(fileData = FileData(fileUri = "gs://bucket/image.png", mimeType = "image/png")))

    val part = parsed.parts.single()
    assertEquals("gs://bucket/image.png", part.get("uri").asText())
    assertEquals("image/png", part.get("mime_type").asText())
    assertFalse(parsed.isTruncated)
  }

  @Test
  fun fileUri_withUserinfo_isReplacedWhole() {
    assertEquals(
      "[REDACTED_SENSITIVE_URI]",
      fileUriPart("https://user:pass@example.com/file.pdf").get("uri").asText(),
    )
  }

  @Test
  fun fileUri_sensitivePathSegment_redactsItAndTheSegmentAfterIt() {
    assertEquals(
      "https://example.com/v1/%5BREDACTED%5D/%5BREDACTED%5D/file.pdf",
      fileUriPart("https://example.com/v1/token/abc123/file.pdf").get("uri").asText(),
    )
  }

  @Test
  fun fileUri_encodedCredentialInAPathSegment_replacesTheSegment() {
    assertEquals(
      "https://example.com/cb/%5BREDACTED_SENSITIVE_TEXT%5D/file.pdf",
      fileUriPart("https://example.com/cb/access_token%3Dya29.X/file.pdf").get("uri").asText(),
    )
  }

  @Test
  fun fileUri_doubleEncodedSensitivePathSegment_redactsItAndTheSegmentAfterIt() {
    assertEquals(
      "https://example.com/v1/%5BREDACTED%5D/%5BREDACTED%5D/file.pdf",
      fileUriPart("https://example.com/v1/access%255Ftoken/abc123/file.pdf").get("uri").asText(),
    )
  }

  @Test
  fun fileUri_fragmentCredentials_areRedacted() {
    assertEquals(
      "https://example.com/callback#access_token=[REDACTED]&state=xyz",
      fileUriPart("https://example.com/callback#access_token=ya29.abc&state=xyz")
        .get("uri")
        .asText(),
    )
  }

  @Test
  fun fileUri_jsonInAComponent_isRedactedByKey() {
    val json = """{"token": {"access_token": "ya29.X"}}"""
    val encodedJson = "%7B%22token%22%3A+%7B%22access_token%22%3A+%22ya29.X%22%7D%7D"
    val redactedJson = "%7B%22token%22%3A%22%5BREDACTED%5D%22%7D"

    assertEquals(
      """https://example.com/$redactedJson/cb?state=$redactedJson#{"token":"[REDACTED]"}""",
      fileUriPart("https://example.com/$json/cb?state=$encodedJson#$json").get("uri").asText(),
    )
  }

  @Test
  fun textPart_overBudget_isTruncatedAndFlagged() {
    val parsed = parse(Part(text = "a".repeat(50)), maxLength = 20)

    assertEquals("aaaaaa...[TRUNCATED]", parsed.parts.single().get("text").asText())
    assertTrue(parsed.isTruncated)
  }

  @Test
  fun textPart_json_isRedactedByKey() {
    val text = """{"token": {"access_token": "ya29.X"}, "city": "Paris"}"""

    val part = parse(Part(text = text)).parts.single()

    assertEquals("""{"token":"[REDACTED]","city":"Paris"}""", part.get("text").asText())
  }

  @Test
  fun textPart_bracketLedProse_isKept() {
    val parsed = parse(Part(text = "[INFO] done"))

    assertEquals("[INFO] done", parsed.parts.single().get("text").asText())
    assertFalse(parsed.isTruncated)
  }

  @Test
  fun stringContent_bracketLedProse_isKept() {
    val parsed = parser.parse("[System] Be brief.")

    assertEquals("[System] Be brief.", parsed.content.asText())
    assertFalse(parsed.isTruncated)
  }

  @Test
  fun functionCallPart_recordsTheFunctionName() {
    val part =
      parse(
          Part(functionCall = FunctionCall(name = "get_weather", args = mapOf("city" to "Paris")))
        )
        .parts
        .single()

    assertEquals("Function: get_weather", part.get("text").asText())
    assertEquals("application/json", part.get("mime_type").asText())
    assertEquals("get_weather", partAttributes(part).get("function_name").asText())
  }

  @Test
  fun functionResponsePart_isSummarizedAndRedacted() {
    val parsed =
      parse(
        Part(
          functionResponse =
            FunctionResponse(
              name = "lookup",
              id = "call-1",
              response = mapOf("result" to "ok", "api_key" to "k-123"),
            )
        )
      )

    val part = parsed.parts.single()
    assertEquals("Function response: lookup", part.get("text").asText())
    assertEquals("application/json", part.get("mime_type").asText())
    val response = partAttributes(part).at("/function_response/response")
    assertEquals("ok", response.get("result").asText())
    assertEquals(JsonFormatter.REDACTED_MESSAGE, response.get("api_key").asText())
    assertEquals("Function response: lookup", parsed.content.get("text_summary").asText())
  }

  @Test
  fun executableCodePart_rendersTheLanguageAndRedactedCode() {
    val parsed =
      parse(
        Part(
          executableCode =
            ExecutableCode(code = "x = 1\ntoken=abc\nprint(x)", language = Language.PYTHON)
        )
      )

    val part = parsed.parts.single()
    assertEquals("x = 1\ntoken=[REDACTED]\nprint(x)", part.get("text").asText())
    assertEquals("PYTHON", partAttributes(part).at("/executable_code/language").asText())
    assertEquals(
      "Executable code (PYTHON): x = 1\ntoken=[REDACTED]\nprint(x)",
      parsed.content.get("text_summary").asText(),
    )
  }

  @Test
  fun codeExecutionResultPart_rendersTheOutcomeAndOutput() {
    val parsed =
      parse(
        Part(
          codeExecutionResult =
            CodeExecutionResult(outcome = Outcome.OUTCOME_OK, output = "42", id = "exec-1")
        )
      )

    val part = parsed.parts.single()
    assertEquals("42", part.get("text").asText())
    assertEquals("exec-1", partAttributes(part).at("/code_execution_result/id").asText())
    assertEquals(
      "Code execution result (OUTCOME_OK): 42",
      parsed.content.get("text_summary").asText(),
    )
  }

  @Test
  fun llmRequest_logsThePromptAndSystemInstruction() {
    val request =
      LlmRequest(
        contents = listOf(Content(role = "user", parts = listOf(Part(text = "Hi")))),
        config =
          GenerateContentConfig(
            systemInstruction = Content(parts = listOf(Part(text = "Be brief.")))
          ),
      )

    val parsed = parser.parse(request)

    assertEquals("Be brief.", parsed.content.get("system_prompt").asText())
    assertEquals("user", parsed.content.at("/prompt/0/role").asText())
    assertEquals("Hi", parsed.content.at("/prompt/0/content").asText())
    assertEquals(listOf("Hi", "Be brief."), parsed.parts.map { it.get("text").asText() })
  }

  @Test
  fun llmRequest_overBudgetSystemInstruction_isFlagged() {
    val request =
      LlmRequest(
        config =
          GenerateContentConfig(
            systemInstruction = Content(parts = listOf(Part(text = "b".repeat(50))))
          )
      )

    val parsed = Parser(maxLength = 20).parse(request)

    assertEquals("bbbbbb...[TRUNCATED]", parsed.content.get("system_prompt").asText())
    assertTrue(parsed.isTruncated)
  }

  @Test
  fun formatText_joinsTextsLikePythonAdk() {
    assertEquals(
      SanitizedText("text: 'Done' | text: 'Bye'", false),
      parser.formatText(listOf("Done", "Bye")),
    )
  }

  @Test
  fun formatText_reportsRedaction() {
    assertEquals(
      SanitizedText("text: 'bearer [REDACTED]'", true),
      parser.formatText(listOf("bearer abc")),
    )
  }
}
