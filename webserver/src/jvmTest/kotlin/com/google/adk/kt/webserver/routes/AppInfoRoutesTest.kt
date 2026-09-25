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

package com.google.adk.kt.webserver.routes

import com.google.adk.kt.agents.BaseAgent
import com.google.adk.kt.agents.Instruction
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.agents.ReadonlyContext
import com.google.adk.kt.agents.SequentialAgent
import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.models.Model
import com.google.adk.kt.serialization.adkJson
import com.google.adk.kt.tools.AgentTool
import com.google.adk.kt.tools.BaseTool
import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.tools.Toolset
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FunctionDeclaration
import com.google.adk.kt.types.Part
import com.google.adk.kt.webserver.buildAppInfo
import com.google.adk.kt.webserver.loaders.AgentLoader
import com.google.adk.kt.webserver.models.AppInfo
import com.google.common.truth.Truth.assertThat
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * `/apps/{appName}/app-info` reports every LLM agent the root reaches.
 *
 * An agent hides behind three edges - sub-agents, an agent tool, and an agent tool a toolset
 * returns - and a non-LLM agent on any of them must be traversed through, not treated as a leaf.
 */
@OptIn(FrameworkInternalApi::class)
@RunWith(JUnit4::class)
class AppInfoRoutesTest {

  @Test
  fun appInfo_nestedUnderWorkflowAgent_isReported() = testApplication {
    val nested = llmAgent("nested")
    val root =
      llmAgent("root", subAgents = listOf(SequentialAgent("pipeline", subAgents = listOf(nested))))
    installAppInfo(root)

    val info = getAppInfo()

    assertThat(info.agents.keys).containsExactly("root", "nested")
    // The workflow agent is transparent, so the chain from root to nested is not broken.
    assertThat(info.agents.getValue("root").subAgents).containsExactly("nested")
  }

  @Test
  fun appInfo_workflowRoot_returnsOkWithItsDescendants() = testApplication {
    val leaf = llmAgent("leaf")
    val root = SequentialAgent("pipeline", subAgents = listOf(leaf))
    installAppInfo(root)

    val response = client.get(APP_INFO_URL)

    // A non-LLM root is reported as the root without being an entry of its own.
    assertThat(response.status).isEqualTo(HttpStatusCode.OK)
    val info = adkJson.decodeFromString<AppInfo>(response.bodyAsText())
    assertThat(info.rootAgentName).isEqualTo("pipeline")
    assertThat(info.agents.keys).containsExactly("leaf")
    // The app is named by the path, not by its root agent.
    assertThat(info.name).isEqualTo("demo")
    assertThat(info.description).isEqualTo(root.description)
    assertThat(info.language).isEqualTo("kotlin")
  }

  @Test
  fun appInfo_agentWrappedInTool_isReported() = testApplication {
    val wrapped = llmAgent("wrapped")
    installAppInfo(llmAgent("root", tools = listOf(AgentTool(wrapped))))

    val info = getAppInfo()

    assertThat(info.agents.keys).containsExactly("root", "wrapped")
    // A tool edge reports the agent without claiming it as a child.
    assertThat(info.agents.getValue("root").subAgents).isEmpty()
  }

  @Test
  fun appInfo_workflowWrappedInTool_reportsItsLlmDescendants() = testApplication {
    val nested = llmAgent("nested")
    val pipeline = SequentialAgent("pipeline", subAgents = listOf(nested))
    installAppInfo(llmAgent("root", tools = listOf(AgentTool(pipeline))))

    val info = getAppInfo()

    assertThat(info.agents.keys).containsExactly("root", "nested")
  }

  @Test
  fun appInfo_agentToolFromToolset_isReported() = testApplication {
    val wrapped = llmAgent("wrapped")
    val toolset = FakeToolset(listOf(AgentTool(wrapped)))
    installAppInfo(llmAgent("root", toolsets = listOf(toolset)))

    val info = getAppInfo()

    assertThat(info.agents.keys).containsExactly("root", "wrapped")
    assertThat(info.agents.getValue("root").subAgents).isEmpty()
    // No invocation is in flight, so the toolset must be asked for everything it has.
    assertThat(toolset.contexts).containsExactly(null)
  }

