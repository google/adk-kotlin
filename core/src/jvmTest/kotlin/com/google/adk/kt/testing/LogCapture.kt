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

import java.util.Collections
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

/**
 * Runs [block] and returns its result with every record at [level] that the logger named
 * [loggerName] got meanwhile. ADK's logging facade delegates to Flogger, which is backed by
 * `java.util.logging` and names each logger after the class it was created for, with `$` replaced
 * by `.`.
 */
inline fun <T> capturingLogs(
  loggerName: String,
  level: Level,
  block: () -> T,
): Pair<T, List<LogRecord>> {
  // publish() runs on whichever thread logged, so this cannot be a plain list.
  val records = Collections.synchronizedList(mutableListOf<LogRecord>())
  val handler =
    object : Handler() {
      override fun publish(record: LogRecord?) {
        if (record?.level == level) records.add(record)
      }

      override fun flush() {}

      override fun close() {}
    }
  val logger = Logger.getLogger(loggerName.replace('$', '.'))
  logger.addHandler(handler)
  val result =
    try {
      block()
    } finally {
      logger.removeHandler(handler)
    }
  return result to synchronized(records) { records.toList() }
}
