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

package com.google.adk.kt.cli.builtinagents

import com.google.adk.kt.agents.Instruction
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.agents.ReadonlyContext
import com.google.adk.kt.models.Model
import com.google.adk.kt.tools.AgentTool
import com.google.adk.kt.tools.GoogleSearchTool
import com.google.adk.kt.tools.UrlContextTool
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.GenerateContentConfig
import com.google.adk.kt.types.Part
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The assistant this SDK ships for building agents with.
 *
 * Every other agent in ADK is one a user brings. This one arrives with the SDK, so a user who has
 * written nothing yet has something to open: it looks at the project directory the session names,
 * reads what is already there, and writes the agent document and tool sources the user is after.
 */
object AgentBuilderAssistant {

  /**
   * The assistant, running on [model].
   *
   * [model] also runs the two sub-agents it searches and fetches through, so a caller chooses one
   * model and gets one model.
   */
  fun createAgent(model: Model): LlmAgent =
    LlmAgent(
      name = "agent_builder_assistant",
      model = model,
      description =
        "Assistant that surveys the user's project and writes the agent document and tool " +
          "sources it needs",
      instruction =
        Instruction { context -> Content(parts = listOf(Part(text = instructionFor(context)))) },
      tools =
        listOf(
          // A built-in tool may not travel in the same request as a function declaration, and the
          // assistant's own tools are function declarations. Held directly, `google_search` would
          // make every turn of the conversation fail, so each built-in is given to a sub-agent and
          // reached through that. This is the same workaround GoogleSearchAgentTool documents.
          AgentTool(searchAgent(model)),
          AgentTool(urlFetchingAgent(model)),
          ExploreProjectTool(),
          ReadFilesTool(),
          WriteFilesTool(),
          DeleteFilesTool(),
          CleanupUnusedFilesTool(),
        ),
      generateContentConfig = GenerateContentConfig(maxOutputTokens = 8192),
    )

  /** A sub-agent whose only tool is Google Search. */
  private fun searchAgent(model: Model): LlmAgent =
    LlmAgent(
      name = "google_search_agent",
      model = model,
      description = "Searches the web for ADK examples, documentation and error messages",
      instruction =
        Instruction(
          "You search the web on behalf of the agent builder assistant.\n\n" +
            "Given a query, use the `google_search` tool to find ADK configuration examples, " +
            "multi-agent patterns, documentation and fixes for error messages. Prefer results " +
            "from github.com/google/adk-kotlin, github.com/google/adk-python and " +
            "github.com/google/adk-docs.\n\n" +
            "Report the URLs you found, what each one contains, and which are worth fetching in " +
            "full."
        ),
      tools = listOf(GoogleSearchTool()),
    )

  /** A sub-agent whose only tool is URL fetching. */
  private fun urlFetchingAgent(model: Model): LlmAgent =
    LlmAgent(
      name = "url_context_agent",
      model = model,
      description = "Fetches and analyses the content of a URL",
      instruction =
        Instruction(
          "You read web pages on behalf of the agent builder assistant.\n\n" +
            "Given a URL, use the `url_context` tool to fetch it, then report what it contains: " +
            "the agent configuration or code it shows, the patterns it uses, and anything in it " +
            "that contradicts what the caller was about to do."
        ),
      tools = listOf(UrlContextTool()),
    )

  /**
   * The instruction for this turn, naming the project the session is pointed at.
   *
   * Worked out per turn rather than fixed, because the project directory is session state and a
   * model told which folder it is working in stops asking.
   */
  @Suppress("GlobalCoroutineDispatchers") // Canonicalising a path stats the filesystem.
  private suspend fun instructionFor(context: ReadonlyContext): String {
    val name =
      withContext(Dispatchers.IO) { runCatching { projectRoot(context.state).name }.getOrNull() }
    val project = name?.ifEmpty { null } ?: "project"
    return """
      You are the ADK agent builder assistant. You help the user turn the project folder
      "$project" into a working ADK agent, and you work only inside that folder.

      The tools you have:
        - explore_project says what is in the project. Call it before anything else.
        - read_files hands you the contents of files you name.
        - write_files creates or replaces a file, at a path relative to the project.
        - delete_files removes files, and only ones the user has agreed to lose.
        - cleanup_unused_files names the source files nothing references. It deletes nothing.
        - google_search_agent and url_context_agent look things up on the web.

      What an agent looks like in this SDK. An agent document is a YAML file at the project
      root, conventionally root_agent.yaml, carrying `name`, `model`, `description`,
      `instruction` and a list of `tools`. Each entry under `tools` names a component the
      application registers at startup. Sub-agents, callbacks, and agent classes other than
      LlmAgent are not loaded from a document yet, so do not write them into one; an agent that
      needs them is written in Kotlin instead.

      How to work:
        1. Survey the project before proposing anything, and read a file before changing it.
        2. Tell the user what you are about to write and where, then write it.
        3. Ask before removing anything, and pass confirm_deletion only once they have agreed.
        4. If a tool reports an error, say what it was rather than trying the same call again.
      """
      .trimIndent()
  }
}