  @Test
  fun appInfo_cycleThroughToolEdge_terminates() = testApplication {
    val toolset = CyclicToolset()
    val root = llmAgent("root", toolsets = listOf(toolset))
    toolset.target = root
    installAppInfo(root)

    val info = getAppInfo()

    assertThat(info.agents.keys).containsExactly("root")
  }

  @Test
  fun appInfo_cycleThroughMutatedSubAgents_terminates() = testApplication {
    val subAgents = mutableListOf<BaseAgent>()
    val pipeline = SequentialAgent("pipeline", subAgents = subAgents)
    // Mutated after construction, which is how a sub-agent cycle escapes the parent check.
    subAgents += pipeline
    subAgents += llmAgent("leaf")
    installAppInfo(llmAgent("root", subAgents = listOf(pipeline)))

    val info = getAppInfo()

    assertThat(info.agents.keys).containsExactly("root", "leaf")
    assertThat(info.agents.getValue("root").subAgents).containsExactly("leaf")
  }

  @Test
  fun appInfo_duplicateNames_reportsTheFirstReached() = testApplication {
    val first = llmAgent("twin", description = "first")
    val second = llmAgent("twin", description = "second")
    installAppInfo(llmAgent("root", subAgents = listOf(first, second)))

    val info = getAppInfo()

    assertThat(info.agents.keys).containsExactly("root", "twin")
    assertThat(info.agents.getValue("twin").description).isEqualTo("first")
    // One entry per name, so the parent names it once rather than once per agent.
    assertThat(info.agents.getValue("root").subAgents).containsExactly("twin")
  }

  @Test
  fun appInfo_toolsets_contributeTheirDeclarations() = testApplication {
    installAppInfo(
      llmAgent(
        "root",
        tools = listOf(FakeTool("declared")),
        toolsets = listOf(FakeToolset(listOf(FakeTool("from_toolset")))),
      )
    )

    val info = getAppInfo()

    assertThat(info.agents.getValue("root").tools.flatMap { it.functionDeclarations.orEmpty() })
      .containsExactly(declarationOf("declared"), declarationOf("from_toolset"))
  }

  @Test
  fun appInfo_toolWithoutDeclaration_isOmitted() = testApplication {
    installAppInfo(llmAgent("root", tools = listOf(FakeTool("undeclared", declaration = null))))

    val info = getAppInfo()

    assertThat(info.agents.getValue("root").tools).isEmpty()
  }

  @Test
  fun appInfo_toolsetThatFails_stillReportsTheRest() = testApplication {
    installAppInfo(
      llmAgent("root", tools = listOf(FakeTool("declared")), toolsets = listOf(FailingToolset()))
    )

    val response = client.get(APP_INFO_URL)

    // A toolset that cannot be enumerated degrades the answer rather than failing it.
    assertThat(response.status).isEqualTo(HttpStatusCode.OK)
    val info = adkJson.decodeFromString<AppInfo>(response.bodyAsText())
    assertThat(info.agents.getValue("root").tools.flatMap { it.functionDeclarations.orEmpty() })
      .containsExactly(declarationOf("declared"))
  }

  @Test
  fun appInfo_toolsetThatTimesOutItself_stillReportsTheRest() = testApplication {
    installAppInfo(
      llmAgent("root", tools = listOf(FakeTool("declared")), toolsets = listOf(TimingOutToolset()))
    )

    val response = client.get(APP_INFO_URL)

    // The toolset's own timeout cancelled the toolset, not this request.
    assertThat(response.status).isEqualTo(HttpStatusCode.OK)
    val info = adkJson.decodeFromString<AppInfo>(response.bodyAsText())
    assertThat(info.agents.getValue("root").tools.flatMap { it.functionDeclarations.orEmpty() })
      .containsExactly(declarationOf("declared"))
  }

