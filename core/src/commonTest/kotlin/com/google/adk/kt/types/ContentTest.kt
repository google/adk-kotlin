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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Tests for [Content] and [Part] classes. */
class ContentTest {

  @Test
  fun testTextPartEquality() {
    val part1 = Part(text = "hello")
    val part2 = Part(text = "hello")
    val part3 = Part(text = "world")

    assertEquals(part1, part2)
    assertNotEquals(part1, part3)
  }

  @Test
  fun testBlobPartEquality() {
    val part1 =
      Part(
        inlineData = Blob(data = byteArrayOf(1, 2, 3), mimeType = "image/png", displayName = null)
      )
    val part2 =
      Part(
        inlineData = Blob(data = byteArrayOf(1, 2, 3), mimeType = "image/png", displayName = null)
      )
    val part3 =
      Part(
        inlineData = Blob(data = byteArrayOf(1, 2, 3), mimeType = "image/jpeg", displayName = null)
      )
    val part4 =
      Part(
        inlineData = Blob(data = byteArrayOf(1, 2, 4), mimeType = "image/png", displayName = null)
      )

    val blob1 = part1.inlineData!!
    val blob2 = part2.inlineData!!
    val blob3 = part3.inlineData!!
    val blob4 = part4.inlineData!!
    val data1 = blob1.data!!
    val data2 = blob2.data!!
    val data4 = blob4.data!!
    assertTrue(data1.contentEquals(data2))
    assertEquals("image/png", blob1.mimeType)
    assertEquals("image/jpeg", blob3.mimeType) // Different mime type
    assertTrue(!data1.contentEquals(data4)) // Different data
  }

  @Test
  fun testContentEquality() {
    val content1 = Content(role = Role.USER, parts = listOf(Part(text = "hello")))
    val content2 = Content(role = Role.USER, parts = listOf(Part(text = "hello")))

    val content3 = Content(role = Role.MODEL, parts = listOf(Part(text = "world")))

    assertEquals(content1, content2)
    assertNotEquals(content1, content3)
  }

  @Test
  fun fromText_setsRoleAndWrapsTextInSinglePart() {
    val content = Content.fromText(Role.USER, "hello world")

    assertEquals(Content(role = Role.USER, parts = listOf(Part(text = "hello world"))), content)
  }

  @Test
  fun text_joinsTextPartsWithSeparatorSkippingNonText() {
    val content =
      Content(
        role = Role.MODEL,
        parts =
          listOf(
            Part(text = "Hello"),
            Part(functionCall = FunctionCall(name = "tool")),
            Part(text = "world"),
          ),
      )

    assertEquals("Hello world", content.text(" "))
  }

  @Test
  fun text_defaultSeparatorIsEmpty() {
    // Matches the google-genai SDK: parts are fragments of one message, joined with no separator.
    val content = Content(parts = listOf(Part(text = "Hel"), Part(text = "lo")))

    assertEquals("Hello", content.text())
  }

  @Test
  fun text_emptyWhenNoTextParts() {
    assertEquals(
      "",
      Content(parts = listOf(Part(functionCall = FunctionCall(name = "t")))).text(" "),
    )
    assertEquals("", Content().text(" "))
  }

  @Test
  fun text_excludesThoughtPartsUnlessRequested() {
    val content =
      Content(parts = listOf(Part(text = "visible"), Part(text = "reason", thought = true)))

    assertEquals("visible", content.text(" "))
    assertEquals("visible reason", content.text(" ", includeThoughts = true))
  }
}
