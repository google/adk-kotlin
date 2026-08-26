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

package com.google.adk.kt.workflow

/**
 * A FIFO queue of pending triggers per node for the workflow scheduler.
 *
 * Each node maintains its own queue so multiple incoming triggers (such as parallel branches or
 * loops) are serviced in arrival order without interfering with other nodes.
 */
internal class TriggerQueue {
  private val queues = linkedMapOf<String, ArrayDeque<Trigger>>()

  /** The names of all nodes that currently have at least one pending trigger. */
  val queuedNodeNames: List<String>
    get() = queues.keys.toList()

  /** Enqueues [trigger] for [nodeName]. */
  fun enqueue(nodeName: String, trigger: Trigger) {
    queues.getOrPut(nodeName) { ArrayDeque() }.addLast(trigger)
  }

  /** Removes and returns the next pending trigger for [nodeName], or null if none. */
  fun poll(nodeName: String): Trigger? {
    val queue = queues[nodeName] ?: return null
    val trigger = queue.removeFirstOrNull() ?: return null
    if (queue.isEmpty()) {
      queues.remove(nodeName)
    }
    return trigger
  }
}