  @Test
  fun appInfo_whenTheRequestIsCancelled_doesNotDegradeToAPartialAnswer() {
    val root = llmAgent("root", toolsets = listOf(HangingToolset()))
    var produced: AppInfo? = null

    assertThrows(TimeoutCancellationException::class.java) {
      runBlocking { withTimeout(50.milliseconds) { produced = buildAppInfo("demo", root) } }
    }

    // withTimeout throws either way, so the answer itself is what says the walk gave up.
    assertThat(produced).isNull()
  }

  @Test
  fun appInfo_instructionProvider_isReportedWithoutBeingInvoked() = testApplication {
    val lambda = Instruction.Provider { error("app-info must not resolve an instruction") }
    installAppInfo(
      llmAgent(
        "root",
        instruction = lambda,
        subAgents = listOf(llmAgent("named", instruction = NamedProvider())),
      )
    )

    val info = getAppInfo()

    // A lambda's runtime name is synthetic, so only a source-level name is worth reporting.
    assertThat(info.agents.getValue("root").instruction).isEqualTo("<InstructionProvider>")
    assertThat(info.agents.getValue("named").instruction)
      .isEqualTo("<InstructionProvider: NamedProvider>")
  }

  @Test
  fun appInfo_staticInstruction_isReportedAheadOfTheTurnInstruction() = testApplication {
    val static = Content(role = "user", parts = listOf(Part(text = "static")))
    installAppInfo(
      llmAgent(
        "root",
        instruction = Instruction.Text("per-turn"),
        staticInstruction = static,
        subAgents = listOf(llmAgent("static_only", staticInstruction = static)),
      )
    )

    val info = getAppInfo()

    // Static content is the system instruction whenever it is set, so omitting it hides the prompt.
    assertThat(info.agents.getValue("root").instruction).isEqualTo("static\nper-turn")
    assertThat(info.agents.getValue("static_only").instruction).isEqualTo("static")
  }

  @Test
  fun appInfo_toolThatFailsToDeclare_stillReportsTheRest() = testApplication {
    installAppInfo(llmAgent("root", tools = listOf(FailingTool(), FakeTool("declared"))))

    val response = client.get(APP_INFO_URL)

    assertThat(response.status).isEqualTo(HttpStatusCode.OK)
    val info = adkJson.decodeFromString<AppInfo>(response.bodyAsText())
    assertThat(info.agents.getValue("root").tools.flatMap { it.functionDeclarations.orEmpty() })
      .containsExactly(declarationOf("declared"))
  }

  @Test
  fun appInfo_textAndStructuredInstructions_areRendered() = testApplication {
    val structured =
      Instruction.Structured(Content(role = "user", parts = listOf(Part(text = "structured"))))
    installAppInfo(
      llmAgent(
        "root",
        instruction = Instruction.Text("plain"),
        subAgents = listOf(llmAgent("child", instruction = structured)),
      )
    )

    val info = getAppInfo()

    assertThat(info.agents.getValue("root").instruction).isEqualTo("plain")
    assertThat(info.agents.getValue("child").instruction).isEqualTo("structured")
  }

  @Test
  fun appInfo_emptyToolsAndSubAgents_areStillEmitted() = testApplication {
    installAppInfo(llmAgent("root"))

    val body = client.get(APP_INFO_URL).bodyAsText()

    // adkJson drops defaulted properties, so a required field needs an explicit encode default.
    assertThat(body).contains("\"tools\":[]")
    assertThat(body).contains("\"subAgents\":[]")
  }

  @Test
  fun appInfo_unknownApp_returnsNotFound() = testApplication {
    application {
      install(ContentNegotiation) { json(adkJson) }
      routing { appInfoRoutes(StubLoader(null)) }
    }

    assertThat(client.get(APP_INFO_URL).status).isEqualTo(HttpStatusCode.NotFound)
  }

