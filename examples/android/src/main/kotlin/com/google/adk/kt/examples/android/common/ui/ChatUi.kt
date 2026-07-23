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

package com.google.adk.kt.examples.android.common.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

/** Who authored a line in the transcript. */
enum class ChatAuthor {
  USER,
  AGENT,
  /** A status/hint line (e.g. "Ready…", errors) rendered centered, not as a bubble. */
  SYSTEM,
}

/** One line in the chat transcript. [label] is the agent name shown above agent bubbles. */
data class ChatMessage(val author: ChatAuthor, val text: String, val label: String = "")

/**
 * Shared Material 3 chat screen used by every example: an app bar with a back button, a scrolling
 * list of message bubbles, and an input bar. When [onStreamingChange] is supplied a "Stream" toggle
 * is shown above the input (used by the ML Kit example).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
  title: String,
  messages: List<ChatMessage>,
  inputEnabled: Boolean,
  onSend: (String) -> Unit,
  onBack: () -> Unit,
  hint: String = "Type a message…",
  streaming: Boolean = false,
  onStreamingChange: ((Boolean) -> Unit)? = null,
) {
  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(title) },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
          }
        },
      )
    }
  ) { padding ->
    Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
      MessageList(messages, Modifier.weight(1f))
      if (onStreamingChange != null) {
        StreamToggle(streaming, onStreamingChange)
      }
      ChatInputBar(inputEnabled, hint, onSend)
    }
  }
}

@Composable
private fun MessageList(messages: List<ChatMessage>, modifier: Modifier = Modifier) {
  val listState = rememberLazyListState()
  LaunchedEffect(messages.size) {
    if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
  }
  LazyColumn(
    state = listState,
    modifier = modifier.fillMaxWidth(),
    contentPadding = PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    items(messages.size) { index -> MessageRow(messages[index]) }
  }
}

@Composable
private fun MessageRow(message: ChatMessage) {
  if (message.author == ChatAuthor.SYSTEM) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
      Text(
        message.text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    return
  }

  val isUser = message.author == ChatAuthor.USER
  val bubbleColor =
    if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
  val contentColor =
    if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
  val shape =
    if (isUser) RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp)
    else RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp)
  Row(
    Modifier.fillMaxWidth(),
    horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
  ) {
    Surface(color = bubbleColor, contentColor = contentColor, shape = shape) {
      Column(Modifier.widthIn(max = 320.dp).padding(horizontal = 14.dp, vertical = 10.dp)) {
        if (!isUser && message.label.isNotEmpty()) {
          Text(
            message.label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
          )
          Spacer(Modifier.padding(top = 2.dp))
        }
        Text(message.text, style = MaterialTheme.typography.bodyLarge)
      }
    }
  }
}

@Composable
private fun StreamToggle(streaming: Boolean, onStreamingChange: (Boolean) -> Unit) {
  Row(
    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      "Stream responses",
      style = MaterialTheme.typography.bodyMedium,
      modifier = Modifier.weight(1f),
    )
    Switch(checked = streaming, onCheckedChange = onStreamingChange)
  }
}

@Composable
private fun ChatInputBar(enabled: Boolean, hint: String, onSend: (String) -> Unit) {
  var text by remember { mutableStateOf("") }
  val submit = {
    val trimmed = text.trim()
    if (trimmed.isNotEmpty()) {
      onSend(trimmed)
      text = ""
    }
  }
  Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
    Row(
      Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
      verticalAlignment = Alignment.Bottom,
    ) {
      OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        modifier = Modifier.weight(1f),
        placeholder = { Text(hint) },
        enabled = enabled,
        maxLines = 5,
        shape = RoundedCornerShape(24.dp),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = { submit() }),
      )
      Spacer(Modifier.padding(start = 8.dp))
      FilledIconButton(
        onClick = submit,
        enabled = enabled && text.isNotBlank(),
        modifier = Modifier.padding(bottom = 4.dp),
      ) {
        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
      }
    }
  }
}
