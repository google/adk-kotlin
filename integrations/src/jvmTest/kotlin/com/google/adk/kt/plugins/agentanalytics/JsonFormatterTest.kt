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

import com.google.adk.kt.plugins.agentanalytics.JsonFormatter.REDACTED_MESSAGE
import com.google.adk.kt.plugins.agentanalytics.JsonFormatter.SanitizedText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTimedValue

class JsonFormatterTest {

  @Test
  fun smartTruncate_redactsEveryKeyPythonAdkRedacts() {
    val keys =
      listOf(
        "client_secret",
        "access_token",
        "refresh_token",
        "id_token",
        "api_key",
        "password",
        "private_key",
        "proxy_authorization",
        "google_access_id",
        "sig",
        "signature",
        "token",
        "secret",
        "authorization",
        "x_api_key",
        "x_amz_credential",
        "x_amz_signature",
        "x_goog_credential",
        "x_goog_security_token",
        "x_goog_signature",
      )
    val input = keys.associateWith { "value" } + ("region" to "us")

    val redacted = JsonFormatter.smartTruncate(input, NO_LIMIT).node

    for (key in keys) {
      assertEquals(REDACTED_MESSAGE, redacted.get(key).asText(), key)
    }
    assertEquals("us", redacted.get("region").asText())
  }

  @Test
  fun smartTruncate_redactsHyphenatedSensitiveKeys() {
    val input = mapOf("API-Key" to "secret-123", "id-token" to "t")

    val redacted = JsonFormatter.smartTruncate(input, NO_LIMIT).node

    assertEquals(REDACTED_MESSAGE, redacted.get("API-Key").asText())
    assertEquals(REDACTED_MESSAGE, redacted.get("id-token").asText())
  }

  @Test
  fun smartTruncate_redactsAServiceAccountPrivateKey() {
    val credentials =
      mapOf(
        "type" to "service_account",
        "private_key" to "-----BEGIN PRIVATE KEY-----\nMIIE\n-----END PRIVATE KEY-----\n",
      )

    val redacted = JsonFormatter.smartTruncate(mapOf("credentials" to credentials), NO_LIMIT).node

    assertEquals(REDACTED_MESSAGE, redacted.at("/credentials/private_key").asText())
    assertEquals("service_account", redacted.at("/credentials/type").asText())
  }

  @Test
  fun redactSensitivePatterns_redactsCredentialsEmbeddedInText() {
    assertEquals(
      "https://example.com/o?api-key=[REDACTED]&alt=media",
      JsonFormatter.redactSensitivePatterns("https://example.com/o?api-key=abc123xyz&alt=media"),
    )
    assertEquals(
      "Authorization: [REDACTED]",
      JsonFormatter.redactSensitivePatterns("Authorization: Bearer abc.def.ghi"),
    )
    assertEquals(
      "call failed with bearer [REDACTED]",
      JsonFormatter.redactSensitivePatterns("call failed with bearer abc.def.ghi"),
    )
    assertEquals(
      """{"access_token": "[REDACTED]"}""",
      JsonFormatter.redactSensitivePatterns("""{"access_token": "ya29.abcdef"}"""),
    )
  }

  @Test
  fun redactSensitivePatterns_leavesOrdinaryProseUnchanged() {
    val prose = "[INFO] the bearer of this note may retry; count=3, ratio=0.5"
    assertEquals(prose, JsonFormatter.redactSensitivePatterns(prose))
  }

  @Test
  fun redactSensitivePatterns_isIdempotent() {
    val once = JsonFormatter.redactSensitivePatterns("api_key=abc123")
    assertEquals(once, JsonFormatter.redactSensitivePatterns(once))
  }

  @Test
  fun redactSensitivePatterns_redactsSignedUrlCredentials() {
    val signedUrl =
      "https://storage.googleapis.com/bucket/object?X-Goog-Algorithm=GOOG4-RSA-SHA256" +
        "&X-Goog-Credential=sa%40project.iam.gserviceaccount.com%2F20260101" +
        "&X-Goog-Signature=0a1b2c3d&sig=e4f5"

    assertEquals(
      "https://storage.googleapis.com/bucket/object?X-Goog-Algorithm=GOOG4-RSA-SHA256" +
        "&X-Goog-Credential=[REDACTED]&X-Goog-Signature=[REDACTED]&sig=[REDACTED]",
      JsonFormatter.redactSensitivePatterns(signedUrl),
    )
  }

  @Test
  fun redactSensitivePatterns_longQuotedValue_doesNotOverflowTheStack() {
    val text = "token=\"" + "a".repeat(100_000) + "\""

    assertEquals("token=\"[REDACTED]\"", JsonFormatter.redactSensitivePatterns(text))
  }

