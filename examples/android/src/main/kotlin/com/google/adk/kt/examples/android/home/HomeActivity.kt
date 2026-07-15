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

package com.google.adk.kt.examples.android.home

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import com.google.adk.kt.examples.android.common.setExampleContentView
import com.google.adk.kt.examples.android.mlkitchat.MlKitChatActivity
import com.google.adk.kt.examples.android.roomsession.RoomSessionActivity
import com.google.adk.kt.examples.android.skillsassetsource.SkillsAssetSourceActivity

/**
 * Launcher screen for the ADK Android examples. Each button opens one self-contained example
 * [Activity]; the examples themselves are the interesting part — this menu just lets the whole set
 * ship as a single installable app.
 *
 * To add another example, write its [Activity], declare it in `AndroidManifest.xml`, and add a
 * button below.
 */
// Hardcoded UI strings are intentional in this minimal example; a real app would use resources.
@Suppress("SetTextI18n")
class HomeActivity : Activity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setExampleContentView("ADK Android examples", buildContent())
  }

  private fun buildContent(): LinearLayout =
    LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(24, 24, 24, 24)
      addView(
        exampleButton(
          "Room session — on-device agent, persistent across restarts",
          RoomSessionActivity::class.java,
        )
      )
      addView(
        exampleButton(
          "Skills (AssetSkillSource) — cloud Gemini with tool calling",
          SkillsAssetSourceActivity::class.java,
        )
      )
      addView(
        exampleButton(
          "ML Kit chat — on-device Gemini Nano, multi-turn, streaming toggle",
          MlKitChatActivity::class.java,
        )
      )
    }

  private fun exampleButton(label: String, activity: Class<out Activity>): Button =
    Button(this).apply {
      text = label
      setOnClickListener { startActivity(Intent(this@HomeActivity, activity)) }
    }
}
