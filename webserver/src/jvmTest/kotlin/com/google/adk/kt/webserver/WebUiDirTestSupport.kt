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

package com.google.adk.kt.webserver

/** The system property selecting an on-disk Development UI instead of the bundled one. */
internal const val WEB_UI_DIR_PROPERTY = "adk.web.ui.dir"

/** Runs [body] with [WEB_UI_DIR_PROPERTY] cleared, restoring whatever was set before. */
internal fun withoutWebUiDir(body: () -> Unit) = withWebUiDir(null, body)

/** Runs [body] with [WEB_UI_DIR_PROPERTY] set to [dir], or cleared when it is null. */
internal fun withWebUiDir(dir: String?, body: () -> Unit) {
  val previous: String? = System.getProperty(WEB_UI_DIR_PROPERTY)
  if (dir == null) System.clearProperty(WEB_UI_DIR_PROPERTY)
  else System.setProperty(WEB_UI_DIR_PROPERTY, dir)
  try {
    body()
  } finally {
    if (previous == null) System.clearProperty(WEB_UI_DIR_PROPERTY)
    else System.setProperty(WEB_UI_DIR_PROPERTY, previous)
  }
}