  @Test
  fun truncate_appendsTheMarkerWithinTheByteBudget() {
    assertEquals("aaaaaa...[TRUNCATED]", JsonFormatter.truncate("a".repeat(100), 20))
  }

  @Test
  fun truncate_countsUtf8BytesAndNeverSplitsACharacter() {
    assertEquals("éé...[TRUNCATED]", JsonFormatter.truncate("é".repeat(10), 19))
  }

  @Test
  fun sanitizeText_reportsRedactionAndTruncationAsChanges() {
    assertEquals(SanitizedText("plain", false), JsonFormatter.sanitizeText("plain", 100))
    assertEquals(
      SanitizedText("auth failed: bearer [REDACTED]", true),
      JsonFormatter.sanitizeText("auth failed: bearer abc.def", 100),
    )
    assertEquals(
      SanitizedText("aaaaaa...[TRUNCATED]", true),
      JsonFormatter.sanitizeText("a".repeat(100), 20),
    )
  }

  @Test
  fun sanitizeText_jsonDocument_isRedactedByKey() {
    assertEquals(
      SanitizedText("""{"token":"[REDACTED]"}""", true),
      JsonFormatter.sanitizeText("""{"token": {"access_token": "ya29.X"}}""", 1000),
    )
  }

  @Test
  fun sanitizeText_jsonWithADuplicateKeyOrNaN_isRedactedByKey() {
    assertEquals(
      SanitizedText("""{"token":"[REDACTED]","x":2}""", true),
      JsonFormatter.sanitizeText("""{"token": {"access_token": "ya29.X"}, "x": 1, "x": 2}""", 1000),
    )
    assertEquals(
      SanitizedText("""{"token":"[REDACTED]","x":"NaN"}""", true),
      JsonFormatter.sanitizeText("""{"token": {"access_token": "ya29.X"}, "x": NaN}""", 1000),
    )
  }

  @Test
  fun sanitizeText_bracketLedProse_isRedactedInPlace() {
    assertEquals(
      SanitizedText("[INFO] retry with token: [REDACTED]", true),
      JsonFormatter.sanitizeText("[INFO] retry with token: abc", 1000),
    )
  }

  @Test
  fun sanitizeText_escapedCredential_replacesTheWholeText() {
    for (text in
      listOf(
        "redirect to /cb?access%5Ftoken=ya29.X failed",
        "redirect to /cb?access%255Ftoken=ya29.X failed",
        """error: access\u005ftoken=ya29.X""",
        """error: access\x5ftoken=ya29.X""",
      )) {
      assertEquals(
        SanitizedText("[REDACTED_SENSITIVE_TEXT]", true),
        JsonFormatter.sanitizeText(text, 1000),
        text,
      )
    }
  }

  @Test
  fun sanitizeText_escapesThatHideNoCredential_areKept() {
    val text = """C:\x41pps\tool failed at 100%41 with \u00e9"""

    assertEquals(SanitizedText(text, false), JsonFormatter.sanitizeText(text, 1000))
  }

  @Test
  fun sanitizeText_keyWithAnObjectOrArrayValue_isRedactedThroughItsClosingBracket() {
    assertEquals(
      """{"token": [REDACTED]""",
      JsonFormatter.sanitizeText(
          """{"token": {"access_token": "ya29.X", "expires_in": 3599}""",
          1000,
        )
        .text,
    )
    assertEquals(
      """{"credentials": {"password": [REDACTED]}""",
      JsonFormatter.sanitizeText("""{"credentials": {"password": ["hunter2", "hunter3"]}""", 1000)
        .text,
    )
    assertEquals(
      """Exception: {"token": [REDACTED]} at Foo.bar""",
      JsonFormatter.sanitizeText("""Exception: {"token": {"a": "}"}} at Foo.bar""", 1000).text,
    )
  }

  @Test
  fun sanitizeText_keyWithAnUnclosedObjectValue_isRedactedToTheEnd() {
    assertEquals(
      SanitizedText("retry failed, token=[REDACTED]", true),
      JsonFormatter.sanitizeText(
        """retry failed, token={"access_token": "ya29.X", "scope": "a b""",
        1000,
      ),
    )
  }

  @Test
  fun redactSensitivePatterns_bracketLedValueFollowedByText_isRedactedToTheSeparator() {
    assertEquals(
      "login failed: password=[REDACTED] retry",
      JsonFormatter.redactSensitivePatterns("login failed: password=[x]hunter2 retry"),
    )
    assertEquals(
      "token: [REDACTED] rest",
      JsonFormatter.redactSensitivePatterns("token: [x](SECRET) rest"),
    )
  }