  private fun ApplicationTestBuilder.installAppInfo(agent: BaseAgent) {
    application {
      install(ContentNegotiation) { json(adkJson) }
      routing { appInfoRoutes(StubLoader(agent)) }
    }
  }

  private suspend fun ApplicationTestBuilder.getAppInfo(): AppInfo =
    adkJson.decodeFromString(client.get(APP_INFO_URL).bodyAsText())

  private companion object {
    const val APP_INFO_URL = "/apps/demo/app-info"

    fun declarationOf(name: String) = FunctionDeclaration(name = name, description = "")

    fun llmAgent(
      name: String,
      description: String = "",
      instruction: Instruction? = null,
      staticInstruction: Content? = null,
      subAgents: List<BaseAgent> = emptyList(),
      tools: List<BaseTool> = emptyList(),
      toolsets: List<Toolset> = emptyList(),
    ) =
      LlmAgent(
        name = name,
        model = FakeModel,
        description = description,
        instruction = instruction,
        staticInstruction = staticInstruction,
        subAgents = subAgents,
        tools = tools,
        toolsets = toolsets,
      )
  }

  private class NamedProvider : Instruction.Provider {
    override suspend fun provide(context: ReadonlyContext) =
      error("app-info must not resolve an instruction")
  }

  private object FakeModel : Model {
    override val name = "fake-model"

    override fun generateContent(request: LlmRequest, stream: Boolean): Flow<LlmResponse> =
      error("app-info must not call the model")
  }

  /** Serves one agent under any name, so a test need not match the loader's key. */
  private class StubLoader(private val agent: BaseAgent?) : AgentLoader {
    override fun listAgents(): List<String> = listOfNotNull(agent?.name)

    override fun loadAgent(agentName: String): BaseAgent? = agent
  }

  private class FakeTool(
    name: String,
    private val declaration: FunctionDeclaration? = declarationOf(name),
  ) : BaseTool(name = name, description = "") {
    override fun declaration(): FunctionDeclaration? = declaration

    override suspend fun run(context: ToolContext, args: Map<String, Any?>): Any = Unit
  }

  private class FailingTool : BaseTool(name = "failing", description = "") {
    override fun declaration(): FunctionDeclaration = error("cannot build a declaration")

    override suspend fun run(context: ToolContext, args: Map<String, Any?>): Any = Unit
  }

  /** Records the context it was asked with, so a test can pin that none is supplied. */
  private class FakeToolset(private val tools: List<BaseTool>) : Toolset {
    val contexts = mutableListOf<ReadonlyContext?>()

    override suspend fun getTools(readonlyContext: ReadonlyContext?): List<BaseTool> {
      contexts.add(readonlyContext)
      return tools
    }
  }

  private class FailingToolset : Toolset {
    override suspend fun getTools(readonlyContext: ReadonlyContext?): List<BaseTool> =
      throw IllegalStateException("cannot reach the tool server")
  }

  /** Times itself out, which raises a cancellation that did not come from the caller. */
  private class TimingOutToolset : Toolset {
    override suspend fun getTools(readonlyContext: ReadonlyContext?): List<BaseTool> =
      withTimeout(1.milliseconds) {
        delay(1.seconds)
        emptyList()
      }
  }

  /** Never returns, so only the caller's cancellation can end the walk. */
  private class HangingToolset : Toolset {
    override suspend fun getTools(readonlyContext: ReadonlyContext?): List<BaseTool> {
      delay(1.minutes)
      return emptyList()
    }
  }

  /** Hands back a tool wrapping an agent that owns it, which no constructor could tie together. */
  private class CyclicToolset : Toolset {
    lateinit var target: BaseAgent

    override suspend fun getTools(readonlyContext: ReadonlyContext?): List<BaseTool> =
      listOf(AgentTool(target))
  }
}
