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
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.adk.kt.examples.android.common.ui.AdkExamplesTheme
import com.google.adk.kt.examples.android.firebase.FirebaseChatActivity
import com.google.adk.kt.examples.android.mlkitchat.MlKitChatActivity
import com.google.adk.kt.examples.android.roomsession.RoomSessionActivity
import com.google.adk.kt.examples.android.skillsassetsource.SkillsAssetSourceActivity

/**
 * Launcher screen for the ADK Android examples. Each card opens one self-contained example
 * [Activity]; the examples themselves are the interesting part — this menu just lets the whole set
 * ship as a single installable app.
 *
 * To add another example, write its [Activity], declare it in `AndroidManifest.xml`, and add an
 * [ExampleEntry] below.
 */
class HomeActivity : ComponentActivity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      AdkExamplesTheme {
        HomeScreen(EXAMPLES) { entry -> startActivity(Intent(this, entry.activity)) }
      }
    }
  }

  private companion object {
    val EXAMPLES =
      listOf(
        ExampleEntry(
          "Room session",
          "On-device agent whose conversation persists across app restarts",
          RoomSessionActivity::class.java,
        ),
        ExampleEntry(
          "Skills (AssetSkillSource)",
          "Cloud Gemini (Firebase AI) with a toolset reading skills from APK assets",
          SkillsAssetSourceActivity::class.java,
        ),
        ExampleEntry(
          "ML Kit chat",
          "On-device Gemini Nano, multi-turn, with a streaming toggle",
          MlKitChatActivity::class.java,
        ),
        ExampleEntry(
          "Firebase AI",
          "Cloud Gemini (Firebase AI Logic): plain chat plus tool calling",
          FirebaseChatActivity::class.java,
        ),
      )
  }
}

/** One entry in the launcher list: a title, a one-line description, and the screen it opens. */
private class ExampleEntry(
  val title: String,
  val subtitle: String,
  val activity: Class<out Activity>,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(examples: List<ExampleEntry>, onOpen: (ExampleEntry) -> Unit) {
  Scaffold(topBar = { TopAppBar(title = { Text("ADK Android examples") }) }) { padding ->
    LazyColumn(
      modifier = Modifier.fillMaxSize().padding(padding),
      contentPadding = PaddingValues(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      items(examples) { example -> ExampleCard(example) { onOpen(example) } }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExampleCard(example: ExampleEntry, onClick: () -> Unit) {
  ElevatedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(20.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text(example.title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
          example.subtitle,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      Spacer(Modifier.width(12.dp))
      Icon(
        Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}
