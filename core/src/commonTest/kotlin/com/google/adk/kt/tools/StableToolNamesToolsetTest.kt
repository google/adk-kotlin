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

package com.google.adk.kt.tools

import com.google.adk.kt.agents.ReadonlyContext
import com.google.adk.kt.agents.toReadonlyContext
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.testing.testInvocationContext
import com.google.adk.kt.testing.testSession
import com.google.adk.kt.testing.testToolContext
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.FunctionDeclaration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** Unit tests for [StableToolNamesToolset]. */
class StableToolNamesToolsetTest {

  /**
   * A tool that declares itself, so a retained copy can be told apart by its missing declaration.
   */
  private class DeclaredTool(name: String, private val result: String) :
    BaseTool(name = name, description = "declared") {
    override fun declaration(): FunctionDeclaration =
      FunctionDeclaration(name = name, description = description)

    override suspend fun run(context: ToolContext, args: Map<String, Any?>): Any =
      mapOf("ran" to result)
  }

  /** A toolset whose catalog the test rewrites between turns. */
  private class MutableToolset(var tools: List<BaseTool>) : Toolset {
    var closed = false

    override suspend fun getTools(readonlyContext: ReadonlyContext?): List<BaseTool> = tools

    override fun close() {
      closed = true
    }
  }

  /** Takes a turn only to let the toolset record the names it hands out. */
  private suspend fun StableToolNamesToolset.issueNames(context: ReadonlyContext?) {
    val unused = getTools(context)
  }

  private fun contextFor(sessionId: String?): ReadonlyContext =
    testInvocationContext(session = testSession(id = sessionId)).toReadonlyContext()

  private val sessionA = contextFor("session_a")
  private val sessionB = contextFor("session_b")

  @Test
  fun getTools_toolRenamedBetweenTurns_stillReturnsThePreviousName() = runBlocking {
    val delegate = MutableToolset(listOf(DeclaredTool("searchContacts", "contacts")))
    val toolset = StableToolNamesToolset(delegate)
    toolset.issueNames(sessionA)

    delegate.tools = listOf(DeclaredTool("find_contacts", "contacts"))
    val secondTurn = toolset.getTools(sessionA)

    assertEquals(listOf("find_contacts", "searchContacts"), secondTurn.map { it.name })
  }

  @Test
  fun getTools_retainedName_isNotDeclaredToTheModel() = runBlocking {
    val delegate = MutableToolset(listOf(DeclaredTool("searchContacts", "contacts")))
    val toolset = StableToolNamesToolset(delegate)
    toolset.issueNames(sessionA)

    delegate.tools = listOf(DeclaredTool("find_contacts", "contacts"))
    val secondTurn = toolset.getTools(sessionA)

    // The live tool is declared to the model; the retained name is reachable but not declared.
    assertNotNull(secondTurn.single { it.name == "find_contacts" }.declaration())
    assertNull(secondTurn.single { it.name == "searchContacts" }.declaration())
  }

  @Test
  fun getTools_retainedName_isKeptOutOfTheRequest() = runBlocking {
    val delegate = MutableToolset(listOf(DeclaredTool("searchContacts", "contacts")))
    val toolset = StableToolNamesToolset(delegate)
    toolset.issueNames(sessionA)
    delegate.tools = listOf(DeclaredTool("find_contacts", "contacts"))

    val request =
      toolset.getTools(sessionA).fold(LlmRequest()) { req, tool ->
        tool.processLlmRequest(testToolContext(), req)
      }

    val declaredNames =
      request.config.tools.orEmpty().flatMap { it.functionDeclarations.orEmpty() }.map { it.name }
    assertEquals(listOf("find_contacts"), declaredNames)
    // It still has to be resolvable, which is what the tool map is built from.
    assertTrue(request.toolsDict.any { it.name == "searchContacts" })
  }

  @Test
  fun getTools_retainedName_runsTheToolItWasIssuedFor() = runBlocking {
    val delegate = MutableToolset(listOf(DeclaredTool("searchContacts", "contacts")))
    val toolset = StableToolNamesToolset(delegate)
    toolset.issueNames(sessionA)
    delegate.tools = listOf(DeclaredTool("find_contacts", "contacts"))

    val retained = toolset.getTools(sessionA).single { it.name == "searchContacts" }

    assertEquals(mapOf("ran" to "contacts"), retained.run(testToolContext(), emptyMap()))
  }

