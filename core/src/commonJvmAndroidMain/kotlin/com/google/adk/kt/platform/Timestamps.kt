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

package com.google.adk.kt.platform

import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.time.Instant
import kotlin.time.toJavaInstant

private val COMPACT_TIMESTAMP: DateTimeFormatter =
  DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.ROOT)

internal actual fun formatCompactTimestamp(instant: Instant, zoneId: String?): String {
  val zone = zoneId?.let(ZoneId::of) ?: ZoneId.systemDefault()
  return COMPACT_TIMESTAMP.withZone(zone).format(instant.toJavaInstant())
}
