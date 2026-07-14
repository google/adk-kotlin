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

package com.google.adk.kt.examples.android.common

import android.app.Activity
import android.view.View
import android.widget.LinearLayout
import android.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Sets the activity's content to the shared example layout: a [Toolbar] app bar above [content],
 * with window insets applied so nothing is hidden behind the system bars or the on-screen keyboard.
 *
 * Requirements:
 * - The activity theme must be a `...NoActionBar` variant; otherwise [Activity.setActionBar] fails
 *   because the window decor already supplies an action bar.
 */
fun Activity.setExampleContentView(title: String, content: View) {
  val toolbar =
    Toolbar(this).apply {
      this.title = title
      // Hardcoded so the sample needs no resource files; a real app would style this via a theme.
      setBackgroundColor(0xFF1A73E8.toInt())
      setTitleTextColor(0xFFFFFFFF.toInt())
    }
  val root =
    LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      addView(
        toolbar,
        LinearLayout.LayoutParams(
          LinearLayout.LayoutParams.MATCH_PARENT,
          LinearLayout.LayoutParams.WRAP_CONTENT,
        ),
      )
      // Weight 1 so the content fills the space below the toolbar.
      addView(content, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
    }
  // Edge-to-edge: pad the root by the system-bar + keyboard insets so content stays clear of them.
  ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
    val bars =
      insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
    view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
    insets
  }
  setContentView(root)
  setActionBar(toolbar)
}
