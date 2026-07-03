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

package com.google.adk.kt.testing

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for [DummyTracer] verifying span recording and hierarchy tracking. */
class DummyTracerTest {

  private val dummyTracer = DummyTracer()

  @Test
  fun testSpanRecordingAndAttributes() {
    val tracer = dummyTracer
    val builder = tracer.spanBuilder("test-span")
    builder["key1"] = "value1"
    builder["key2"] = 42L
    val span = builder.startSpan()

    span.end()

    assertEquals(1, dummyTracer.recordedSpans.size)
    val recordedSpan = dummyTracer.recordedSpans[0]
    assertEquals("test-span", recordedSpan.name)
    assertEquals("value1", recordedSpan.attributes["key1"])
    assertEquals(42L, recordedSpan.attributes["key2"])
    assertTrue(recordedSpan.isEnded)
  }

  @Test
  fun testSpanEvents() {
    val tracer = dummyTracer
    val span = tracer.spanBuilder("test-span").startSpan()

    span.addEvent("event1")
    span.addEvent("event2")
    span.end()

    val recordedSpan = dummyTracer.recordedSpans[0]
    assertEquals(listOf("event1", "event2"), recordedSpan.events)
  }

  @Test
  fun testHierarchyTracking() = runBlocking {
    val tracer = dummyTracer

    // Create parent span
    val parentSpan = tracer.spanBuilder("parent").startSpan()
    val parentContext = tracer.contextWithSpan(parentSpan)

    // Create child span with parent context
    val childSpan = tracer.spanBuilder("child").setParent(parentContext).startSpan()

    childSpan.end()
    parentSpan.end()

    assertEquals(2, dummyTracer.recordedSpans.size)
    val recordedChild = dummyTracer.recordedSpans.find { it.name == "child" }
    val recordedParent = dummyTracer.recordedSpans.find { it.name == "parent" }

    assertTrue(recordedChild != null)
    assertTrue(recordedParent != null)
    assertEquals(recordedParent, recordedChild!!.parentSpan)
    assertNull(recordedParent!!.parentSpan)
  }

  @Test
  fun testSpanAttributesSetAfterStart() {
    val tracer = dummyTracer
    val span = tracer.spanBuilder("test-span").startSpan()

    span["key1"] = "value1"
    span["key2"] = 42L
    span.end()

    assertEquals(1, dummyTracer.recordedSpans.size)
    val recordedSpan = dummyTracer.recordedSpans[0]
    assertEquals("value1", recordedSpan.attributes["key1"])
    assertEquals(42L, recordedSpan.attributes["key2"])
  }
}