  @Test
  fun getTools_nameThatWasNeverIssued_isNotReturned() = runBlocking {
    val delegate = MutableToolset(listOf(DeclaredTool("searchContacts", "contacts")))
    val toolset = StableToolNamesToolset(delegate)
    toolset.issueNames(sessionA)
    delegate.tools = listOf(DeclaredTool("find_contacts", "contacts"))

    val names = toolset.getTools(sessionA).map { it.name }

    // Nothing is inferred from a name's shape: only the exact strings already handed out come back.
    assertTrue(names.none { it == "search_contacts" || it == "searchContact" || it == "contacts" })
  }

  @Test
  fun getTools_anotherSession_doesNotSeeTheRetainedName() = runBlocking {
    val delegate = MutableToolset(listOf(DeclaredTool("searchContacts", "contacts")))
    val toolset = StableToolNamesToolset(delegate)
    toolset.issueNames(sessionA)

    delegate.tools = listOf(DeclaredTool("find_contacts", "contacts"))

    assertEquals(listOf("find_contacts"), toolset.getTools(sessionB).map { it.name })
    assertEquals(
      listOf("find_contacts", "searchContacts"),
      toolset.getTools(sessionA).map { it.name },
    )
  }

  @Test
  fun getTools_nameReissuedForAnotherTool_retainsTheToolItWasLastIssuedFor() = runBlocking {
    val delegate = MutableToolset(listOf(DeclaredTool("searchContacts", "chat_app")))
    val toolset = StableToolNamesToolset(delegate)
    toolset.issueNames(sessionA)
    delegate.tools = listOf(DeclaredTool("searchContacts", "mail_app"))
    toolset.issueNames(sessionA)

    delegate.tools = listOf(DeclaredTool("find_contacts", "mail_app"))
    val retained = toolset.getTools(sessionA).single { it.name == "searchContacts" }

    assertEquals(mapOf("ran" to "mail_app"), retained.run(testToolContext(), emptyMap()))
  }

  @Test
  fun getTools_nameStillIssuedByTheDelegate_isNotShadowed() = runBlocking {
    val delegate = MutableToolset(listOf(DeclaredTool("searchContacts", "first")))
    val toolset = StableToolNamesToolset(delegate)
    toolset.issueNames(sessionA)

    delegate.tools = listOf(DeclaredTool("searchContacts", "second"))
    val secondTurn = toolset.getTools(sessionA)

    assertEquals(listOf("searchContacts"), secondTurn.map { it.name })
    assertNotNull(secondTurn.single().declaration())
    assertEquals(mapOf("ran" to "second"), secondTurn.single().run(testToolContext(), emptyMap()))
  }

  @Test
  fun getTools_moreNamesThanTheLimit_dropsTheNameIssuedLongestAgo() = runBlocking {
    val delegate = MutableToolset(listOf(DeclaredTool("first", "a")))
    val toolset = StableToolNamesToolset(delegate, maxRetainedNamesPerSession = 1)
    toolset.issueNames(sessionA)
    delegate.tools = listOf(DeclaredTool("second", "b"))
    toolset.issueNames(sessionA)

    delegate.tools = listOf(DeclaredTool("third", "c"))
    val names = toolset.getTools(sessionA).map { it.name }

    assertEquals(listOf("third", "second"), names)
  }

  @Test
  fun getTools_asManyLiveToolsAsTheLimit_stillRetainsTheRenamedName() = runBlocking {
    // The limit bounds the names being kept alive, so a catalog at least that large does not
    // spend it and silently turn retention off.
    val delegate =
      MutableToolset(listOf(DeclaredTool("searchContacts", "contacts"), DeclaredTool("dial", "d")))
    val toolset = StableToolNamesToolset(delegate, maxRetainedNamesPerSession = 2)
    toolset.issueNames(sessionA)

    delegate.tools = listOf(DeclaredTool("find_contacts", "contacts"), DeclaredTool("dial", "d"))
    val names = toolset.getTools(sessionA).map { it.name }

    assertEquals(listOf("find_contacts", "dial", "searchContacts"), names)
  }

