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

package com.google.adk.kt.memory

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RagCorpusNamesTest {

  @Test
  fun normalizeRagCorpusName_expandsBareId() {
    assertEquals(
      "projects/proj/locations/loc/ragCorpora/my-corpus",
      normalizeRagCorpusName("my-corpus", "proj", "loc"),
    )
  }

  @Test
  fun normalizeRagCorpusName_rejectsFullResourceName() {
    // Assert the dedicated guard fires (not the generic segment check), pinning that branch.
    val e =
      assertFailsWith<IllegalArgumentException> {
        normalizeRagCorpusName("projects/proj/locations/loc/ragCorpora/z", "proj", "loc")
      }
    assertContains(e.message ?: "", "full resource name")
  }

  @Test
  fun normalizeRagCorpusName_rejectsInvalidProject() {
    val e =
      assertFailsWith<IllegalArgumentException> { normalizeRagCorpusName("c", "bad/proj", "loc") }
    assertContains(e.message ?: "", "project")
  }

  @Test
  fun normalizeRagCorpusName_rejectsInvalidLocation() {
    val e =
      assertFailsWith<IllegalArgumentException> { normalizeRagCorpusName("c", "proj", "bad/loc") }
    assertContains(e.message ?: "", "location")
  }

  @Test
  fun normalizeRagCorpusName_rejectsIdsThatEscapeThePathSegment() {
    // A bare id must stay within one URL path segment (no `/`, `?`, `#`, or `..`).
    for (bad in listOf("a/b", "a..b", "a?b", "a#b", "a b")) {
      assertFailsWith<IllegalArgumentException> { normalizeRagCorpusName(bad, "proj", "loc") }
    }
  }
}
