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

@file:OptIn(ExperimentalWorkflowApi::class)

package com.google.adk.kt.telemetry

import com.google.adk.kt.agents.Context
import com.google.adk.kt.agents.Instruction
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.agents.RunConfig
import com.google.adk.kt.agents.StreamingMode
import com.google.adk.kt.annotations.ExperimentalWorkflowApi
import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.apps.App
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.models.Model
import com.google.adk.kt.models.StreamingResponseAggregator
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.telemetry.otel.OtelTracer
import com.google.adk.kt.testing.DummyModel
import com.google.adk.kt.testing.modelFunctionCall
import com.google.adk.kt.testing.modelMessage
import com.google.adk.kt.testing.userMessage
import com.google.adk.kt.tools.AgentTool
import com.google.adk.kt.tools.BaseTool
import com.google.adk.kt.tools.FunctionTool
import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FinishReason
import com.google.adk.kt.types.FunctionDeclaration
import com.google.adk.kt.types.UsageMetadata
import com.google.adk.kt.workflow.Edge
import com.google.adk.kt.workflow.Node
import com.google.adk.kt.workflow.Start
import com.google.adk.kt.workflow.Workflow
import com.google.common.truth.Truth.assertWithMessage
import com.google.genai.kotlin.ClientException
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import io.opentelemetry.sdk.trace.export.SpanExporter
import java.util.Collections
import kotlin.test.assertFailsWith
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Compares the spans of Python ADK's functional telemetry scenarios, re-created in Kotlin, with
 * hand copies of Python's schema v1 goldens in adk-python's
 * `tests/unittests/telemetry/functional_goldens/`: span names, nesting, attribute keys and pinned
 * values. [DIVERGENCES] lists where Kotlin differs on purpose. Span status and log records are not
 * compared: Kotlin spans have no status, and Kotlin emits no log records.
 */
@RunWith(JUnit4::class)
class PythonGoldenParityTest {

  private val exported: MutableList<SpanData> = Collections.synchronizedList(mutableListOf())
  private val provider =
    SdkTracerProvider.builder()
      .addSpanProcessor(
        SimpleSpanProcessor.create(
          object : SpanExporter {
            override fun export(spans: Collection<SpanData>): CompletableResultCode {
              exported += spans
              return CompletableResultCode.ofSuccess()
            }

            override fun flush(): CompletableResultCode = CompletableResultCode.ofSuccess()

            override fun shutdown(): CompletableResultCode = CompletableResultCode.ofSuccess()
          }
        )
      )
      .build()

  @Before
  fun setUp() {
    Telemetry.setTracerForTest(OtelTracer(provider.get("test")))
    TelemetryConfig.captureMessageContent = false
  }

  @After
  fun tearDown() {
    Telemetry.resetTracer()
    provider.close()
  }

  /** `agent/stable-no-capture-schema-v1.json`. */
  @Test
  fun agentScenario_matchesTheSchemaV1Golden() {
    run(rootAgent(toolCallingModel()))

    assertMatches(Expected("invocation", emptyMap(), listOf(agentSpan())))
  }

  /** `node/stable-no-capture-schema-v1.json`. */
  @Test
  fun workflowScenario_matchesTheSchemaV1Golden() {
    val someNode =
      object : Node {
        override val name = "some_node"

        override fun runNode(context: Context, nodeInput: Any?): Flow<Any?> = flow {
          emit("some result")
        }
      }
    val nested = Workflow("my_nested_workflow", edges = listOf(Edge(Start, someNode)))
    val agent = rootAgent(toolCallingModel())
    run(Workflow("my_workflow", edges = listOf(Edge(Start, nested), Edge(nested, agent))))

    assertMatches(
      Expected(
        "invocation",
        emptyMap(),
        listOf(
          Expected(
            "invoke_workflow my_workflow",
            mapOf(
              "gen_ai.conversation.id" to PRESENT,
              "gen_ai.operation.name" to "invoke_workflow",
              "gen_ai.workflow.name" to "my_workflow",
            ),
            listOf(
              Expected(
                "invoke_workflow my_nested_workflow",
                mapOf(
                  "gen_ai.conversation.id" to PRESENT,
                  "gen_ai.operation.name" to "invoke_workflow",
                  "gen_ai.workflow.name" to "my_nested_workflow",
                  "gen_ai.workflow.nested" to true,
                ),
                listOf(
                  Expected(
                    "invoke_node some_node",
                    mapOf(
                      "gcp.vertex.agent.associated_event_ids" to PRESENT,
                      "gen_ai.conversation.id" to PRESENT,
                      "gen_ai.operation.name" to "invoke_node",
                    ),
                  )
                ),
              ),
              agentSpan(),
            ),
          )
        ),
      )
    )
  }

