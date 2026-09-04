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

package com.google.adk.kt.tools.appfunctions

import android.app.PendingIntent
import android.content.Intent
import androidx.appfunctions.AppFunction
import androidx.appfunctions.AppFunctionSerializable
import androidx.appfunctions.AppFunctionService
import androidx.appfunctions.AppFunctionServiceEntryPoint

/** A note, so the end-to-end test covers an object return rather than only a scalar. */
@AppFunctionSerializable data class TestNote(val title: String, val pages: Int)

/**
 * App functions this test APK declares about itself, so the toolset can discover and call them
 * without any permission.
 *
 * The compiler generates `TestAppFunctionService` from this class, and running these tests needs a
 * manifest registering it as a service. A function declared here takes no `AppFunctionContext`
 * parameter: the generated invoker would read one straight from the caller's arguments.
 */
@AppFunctionServiceEntryPoint(
  serviceName = "TestAppFunctionService",
  appFunctionXmlFileName = "adk_test_app_functions",
)
abstract class BaseTestAppFunctionService : AppFunctionService() {

  /**
   * Creates a note.
   *
   * @param title The title of the note.
   * @param pages How many pages the note has.
   */
  @AppFunction
  suspend fun createNote(title: String, pages: Int): TestNote =
    TestNote(title = title, pages = pages)

  /**
   * Returns the text it was given.
   *
   * @param value The text to return.
   */
  @AppFunction suspend fun echo(value: String): String = value

  /**
   * Returns a screen for the caller to open, which is the over-the-app half of AppFunctions.
   *
   * @param noteId Which note to show.
   */
  @AppFunction
  suspend fun showNote(noteId: String): PendingIntent =
    PendingIntent.getActivity(
      this,
      0,
      Intent(SHOW_NOTE_ACTION).setPackage(packageName).putExtra("noteId", noteId),
      PendingIntent.FLAG_IMMUTABLE,
    )

  companion object {
    const val SHOW_NOTE_ACTION: String = "com.google.adk.kt.tools.appfunctions.SHOW_NOTE"
  }
}