  @Test
  fun redactSensitivePatterns_manyBracketValuesWithoutSeparators_finishesQuickly() {
    val text = "x " + "token=[]".repeat(32_000)

    val (redacted, elapsed) = measureTimedValue { JsonFormatter.redactSensitivePatterns(text) }

    assertEquals("x token=[REDACTED]", redacted)
    assertTrue(elapsed < 2.seconds, "took $elapsed")
  }

  @Test
  fun sanitizeText_escapesAfterARedactedValue_areKept() {
    assertEquals(
      SanitizedText("token=[REDACTED] more%20x", true),
      JsonFormatter.sanitizeText("""token={"a":1}xyz more%20x""", 1000),
    )
  }

  @Test
  fun sanitizeText_textTooLongToInspect_isReplacedOnlyWithoutABudget() {
    val text = "a".repeat(4_000_001)

    assertEquals(
      SanitizedText("[REDACTED_SENSITIVE_TEXT]", true),
      JsonFormatter.sanitizeText(text, Int.MAX_VALUE),
    )
    assertEquals("a".repeat(986) + "...[TRUNCATED]", JsonFormatter.sanitizeText(text, 1000).text)
  }

  @Test
  fun smartTruncate_jsonNode_isRedactedAndBounded() {
    val node = JsonFormatter.mapper.readTree("""{"token": "t-1", "note": "${"a".repeat(50)}"}""")

    val result = JsonFormatter.smartTruncate(node, 20)

    assertEquals(REDACTED_MESSAGE, result.node.get("token").asText())
    assertEquals("aaaaaa...[TRUNCATED]", result.node.get("note").asText())
    assertTrue(result.isTruncated)
  }

  @Test
  fun smartTruncate_nestedCollections_areBoundedAndFlagged() {
    val result = JsonFormatter.smartTruncate(mapOf("notes" to listOf("short", "b".repeat(50))), 20)

    assertEquals("short", result.node.at("/notes/0").asText())
    assertEquals("bbbbbb...[TRUNCATED]", result.node.at("/notes/1").asText())
    assertTrue(result.isTruncated)
  }

  @Test
  fun smartTruncate_withinBudget_isNotFlagged() {
    val result =
      JsonFormatter.smartTruncate(mapOf("api_key" to "k", "notes" to listOf("short")), 20)

    assertEquals(REDACTED_MESSAGE, result.node.get("api_key").asText())
    assertFalse(result.isTruncated)
  }

  @Test
  fun smartTruncate_utf8Bytes_areRedactedAndTruncatedAsText() {
    val input =
      mapOf(
        "note" to "api_key=abc123".toByteArray(),
        "blob" to ByteArray(10_000) { 'b'.code.toByte() },
      )

    val result = JsonFormatter.smartTruncate(input, 20)

    assertEquals("api_key=[REDACTED]", result.node.get("note").asText())
    assertEquals("bbbbbb...[TRUNCATED]", result.node.get("blob").asText())
    assertTrue(result.isTruncated)
  }

  @Test
  fun smartTruncate_nonUtf8Bytes_areReplacedAndFlagged() {
    val input = mapOf("blob" to byteArrayOf(0xFF.toByte(), 0xFE.toByte()))

    val result = JsonFormatter.smartTruncate(input, 100)

    assertEquals("[BINARY_DATA]", result.node.get("blob").asText())
    assertTrue(result.isTruncated)
  }

  @Test
  fun smartTruncate_utf8BytesWithinBudget_areNotFlagged() {
    val result = JsonFormatter.smartTruncate(mapOf("note" to "hello".toByteArray()), 100)

    assertEquals("hello", result.node.get("note").asText())
    assertFalse(result.isTruncated)
  }

  @Test
  fun smartTruncate_jsonInAString_isRedactedByKey() {
    val input = mapOf("note" to """{"token": {"access_token": "ya29.X"}, "region": "us"}""")

    val result = JsonFormatter.smartTruncate(input, 1000)

    assertEquals("""{"token":"[REDACTED]","region":"us"}""", result.node.get("note").asText())
    assertFalse(result.isTruncated)
  }

  @Test
  fun smartTruncate_jsonArrayInAString_isRedactedByKeyAndPattern() {
    val input = mapOf("items" to """[{"password": "p"}, "Bearer abc.def"]""")

    val result = JsonFormatter.smartTruncate(input, 1000)

    assertEquals(
      """[{"password":"[REDACTED]"},"Bearer [REDACTED]"]""",
      result.node.get("items").asText(),
    )
  }

  @Test
  fun smartTruncate_jsonInAStringWithNothingToRedact_isKeptAsWritten() {
    val json = """ {"region": "us", "count": 2}"""

    val result = JsonFormatter.smartTruncate(mapOf("note" to json), 1000)

    assertEquals(json, result.node.get("note").asText())
    assertFalse(result.isTruncated)
  }