  /** `agent/inference-error-resource-exhausted-schema-v1.json`. */
  @Test
  fun modelFailureScenario_matchesTheSchemaV1Golden() {
    val failing =
      DummyModel(MODEL) {
        flow {
          throw ClientException(
            code = 429,
            status = "RESOURCE_EXHAUSTED",
            message = "Resource exhausted.",
          )
        }
      }
    assertFailsWith<ClientException> { run(rootAgent(failing)) }

    val error = mapOf("error.type" to "429")
    assertMatches(
      Expected(
        "invocation",
        error,
        listOf(
          Expected(
            "invoke_agent $ROOT_AGENT",
            error + agentAttributes(),
            listOf(
              Expected(
                "call_llm",
                error,
                listOf(Expected("generate_content $MODEL", error + generateContentRequest())),
              )
            ),
          )
        ),
      ),
      DIVERGENCES + FAILED_CALL_LLM_DIVERGENCES,
    )
  }

  /** `multi_agent/no-experimental-telemetry-schema-v1.json`. */
  @Test
  fun multiAgentScenario_matchesTheSchemaV1Golden() {
    val model =
      DummyModel.createSequential(
        MODEL,
        listOf(
          response(
            modelFunctionCall("transfer_to_agent", mapOf("agent_name" to SPECIALIST_AGENT)),
            FIRST_TURN,
          ),
          response(modelMessage("text response"), SECOND_TURN),
        ),
      )
    val specialist = agent(SPECIALIST_AGENT, SPECIALIST_DESCRIPTION, model)
    run(agent(ROOT_AGENT, ROOT_DESCRIPTION, model, subAgents = listOf(specialist)))

    assertMatches(
      Expected(
        "invocation",
        emptyMap(),
        listOf(
          Expected(
            "invoke_agent $ROOT_AGENT",
            agentAttributes(),
            listOf(
              callLlm(FIRST_TURN_ATTRIBUTES),
              // Kotlin's transfer_to_agent tool has its own description text.
              toolSpan("transfer_to_agent", PRESENT, type = "TransferToAgentTool"),
            ),
          ),
          Expected(
            "invoke_agent $SPECIALIST_AGENT",
            agentAttributes(SPECIALIST_AGENT, SPECIALIST_DESCRIPTION),
            listOf(callLlm(SECOND_TURN_ATTRIBUTES, SPECIALIST_AGENT)),
          ),
        ),
      )
    )
  }

  /** `agent_tool/no-experimental-telemetry-schema-v1.json`. */
  @Test
  fun agentToolScenario_matchesTheSchemaV1Golden() {
    val model =
      DummyModel.createSequential(
        MODEL,
        listOf(
          response(modelFunctionCall(DELEGATE_AGENT, mapOf("request" to "hello")), FIRST_TURN),
          response(modelMessage("some result"), NESTED_TURN),
          response(modelMessage("text response"), SECOND_TURN),
        ),
      )
    val delegate = agent(DELEGATE_AGENT, DELEGATE_DESCRIPTION, model)
    val delegating =
      agent(DELEGATING_AGENT, DELEGATING_DESCRIPTION, model, tools = listOf(AgentTool(delegate)))
    run(Workflow("my_agent_tool_workflow", edges = listOf(Edge(Start, delegating))))

    assertMatches(
      Expected(
        "invocation",
        emptyMap(),
        listOf(
          Expected(
            "invoke_workflow my_agent_tool_workflow",
            mapOf(
              "gen_ai.conversation.id" to PRESENT,
              "gen_ai.operation.name" to "invoke_workflow",
              "gen_ai.workflow.name" to "my_agent_tool_workflow",
            ),
            listOf(
              Expected(
                "invoke_agent $DELEGATING_AGENT",
                agentAttributes(DELEGATING_AGENT, DELEGATING_DESCRIPTION),
                listOf(
                  callLlm(FIRST_TURN_ATTRIBUTES, DELEGATING_AGENT),
                  toolSpan(
                    DELEGATE_AGENT,
                    DELEGATE_DESCRIPTION,
                    type = "AgentTool",
                    agentName = DELEGATING_AGENT,
                    children =
                      listOf(
                        Expected(
                          "invocation",
                          emptyMap(),
                          listOf(
                            Expected(
                              "invoke_agent $DELEGATE_AGENT",
                              agentAttributes(DELEGATE_AGENT, DELEGATE_DESCRIPTION),
                              listOf(callLlm(NESTED_TURN_ATTRIBUTES, DELEGATE_AGENT)),
                            )
                          ),
                        )
                      ),
                  ),
                  callLlm(SECOND_TURN_ATTRIBUTES, DELEGATING_AGENT),
                ),
              )
            ),
          )
        ),
      )
    )
  }

