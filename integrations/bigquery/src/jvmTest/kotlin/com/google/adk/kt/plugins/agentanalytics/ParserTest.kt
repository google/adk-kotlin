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
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.plugins.agentanalytics.JsonFormatter.SanitizedText
import com.google.adk.kt.types.Blob
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
import com.google.adk.kt.types.UsageMetadata
import java.io.IOException
import java.nio.charset.StandardCharsets.UTF_8
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

class ParserTest {

  private val parser = Parser(maxLength = 1000)

  private fun parse(
    vararg parts: Part,
    maxLength: Int = 1000,
    connectionId: String? = null,
    traceId: String = "",
    spanId: String = "",
    offloader: ContentOffloader? = null,
  ): Parser.ParsedContent = runBlocking {
    Parser(maxLength, connectionId, dateProvider = { FIXED_DATE }, uidProvider = { "uid1" })
      .parse(Content(role = "user", parts = parts.toList()), traceId, spanId, offloader)
  }

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
    assertEquals("EXTERNAL_URI", part.get("storage_mode").asText())
    assertTrue(part.get("object_ref").isNull)
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
  fun stringContent_bracketLedProse_isKept(): Unit = runBlocking {
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
  fun llmRequest_logsThePromptAndSystemInstruction(): Unit = runBlocking {
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
  fun llmRequest_overBudgetSystemInstruction_isFlagged(): Unit = runBlocking {
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

  @Test
  fun formatText_truncatesJoinedResultWithinMaxLength() {
    assertEquals(
      SanitizedText("text: 'aaa...[TRUNCATED]", true),
      Parser(maxLength = 24).formatText(listOf("aaa", "bbb")),
    )
  }

  @Test
  fun llmResponse_formatsResponseStringAndLeavesContentPartsEmpty(): Unit = runBlocking {
    val response =
      LlmResponse(
        content =
          Content(
            role = "model",
            parts =
              listOf(
                Part(text = "Hi"),
                Part(functionCall = FunctionCall(name = "get_weather", args = emptyMap())),
                Part(
                  functionResponse =
                    FunctionResponse(name = "get_weather", id = "c1", response = emptyMap())
                ),
                Part(
                  executableCode = ExecutableCode(code = "print(1)", language = Language.PYTHON)
                ),
                Part(
                  codeExecutionResult =
                    CodeExecutionResult(outcome = Outcome.OUTCOME_OK, output = "1")
                ),
              ),
          ),
        usageMetadata =
          UsageMetadata(promptTokenCount = 5, candidatesTokenCount = 7, totalTokenCount = 12),
      )

    val parsed = parser.parse(response)

    assertEquals(
      "text: 'Hi' | call: get_weather | resp: get_weather | " +
        "Executable code (PYTHON): print(1) | Code execution result (OUTCOME_OK): 1",
      parsed.content.get("response").asText(),
    )
    assertEquals(5, parsed.content.at("/usage/prompt").asInt())
    assertEquals(7, parsed.content.at("/usage/completion").asInt())
    assertEquals(12, parsed.content.at("/usage/total").asInt())
    assertTrue(parsed.parts.isEmpty())
    assertFalse(parsed.isTruncated)
  }

  @Test
  fun llmResponse_emptyContentAndRedactedResponse(): Unit = runBlocking {
    val emptyParts = parser.parse(LlmResponse(content = Content(parts = emptyList())))
    assertEquals("None", emptyParts.content.get("response").asText())

    val emptyResponse = parser.parse(LlmResponse())
    assertTrue(emptyResponse.content.isNull)

    val redacted =
      parser.parse(LlmResponse(content = Content(parts = listOf(Part(text = "bearer secret")))))
    assertEquals("text: 'bearer [REDACTED]'", redacted.content.get("response").asText())
    assertTrue(redacted.isTruncated)
  }

  @Test
  fun partModels_redactEscapedCredentialsAndFlagTruncation() {
    val codeOutput =
      parse(
        Part(
          codeExecutionResult =
            CodeExecutionResult(
              outcome = Outcome.OUTCOME_OK,
              output = "access%5Ftoken=ya29.SECRET",
              id = "exec-1",
            )
        )
      )
    val codeOutputPart = codeOutput.parts.single()
    assertEquals("[REDACTED_SENSITIVE_TEXT]", codeOutputPart.get("text").asText())
    assertEquals(
      "[REDACTED_SENSITIVE_TEXT]",
      partAttributes(codeOutputPart).at("/code_execution_result/output").asText(),
    )
    assertEquals(
      "Code execution result (OUTCOME_OK): [REDACTED_SENSITIVE_TEXT]",
      codeOutput.content.get("text_summary").asText(),
    )
    assertTrue(codeOutput.isTruncated)

    val execCode =
      parse(
        Part(
          executableCode =
            ExecutableCode(code = "access%5Ftoken=ya29.SECRET", language = Language.PYTHON)
        )
      )
    val execCodePart = execCode.parts.single()
    assertEquals("[REDACTED_SENSITIVE_TEXT]", execCodePart.get("text").asText())
    assertEquals(
      "[REDACTED_SENSITIVE_TEXT]",
      partAttributes(execCodePart).at("/executable_code/code").asText(),
    )
    assertEquals(
      "Executable code (PYTHON): [REDACTED_SENSITIVE_TEXT]",
      execCode.content.get("text_summary").asText(),
    )
    assertTrue(execCode.isTruncated)

    val fnResponse =
      parse(
        Part(
          functionResponse =
            FunctionResponse(
              name = "lookup",
              id = "call-1",
              response = mapOf("out" to "access%5Ftoken=ya29.SECRET", "access%5Ftoken" to "raw"),
            )
        )
      )
    val fnPart = fnResponse.parts.single()
    val responseAttrs = partAttributes(fnPart).at("/function_response/response")
    assertEquals("[REDACTED_SENSITIVE_TEXT]", responseAttrs.get("out").asText())
    assertEquals("[REDACTED]", responseAttrs.get("access%5Ftoken").asText())
    assertTrue(fnResponse.isTruncated)
  }

  @Test
  fun inlineDataPart_offloadsToGcsWithObjectRefAndSanitizedFallbackPaths(): Unit = runBlocking {
    val noOffload = parse(Part(inlineData = Blob(mimeType = "image/png", data = byteArrayOf(1, 2))))
    assertEquals("INLINE", noOffload.parts.single().get("storage_mode").asText())
    assertEquals("[BINARY DATA]", noOffload.parts.single().get("text").asText())
    assertTrue(noOffload.parts.single().get("object_ref").isNull)

    val offloader = RecordingOffloader()
    val bytes = byteArrayOf(0x89.toByte(), 0x50)
    val parsed =
      parse(
        Part(inlineData = Blob(mimeType = "image/png", data = bytes)),
        connectionId = "us.conn",
        traceId = "tr-1",
        spanId = "sp-2",
        offloader = offloader,
      )
    val expectedPath = "2026-11-13/tr-1/sp-2_uid1_c0_p0.png"
    val expectedUri = "gs://b/$expectedPath"
    assertEquals(expectedPath, offloader.uploads.single().path)
    assertContentEquals(bytes, offloader.uploads.single().data)
    val part = parsed.parts.single()
    assertEquals("GCS_REFERENCE", part.get("storage_mode").asText())
    assertEquals(expectedUri, part.get("uri").asText())
    assertEquals("[MEDIA OFFLOADED]", part.get("text").asText())
    val ref = part.get("object_ref")
    assertEquals(expectedUri, ref.get("uri").asText())
    assertTrue(ref.has("version") && ref.get("version").isNull)
    assertEquals("us.conn", ref.get("authorizer").asText())
    assertEquals("""{"gcs_metadata":{"content_type":"image/png"}}""", ref.get("details").asText())
    assertFalse(parsed.isTruncated)

    val fallbackOffloader = RecordingOffloader()
    val fallback =
      parse(
        Part(inlineData = Blob(mimeType = "", data = byteArrayOf(9))),
        offloader = fallbackOffloader,
      )
    assertEquals("application/octet-stream", fallback.parts.single().get("mime_type").asText())
    assertEquals(
      "2026-11-13/no_trace/no_span_uid1_c0_p0.bin",
      fallbackOffloader.uploads.single().path,
    )

    val reqOffloader = RecordingOffloader()
    val sysInstruction =
      Content(
        parts = listOf(Part(text = "s"), Part(inlineData = Blob(mimeType = "application/pdf")))
      )
    val req =
      LlmRequest(
        contents = listOf(Content(role = "user", parts = listOf(Part(text = "hi")))),
        config = GenerateContentConfig(systemInstruction = sysInstruction),
      )
    val reqParsed =
      Parser(1000, dateProvider = { FIXED_DATE }, uidProvider = { "u2" })
        .parse(req, traceId = "../tr/ace:1", spanId = "sp an?2", offloader = reqOffloader)
    assertEquals(3, reqParsed.parts.size)
    assertEquals("2026-11-13/___tr_ace_1/sp_an_2_u2_c1_p1.pdf", reqOffloader.uploads.single().path)
    assertEquals(0, reqOffloader.uploads.single().data.size)
  }

  @Test
  fun largeTextPart_redactsBeforeUploadCutsUtf8PreviewAndExcludesFromSummary() {
    val offloader = RecordingOffloader()
    // "Bearer [REDACTED] " is 18 bytes + 181 'a's = 199 bytes, then 3-byte '世' straddles byte 200.
    val raw = "Bearer ya29.secret " + "a".repeat(181) + "世" + "z".repeat(400)
    val parsed =
      parse(
        Part(text = "Intro"),
        Part(text = raw),
        maxLength = 500,
        connectionId = "us.conn",
        offloader = offloader,
      )
    val upload = offloader.uploads.single()
    assertEquals("2026-11-13/no_trace/no_span_uid1_c0_p1.txt", upload.path)
    assertEquals("text/plain", upload.contentType)
    assertEquals(
      "Bearer [REDACTED] " + "a".repeat(181) + "世" + "z".repeat(400),
      upload.data.toString(UTF_8),
    )
    val part = parsed.parts[1]
    assertEquals("GCS_REFERENCE", part.get("storage_mode").asText())
    assertEquals("text/plain", part.get("mime_type").asText())
    assertEquals(
      "Bearer [REDACTED] " + "a".repeat(181) + "... [OFFLOADED]",
      part.get("text").asText(),
    )
    assertEquals(
      """{"gcs_metadata":{"content_type":"text/plain"}}""",
      part.at("/object_ref/details").asText(),
    )
    assertEquals("Intro", parsed.content.get("text_summary").asText())
    assertFalse(parsed.isTruncated)

    val unlimitedOffloader = RecordingOffloader()
    val exact32k =
      parse(
        Part(text = "x".repeat(32 * 1024)),
        maxLength = Int.MAX_VALUE,
        offloader = unlimitedOffloader,
      )
    assertEquals("INLINE", exact32k.parts.single().get("storage_mode").asText())
    assertTrue(unlimitedOffloader.uploads.isEmpty())

    val over32k =
      parse(
        Part(text = "x".repeat(32 * 1024 + 1)),
        maxLength = Int.MAX_VALUE,
        offloader = unlimitedOffloader,
      )
    assertEquals("GCS_REFERENCE", over32k.parts.single().get("storage_mode").asText())
    assertEquals("x".repeat(200) + "... [OFFLOADED]", over32k.parts.single().get("text").asText())
    assertEquals("", over32k.content.get("text_summary").asText())
    assertFalse(over32k.isTruncated)

    val tinyBudget =
      parse(Part(text = "abcdefghij"), maxLength = 5, offloader = RecordingOffloader())
    assertEquals("... [OFFLOADED]", tinyBudget.parts.single().get("text").asText())
  }

  @Test
  fun offloadFailure_fallsBackInlineWhileCancellationRethrows() {
    val failing = RecordingOffloader(failure = IOException("gcs unavailable"))
    val binaryPart =
      parse(
          Part(inlineData = Blob(mimeType = "image/png", data = byteArrayOf(1))),
          offloader = failing,
        )
        .parts
        .single()
    assertEquals("INLINE", binaryPart.get("storage_mode").asText())
    assertTrue(binaryPart.get("object_ref").isNull)
    assertEquals("[UPLOAD FAILED]", binaryPart.get("text").asText())

    val text = parse(Part(text = "a".repeat(60)), maxLength = 20, offloader = failing)
    val textPart = text.parts.single()
    assertEquals("INLINE", textPart.get("storage_mode").asText())
    assertTrue(textPart.get("object_ref").isNull)
    assertEquals("aaaaaa...[TRUNCATED]", textPart.get("text").asText())
    assertEquals("aaaaaa...[TRUNCATED]", text.content.get("text_summary").asText())
    assertTrue(text.isTruncated)

    val cancelling = RecordingOffloader(failure = CancellationException("cancelled"))
    assertFailsWith<CancellationException> {
      val unused =
        parse(
          Part(inlineData = Blob(mimeType = "image/png", data = byteArrayOf(1))),
          offloader = cancelling,
        )
    }
    assertFailsWith<CancellationException> {
      val unused = parse(Part(text = "a".repeat(60)), maxLength = 20, offloader = cancelling)
    }
  }

  private companion object {
    val FIXED_DATE: LocalDate = LocalDate.of(2026, 11, 13)
  }
}

private class RecordingOffloader(private val failure: Exception? = null) : ContentOffloader {
  class Uploaded(val data: ByteArray, val contentType: String, val path: String)

  val uploads = mutableListOf<Uploaded>()

  override suspend fun uploadContent(data: ByteArray, contentType: String, path: String): String {
    failure?.let { throw it }
    uploads += Uploaded(data, contentType, path)
    return "gs://b/$path"
  }
}