  @Test
  fun smartTruncate_bracketLedStringThatDoesNotParse_isReplacedAndFlagged() {
    for (value in listOf("""{"token": {"access_token": "ya29.X"}""", "[INFO] not json")) {
      val result = JsonFormatter.smartTruncate(mapOf("note" to value), 1000)

      assertEquals("[UNPARSEABLE_JSON_BLOB]", result.node.get("note").asText())
      assertTrue(result.isTruncated)
    }
  }

  @Test
  fun smartTruncate_jsonInAStringOverTheBudget_isReplacedAndFlagged() {
    val json = """{"note": "${"a".repeat(50)}"}"""

    val result = JsonFormatter.smartTruncate(mapOf("note" to json), 20)

    assertEquals("[UNPARSEABLE_JSON_BLOB]", result.node.get("note").asText())
    assertTrue(result.isTruncated)
  }

  @Test
  fun smartTruncate_jsonInAStringWithADuplicateKey_isRewrittenWithoutTheDroppedValue() {
    val json = """{"auth": {"token": {"access_token": "ya29.X"}}, "auth": "ok"}"""

    val result = JsonFormatter.smartTruncate(mapOf("note" to json), 1000)

    assertEquals("""{"auth":"ok"}""", result.node.get("note").asText())
  }

  @Test
  fun smartTruncate_jsonInAStringWithTrailingText_isReplaced() {
    val text = """{"a": 1} {"token": {"access_token": "ya29.X"}}"""

    val result = JsonFormatter.smartTruncate(mapOf("note" to text), 1000)

    assertEquals("[UNPARSEABLE_JSON_BLOB]", result.node.get("note").asText())
  }

  @Test
  fun smartTruncate_markersItWrote_areKeptOnASecondPass() {
    val input =
      mapOf(
        "a" to "[BINARY_DATA]",
        "b" to "[UNPARSEABLE_JSON_BLOB]",
        "c" to "[REDACTED]",
        "d" to "[REDACTED_SENSITIVE_TEXT]",
      )

    val result = JsonFormatter.smartTruncate(input, 1000)

    assertEquals(
      """{"a":"[BINARY_DATA]","b":"[UNPARSEABLE_JSON_BLOB]","c":"[REDACTED]",""" +
        """"d":"[REDACTED_SENSITIVE_TEXT]"}""",
      result.node.toString(),
    )
    assertFalse(result.isTruncated)
  }

  @Test
  fun smartTruncateText_bracketLedProse_isKept() {
    for (text in listOf("[INFO] done", "[docs](https://example.com/guide)")) {
      val result = JsonFormatter.smartTruncateText(text, 1000)

      assertEquals(text, result.node.asText())
      assertFalse(result.isTruncated)
    }
  }

  @Test
  fun smartTruncateText_bracketLedProseWithACredential_isReplaced() {
    val result = JsonFormatter.smartTruncateText("[ERROR] Authorization: Bearer abc", 1000)

    assertEquals("[UNPARSEABLE_JSON_BLOB]", result.node.asText())
    assertTrue(result.isTruncated)
  }

  @Test
  fun smartTruncateText_bracketLedProseWithAnEscapedCredential_isReplaced() {
    val result = JsonFormatter.smartTruncateText("[INFO] retry with access%5Ftoken=ya29.X", 1000)

    assertEquals("[UNPARSEABLE_JSON_BLOB]", result.node.asText())
    assertTrue(result.isTruncated)
  }

  @Test
  fun smartTruncateText_jsonWithADuplicateKey_isRedactedByKey() {
    val text = """{"temp:my key": "S", "a": 1, "a": 2}"""

    val result = JsonFormatter.smartTruncateText(text, 1000)

    assertEquals("""{"temp:my key":"[REDACTED]","a":2}""", result.node.asText())
  }

  @Test
  fun smartTruncateText_json_isRedactedByKeyBeforeTheBudget() {
    val text = """{"token": "secret-1", "note": "${"b".repeat(50)}"}"""

    val result = JsonFormatter.smartTruncateText(text, 40)

    assertEquals("""{"token":"[REDACTED]","not...[TRUNCATED]""", result.node.asText())
    assertTrue(result.isTruncated)
  }

  @Test
  fun smartTruncate_cyclicCollections_replacesTheBackReference() {
    val outer = mutableMapOf<String, Any?>("name" to "outer")
    outer["children"] = mutableListOf<Any?>(outer)

    val result = JsonFormatter.smartTruncate(outer, 100)

    assertEquals("""{"name":"outer","children":["[CIRCULAR_REFERENCE]"]}""", result.node.toString())
    assertFalse(result.isTruncated)
  }

  private companion object {
    const val NO_LIMIT = Int.MAX_VALUE
  }
}