  /** `streaming/stable-no-capture-schema-v1.json`. */
  @OptIn(FrameworkInternalApi::class)
  @Test
  fun streamingScenario_matchesTheSchemaV1Golden() {
    // Python's `STREAMING_TURNS`, streamed as Gemini does: partial chunks, then the aggregate.
    fun streamed(vararg chunks: LlmResponse): Flow<LlmResponse> = flow {
      val aggregator = StreamingResponseAggregator()
      for (chunk in chunks) emit(aggregator.processResponse(chunk))
      aggregator.aggregate()?.let { emit(it) }
    }
    val model =
      DummyModel(
        MODEL,
        listOf(
          streamed(response(modelFunctionCall("some_tool", mapOf("arg1" to "val1")), FIRST_TURN)),
          streamed(
            LlmResponse(content = modelMessage("text "), usageMetadata = SECOND_TURN),
            response(modelMessage("response"), SECOND_TURN),
          ),
        ),
      )
    run(rootAgent(model), RunConfig(streamingMode = StreamingMode.SSE))

    assertMatches(Expected("invocation", emptyMap(), listOf(agentSpan())))
  }

  private fun run(root: Node, runConfig: RunConfig? = null) {
    runBlocking {
      InMemoryRunner(app = App(appName = "some_app", rootNode = root))
        .runAsync(
          userId = "some_user",
          sessionId = "session",
          newMessage = userMessage("hello"),
          runConfig = runConfig,
        )
        .collect {}
    }
  }

  private fun assertMatches(expected: Expected, divergences: List<Divergence> = DIVERGENCES) {
    val byParent = exported.groupBy { it.parentSpanId }
    val mismatches = buildList {
      fun compare(expected: Expected, actual: SpanData) {
        val name = actual.name
        val attributes = actual.attributes.asMap().entries.associate { it.key.key to it.value }
        val allowed =
          divergences.filter { name.startsWith(it.spanPrefix) }.map { it.attribute }.toSet()
        for ((key, value) in expected.attributes) {
          if (key in allowed) continue
          val actualValue = attributes[key]
          when {
            actualValue == null -> add("$name: missing $key")
            value != PRESENT && actualValue != value ->
              add("$name: $key is $actualValue, golden has $value")
          }
        }
        for (key in attributes.keys - expected.attributes.keys - allowed) {
          add("$name: extra $key=${attributes[key]}")
        }
        val children = byParent[actual.spanId].orEmpty().sortedBy { it.startEpochNanos }
        if (children.map { it.name } != expected.children.map { it.name }) {
          add(
            "$name: children are ${children.map { it.name }}, golden has " +
              "${expected.children.map { it.name }}"
          )
          return
        }
        for ((e, a) in expected.children.zip(children)) compare(e, a)
      }
      compare(expected, exported.single { !it.parentSpanContext.isValid })
    }
    assertWithMessage("Spans differing from the Python golden").that(mismatches).isEmpty()
  }

  private data class Expected(
    val name: String,
    val attributes: Map<String, Any>,
    val children: List<Expected> = emptyList(),
  )