  @Test
  fun getTools_moreSessionsThanTheLimit_dropsTheSessionIdleLongest() = runBlocking {
    val delegate = MutableToolset(listOf(DeclaredTool("searchContacts", "contacts")))
    val toolset = StableToolNamesToolset(delegate, maxRetainedSessions = 1)
    toolset.issueNames(sessionA)
    toolset.issueNames(sessionB)

    delegate.tools = listOf(DeclaredTool("find_contacts", "contacts"))

    assertEquals(listOf("find_contacts"), toolset.getTools(sessionA).map { it.name })
  }

  @Test
  fun close_closesTheDelegate() = runBlocking {
    val delegate = MutableToolset(listOf(DeclaredTool("searchContacts", "contacts")))
    val toolset = StableToolNamesToolset(delegate)
    toolset.issueNames(sessionA)

    toolset.close()

    assertTrue(delegate.closed)
  }

  @Test
  fun getTools_withoutASessionId_returnsOnlyTheLiveTools() = runBlocking {
    val delegate = MutableToolset(listOf(DeclaredTool("searchContacts", "contacts")))
    val toolset = StableToolNamesToolset(delegate)
    // A session the service has not created yet has no id to scope retention to.
    toolset.issueNames(contextFor(null))
    toolset.issueNames(null)

    delegate.tools = listOf(DeclaredTool("find_contacts", "contacts"))

    assertEquals(listOf("find_contacts"), toolset.getTools(contextFor(null)).map { it.name })
    assertEquals(listOf("find_contacts"), toolset.getTools(null).map { it.name })
  }

  @Test
  fun executeSingleFunctionCall_nameRetainedFromAnEarlierTurn_reachesTheTool() = runBlocking {
    // The end of the path the model's call actually takes: the tool map is keyed by tool name.
    val delegate = MutableToolset(listOf(DeclaredTool("searchContacts", "contacts")))
    val toolset = StableToolNamesToolset(delegate)
    val context = testInvocationContext(session = testSession(id = "session_a"))
    toolset.issueNames(context.toReadonlyContext())
    delegate.tools = listOf(DeclaredTool("find_contacts", "contacts"))
    val tools = toolset.getTools(context.toReadonlyContext()).associateBy { it.name }

    val event =
      context.executeSingleFunctionCall(
        FunctionCall(name = "searchContacts", args = emptyMap(), id = "call_id"),
        tools,
      )

    val functionResponse = assertNotNull(event).content?.parts?.get(0)?.functionResponse
    assertEquals("searchContacts", assertNotNull(functionResponse).name)
    assertEquals(mapOf("ran" to "contacts"), functionResponse.response)
  }

  @Test
  fun executeSingleFunctionCall_nameThatWasNeverIssued_answersToolNotFound() = runBlocking {
    // A name this toolset never handed out gets no special treatment: it takes the same
    // tool-not-found answer any unregistered name does.
    val delegate = MutableToolset(listOf(DeclaredTool("searchContacts", "contacts")))
    val toolset = StableToolNamesToolset(delegate)
    val context = testInvocationContext(session = testSession(id = "session_a"))
    toolset.issueNames(context.toReadonlyContext())
    delegate.tools = listOf(DeclaredTool("find_contacts", "contacts"))
    val tools = toolset.getTools(context.toReadonlyContext()).associateBy { it.name }

    val event =
      context.executeSingleFunctionCall(
        FunctionCall(name = "lookupContacts", args = emptyMap(), id = "call_id"),
        tools,
      )

    val functionResponse = assertNotNull(event).content?.parts?.get(0)?.functionResponse
    assertEquals("lookupContacts", assertNotNull(functionResponse).name)
    val error = assertNotNull(functionResponse.response)["error"].toString()
    assertTrue(error.startsWith("Invoking `lookupContacts()` failed"))
    // The retained name is offered back as a tool it could call instead.
    assertTrue(error.contains("searchContacts"))
  }
}