  /** An attribute Kotlin reports differently from Python's golden, on purpose. */
  private data class Divergence(val spanPrefix: String, val attribute: String, val reason: String)

  private class SomeTool : FunctionTool("some_tool", "A sample tool.") {
    override fun declaration() = FunctionDeclaration(name = name, description = description)

    override suspend fun execute(context: ToolContext, args: Map<String, Any?>): Any =
      "processed ${args["arg1"]}"
  }

  companion object {
    private const val MODEL = "gemini-2.5-flash"
    private const val ROOT_AGENT = "some_root_agent"
    private const val ROOT_DESCRIPTION = "A sample root agent."
    private const val SPECIALIST_AGENT = "some_specialist_agent"
    private const val SPECIALIST_DESCRIPTION = "A sample specialist agent."
    private const val DELEGATING_AGENT = "some_delegating_agent"
    private const val DELEGATING_DESCRIPTION = "A sample agent that delegates."
    private const val DELEGATE_AGENT = "some_delegate_agent"
    private const val DELEGATE_DESCRIPTION = "A sample delegate agent."

    // Python's `*_TURN_USAGE` values, then the attributes their spans report.
    private val FIRST_TURN = usage(prompt = 100, cached = 40, candidates = 20, thoughts = 5)
    private val SECOND_TURN = usage(prompt = 150, cached = 60, candidates = 35, thoughts = 15)
    private val NESTED_TURN = usage(prompt = 70, cached = 30, candidates = 10, thoughts = 3)
    private val FIRST_TURN_ATTRIBUTES =
      usageAttributes(input = 100, cached = 40, output = 25, reasoning = 5)
    private val SECOND_TURN_ATTRIBUTES =
      usageAttributes(input = 150, cached = 60, output = 50, reasoning = 15)
    private val NESTED_TURN_ATTRIBUTES =
      usageAttributes(input = 70, cached = 30, output = 13, reasoning = 3)

    /** Matches any value, as the goldens' `"PRESENT"` does. */
    private val PRESENT = Any()

    private val DIVERGENCES =
      listOf(
        Divergence(
          spanPrefix = "invoke_agent",
          attribute = "gen_ai.system",
          reason =
            "Kept from before the schema existed; dropping it could break existing dashboards.",
        ),
        Divergence(
          spanPrefix = "execute_tool some_tool",
          attribute = "gen_ai.tool.type",
          reason = "The tool's own class: a Kotlin function tool subclasses FunctionTool.",
        ),
        Divergence(
          spanPrefix = "execute_tool",
          attribute = "gcp.vertex.agent.invocation_id",
          reason = "The dev server and existing exporters find tool spans by their invocation.",
        ),
        Divergence(
          spanPrefix = "execute_tool",
          attribute = "gcp.vertex.agent.session_id",
          reason = "The dev server links a session's trace through any span with its session id.",
        ),
      )

    /**
     * Attributes Kotlin sets on call_llm when the span starts, where Python sets them once the
     * model answers: a call that fails before answering has them in Kotlin only.
     */
    private val FAILED_CALL_LLM_DIVERGENCES =
      listOf(
          "gcp.vertex.agent.llm_request" to "The Dev UI parses it on every call_llm span.",
          "gcp.vertex.agent.llm_response" to "The Dev UI parses it on every call_llm span.",
          "gcp.vertex.agent.event_id" to "Trace consumers drop call_llm spans without it.",
          "gcp.vertex.agent.invocation_id" to "Known before the call, so set at the start.",
          "gcp.vertex.agent.session_id" to "Known before the call, so set at the start.",
          "gen_ai.request.model" to "Known before the call, so set at the start.",
          "gen_ai.system" to "Known before the call, so set at the start.",
        )
        .map { (attribute, reason) -> Divergence(spanPrefix = "call_llm", attribute, reason) }

    private fun agent(
      name: String,
      description: String,
      model: Model,
      tools: List<BaseTool> = emptyList(),
      subAgents: List<LlmAgent> = emptyList(),
    ) =
      LlmAgent(
        name = name,
        description = description,
        instruction = Instruction("you are helpful"),
        model = model,
        tools = tools,
        subAgents = subAgents,
      )

    private fun rootAgent(model: Model) =
      agent(ROOT_AGENT, ROOT_DESCRIPTION, model, tools = listOf(SomeTool()))

    private fun response(content: Content, usage: UsageMetadata) =
      LlmResponse(content = content, finishReason = FinishReason.STOP, usageMetadata = usage)

    /** The canned conversation of Python's `TOOL_CALLING_TURNS`. */
    private fun toolCallingModel() =
      DummyModel.createSequential(
        MODEL,
        listOf(
          response(modelFunctionCall("some_tool", mapOf("arg1" to "val1")), FIRST_TURN),
          response(modelMessage("text response"), SECOND_TURN),
        ),
      )

    private fun usage(prompt: Int, cached: Int, candidates: Int, thoughts: Int) =
      UsageMetadata(
        promptTokenCount = prompt,
        cachedContentTokenCount = cached,
        candidatesTokenCount = candidates,
        thoughtsTokenCount = thoughts,
        totalTokenCount = prompt + candidates + thoughts,
      )

    private fun agentAttributes(name: String = ROOT_AGENT, description: String = ROOT_DESCRIPTION) =
      mapOf(
        "gen_ai.agent.description" to description,
        "gen_ai.agent.name" to name,
        "gen_ai.conversation.id" to PRESENT,
        "gen_ai.operation.name" to "invoke_agent",
      )

    private fun generateContentRequest(agentName: String = ROOT_AGENT) =
      mapOf(
        "gcp.vertex.agent.event_id" to PRESENT,
        "gcp.vertex.agent.invocation_id" to PRESENT,
        "gen_ai.agent.name" to agentName,
        "gen_ai.conversation.id" to PRESENT,
        "gen_ai.operation.name" to "generate_content",
        "gen_ai.request.model" to MODEL,
        "gen_ai.system" to "gemini",
      )

    private fun usageAttributes(input: Long, cached: Long, output: Long, reasoning: Long) =
      mapOf(
        "gen_ai.response.finish_reasons" to listOf("stop"),
        "gen_ai.usage.cache_read.input_tokens" to cached,
        "gen_ai.usage.input_tokens" to input,
        "gen_ai.usage.output_tokens" to output,
        "gen_ai.usage.reasoning.output_tokens" to reasoning,
      )

    private fun callLlm(usage: Map<String, Any>, agentName: String = ROOT_AGENT) =
      Expected(
        "call_llm",
        mapOf(
          "gcp.vertex.agent.event_id" to PRESENT,
          "gcp.vertex.agent.invocation_id" to PRESENT,
          "gcp.vertex.agent.llm_request" to "{}",
          "gcp.vertex.agent.llm_response" to "{}",
          "gcp.vertex.agent.session_id" to PRESENT,
          "gen_ai.request.model" to MODEL,
          "gen_ai.system" to "gcp.vertex.agent",
        ) + usage,
        listOf(Expected("generate_content $MODEL", generateContentRequest(agentName) + usage)),
      )

    private fun toolSpan(
      name: String,
      description: Any,
      type: String,
      agentName: String = ROOT_AGENT,
      children: List<Expected> = emptyList(),
    ) =
      Expected(
        "execute_tool $name",
        mapOf(
          "gcp.vertex.agent.event_id" to PRESENT,
          "gcp.vertex.agent.llm_request" to "{}",
          "gcp.vertex.agent.llm_response" to "{}",
          "gcp.vertex.agent.tool_call_args" to "{}",
          "gcp.vertex.agent.tool_response" to "{}",
          "gen_ai.agent.name" to agentName,
          "gen_ai.operation.name" to "execute_tool",
          "gen_ai.tool.call.id" to PRESENT,
          "gen_ai.tool.description" to description,
          "gen_ai.tool.name" to name,
          "gen_ai.tool.type" to type,
        ),
        children,
      )

    private fun agentSpan() =
      Expected(
        "invoke_agent $ROOT_AGENT",
        agentAttributes(),
        listOf(
          callLlm(FIRST_TURN_ATTRIBUTES),
          toolSpan("some_tool", "A sample tool.", type = "FunctionTool"),
          callLlm(SECOND_TURN_ATTRIBUTES),
        ),
      )
  }
}
