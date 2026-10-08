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

@file:OptIn(AdkJavaInteropApi::class, ExperimentalLiveApi::class)

package com.google.adk.kt.interop

import com.google.adk.kt.agents.Context
import com.google.adk.kt.agents.LiveRequest
import com.google.adk.kt.agents.LiveRequestQueue
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.agents.RunConfig
import com.google.adk.kt.agents.StreamingMode
import com.google.adk.kt.annotations.AdkJavaInteropApi
import com.google.adk.kt.annotations.ExperimentalLiveApi
import com.google.adk.kt.annotations.Param
import com.google.adk.kt.annotations.Requiredness
import com.google.adk.kt.annotations.Tool
import com.google.adk.kt.models.ContentInput
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.testing.DummyLiveModel
import com.google.adk.kt.testing.DummyLiveStep
import com.google.adk.kt.testing.DummyLiveStep.AwaitSent
import com.google.adk.kt.testing.DummyLiveStep.Respond
import com.google.adk.kt.testing.modelFunctionCallResponse
import com.google.adk.kt.testing.testToolContext
import com.google.adk.kt.tools.BaseTool
import com.google.adk.kt.tools.FunctionTool
import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.google.adk.kt.types.Type
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.asPublisher
import kotlinx.coroutines.runBlocking
import org.reactivestreams.Publisher
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription

enum class Style {
  FORMAL,
  CASUAL,
}

/** A data class param: the KSP path maps it to a nested object; [ReflectiveTools] does not. */
data class Point(val x: Int, val y: Int)

/**
 * Stands in for a Java caller. [ReflectiveTools] reads only the [Tool]/[Param] annotations, never
 * Kotlin parameter names, so the reflection path it exercises is the same one a `.java` source
 * hits. The methods use return types supported by the KSP path because this source is also
 * KSP-processed under Gradle's `jvmTest`.
 */
class Fixture {
  var lastContext: ToolContext? = null
  var lastThread: Thread? = null
  var lastRequestId: String? = null

  /** Stands in for MDC: thread-local state a caller carries into a tool through its context. */
  val requestId = ThreadLocal<String?>()

  @Tool(name = "current_time", description = "Returns the current time.")
  fun currentTime(): String = "noon"

  @Tool(description = "Records the thread it runs on and that thread's request id.")
  fun recordCaller(): String {
    lastThread = Thread.currentThread()
    lastRequestId = requestId.get()
    return "ok"
  }

  @Tool(description = "Adds two numbers.")
  fun addNumbers(
    @Param(name = "a", description = "first addend") a: Int,
    @Param(name = "b") b: Int,
  ): String = (a + b).toString()

  @Tool(description = "Echoes the text back in a map.")
  fun echo(@Param(name = "text") text: String): Map<String, String> = mapOf("text" to text)

  @Tool(description = "Reads session state.")
  fun readState(context: ToolContext): String {
    lastContext = context
    return "ok"
  }

  @Tool(description = "Takes the merged base Context instead of the ToolContext subclass.")
  fun readBaseContext(context: Context, @Param(name = "key") key: String): String {
    lastContext = context as ToolContext
    return "base:$key"
  }

  @Tool(description = "Uses a ToolContext alongside a value parameter.")
  fun readValue(context: ToolContext, @Param(name = "key") key: String): String {
    lastContext = context
    return "value:$key"
  }

  @Tool(description = "Scales a number, exercising Double and Float coercion.")
  fun scale(@Param(name = "factor") factor: Double, @Param(name = "ratio") ratio: Float): String =
    "$factor:$ratio"

  @Tool(description = "Greets in a given style.")
  fun greet(@Param(name = "style") style: Style): String = style.name

  @Tool(description = "Adds an optional note.")
  fun withOptional(@Param(name = "note", required = Requiredness.OPTIONAL) note: String?): String =
    note ?: "none"

  @Tool(description = "Collects items and metadata.")
  fun collect(
    @Param(name = "items") items: List<String>,
    @Param(name = "meta") meta: Map<String, String>,
  ): String = "${items.size}:${meta.size}"

  @Tool(description = "Always fails.") fun boom(): String = throw IllegalStateException("boom")

  @Tool(description = "Returns null.") fun returnsNull(): String? = null

  @Tool(description = "Returns nothing.") fun returnsVoid() {}

  @Tool(description = "Returns a nullable Unit.") fun returnsNullableUnit(): Unit? = null

  @Tool(description = "Has an unmapped parameter type.")
  fun unmapped(@Param(name = "p") p: Point): String = p.toString()

  @Tool(description = "Missing a @Param.") fun missingParam(x: String): String = x

  fun overloaded(a: String): String = a

  fun overloaded(a: Int): String = a.toString()

  @Tool(description = "The annotated overload.")
  fun annotatedOverload(@Param(name = "text") text: String): String = "text:$text"

  fun annotatedOverload(count: Int): String = "count:$count"

  // Two JVM overloads of one name, both @Tool. @JvmName gives them a shared JVM name while their
  // distinct Kotlin names keep the KSP-generated tool classes from colliding; a metadata consumer
  // re-resolves the method by name, so ReflectiveTools must reject building a tool from either.
  @Tool(description = "First ambiguous overload.")
  @JvmName("ambiguousTool")
  fun ambiguousToolText(@Param(name = "text") text: String): String = text

  @Tool(description = "Second ambiguous overload.")
  @JvmName("ambiguousTool")
  fun ambiguousToolCount(@Param(name = "count") count: Int): String = count.toString()

  fun notATool(): String = ""
}

/** Tools whose results stream, for the live-run tests. */
class StreamingFixture {
  @Tool(description = "Streams two prices.")
  fun prices(): Publisher<String> = flowOf("300", "400").asPublisher()

  @Tool(description = "Echoes what the user says.")
  fun echoLive(input: Publisher<LiveRequest>): Publisher<String> =
    input
      .asFlow()
      .mapNotNull { request ->
        (request.input as? ContentInput)?.content?.parts?.firstNotNullOfOrNull { it.text }
      }
      .map { "heard $it" }
      .asPublisher()

  @Tool(description = "Takes the live input through a wildcard.")
  fun echoOut(input: Publisher<out LiveRequest>): Publisher<String> =
    input.asFlow().map { "heard" }.asPublisher()
}

class ReflectiveToolsTest {

  private val fixture = Fixture()

  @Test
  fun fromMethod_usesExplicitNameAndDescription() {
    val tool = ReflectiveTools.fromMethod(fixture, "currentTime")

    assertEquals("current_time", tool.name)
    assertEquals("Returns the current time.", tool.description)
  }

  @Test
  fun fromMethod_defaultsNameToMethodName() {
    val tool = ReflectiveTools.fromMethod(fixture, "addNumbers")

    assertEquals("addNumbers", tool.name)
  }

  @Test
  fun fromMethod_recordsSourceClassAndMethodInCustomMetadata() {
    val tool = ReflectiveTools.fromMethod(fixture, "currentTime")

    // The recorded class + method names let a metadata consumer re-resolve the method and read its
    // annotations, the way a FunctionTool consumer reads them off func().
    assertEquals(
      Fixture::class.java.name,
      tool.customMetadata[FunctionTool.SOURCE_CLASS_METADATA_KEY],
    )
    assertEquals("currentTime", tool.customMetadata[FunctionTool.SOURCE_METHOD_METADATA_KEY])
  }

  @Test
  fun declaration_buildsSchemaFromParams() {
    val declaration = ReflectiveTools.fromMethod(fixture, "addNumbers").declaration()!!

    assertEquals("addNumbers", declaration.name)
    val parameters = declaration.parameters!!
    assertEquals(Type.OBJECT, parameters.type)
    val properties = parameters.properties!!
    assertEquals(Type.INTEGER, properties["a"]!!.type)
    assertEquals("first addend", properties["a"]!!.description)
    assertNull(properties["b"]!!.description)
    assertEquals(listOf("a", "b"), parameters.required)
  }

  @Test
  fun declaration_noParams_hasNoParameters() {
    val declaration = ReflectiveTools.fromMethod(fixture, "currentTime").declaration()!!

    assertNull(declaration.parameters)
  }

  @Test
  fun declaration_optionalParam_isNotRequired() {
    val parameters =
      ReflectiveTools.fromMethod(fixture, "withOptional").declaration()!!.parameters!!

    assertTrue(parameters.properties!!.containsKey("note"))
    assertNull(parameters.required)
  }

  @Test
  fun declaration_enumParam_isStringWithEnumValues() {
    val schema =
      ReflectiveTools.fromMethod(fixture, "greet").declaration()!!.parameters!!.properties!![
        "style"]!!

    assertEquals(Type.STRING, schema.type)
    assertEquals(listOf("FORMAL", "CASUAL"), schema.enum)
  }

  @Test
  fun declaration_listOrMapParam_fails() {
    // List/Map are unsupported: erased generics hide the element type needed for items/properties.
    val tool = ReflectiveTools.fromMethod(fixture, "collect")

    assertFailsWith<IllegalArgumentException> { tool.declaration() }
  }

  @Test
  fun run_invokesMethodAndReturnsResult() = runBlocking {
    val tool = ReflectiveTools.fromMethod(fixture, "currentTime")

    assertEquals(mapOf("result" to "noon"), tool.run(testToolContext(), emptyMap()))
  }

  @Test
  fun run_invokesTheMethodInTheCallersCoroutine() =
    runBlocking(fixture.requestId.asContextElement("request-1")) {
      val tool = ReflectiveTools.fromMethod(fixture, "recordCaller")

      val unused = tool.run(testToolContext(), emptyMap())

      assertSame(Thread.currentThread(), fixture.lastThread)
      assertEquals("request-1", fixture.lastRequestId)
    }

  @Test
  fun run_mapReturn_isWrappedUnderResultKey() = runBlocking {
    val tool = ReflectiveTools.fromMethod(fixture, "echo")

    assertEquals(
      mapOf("result" to mapOf("text" to "hi")),
      tool.run(testToolContext(), mapOf("text" to "hi")),
    )
  }

  @Test
  fun run_coercesJsonNumbersToDeclaredType() = runBlocking {
    val tool = ReflectiveTools.fromMethod(fixture, "addNumbers")

    // JSON numbers arrive as Double; the Int params must be coerced before the reflective call.
    assertEquals(mapOf("result" to "5"), tool.run(testToolContext(), mapOf("a" to 2.0, "b" to 3.0)))
  }

  @Test
  fun run_coercesEnumFromName() = runBlocking {
    val tool = ReflectiveTools.fromMethod(fixture, "greet")

    assertEquals(
      mapOf("result" to "CASUAL"),
      tool.run(testToolContext(), mapOf("style" to "CASUAL")),
    )
  }

  @Test
  fun run_unknownEnumValue_fails() {
    val tool = ReflectiveTools.fromMethod(fixture, "greet")

    assertFailsWith<IllegalArgumentException> {
      runBlocking { tool.run(testToolContext(), mapOf("style" to "SASSY")) }
    }
  }

  @Test
  fun run_injectsToolContext() = runBlocking {
    val tool = ReflectiveTools.fromMethod(fixture, "readState")
    val context = testToolContext()

    val unused = tool.run(context, emptyMap())

    assertSame(context, fixture.lastContext)
  }

  @Test
  fun run_injectsToolContextAlongsideValueParam() = runBlocking {
    val tool = ReflectiveTools.fromMethod(fixture, "readValue")
    val context = testToolContext()

    val result = tool.run(context, mapOf("key" to "k"))

    assertEquals(mapOf("result" to "value:k"), result)
    assertSame(context, fixture.lastContext)
  }

  @Test
  fun run_injectsTheMergedBaseContext() = runBlocking {
    // Verify that a parameter declared as the base Context type is injected rather than treated
    // as a @Param argument.
    val tool = ReflectiveTools.fromMethod(fixture, "readBaseContext")
    val context = testToolContext()

    val result = tool.run(context, mapOf("key" to "k"))

    assertEquals(mapOf("result" to "base:k"), result)
    assertSame(context, fixture.lastContext)
  }

  @Test
  fun run_coercesDoubleAndFloatFromJsonNumbers() = runBlocking {
    val tool = ReflectiveTools.fromMethod(fixture, "scale")

    // JSON numbers arrive as Int/Double; Double and Float params must be coerced.
    assertEquals(
      mapOf("result" to "2.0:3.0"),
      tool.run(testToolContext(), mapOf("factor" to 2, "ratio" to 3)),
    )
  }

  @Test
  fun run_omittedOptionalArgument_usesMethodDefault() = runBlocking {
    val tool = ReflectiveTools.fromMethod(fixture, "withOptional")

    assertEquals(mapOf("result" to "none"), tool.run(testToolContext(), emptyMap()))
  }

  @Test
  fun run_nullReturn_isWrappedAsNullResult() = runBlocking {
    val tool = ReflectiveTools.fromMethod(fixture, "returnsNull")

    assertEquals(mapOf("result" to null), tool.run(testToolContext(), emptyMap()))
  }

  @Test
  fun run_voidMethod_returnsUnit() = runBlocking {
    val tool = ReflectiveTools.fromMethod(fixture, "returnsVoid")

    assertEquals(Unit, tool.run(testToolContext(), emptyMap()))
  }

  @Test
  fun run_nullableUnitMethod_returnsUnit() = runBlocking {
    val tool = ReflectiveTools.fromMethod(fixture, "returnsNullableUnit")

    assertEquals(Unit, tool.run(testToolContext(), emptyMap()))
  }

  @Test
  fun run_missingRequiredArgument_fails() {
    val tool = ReflectiveTools.fromMethod(fixture, "addNumbers")

    assertFailsWith<IllegalArgumentException> {
      runBlocking { tool.run(testToolContext(), mapOf("a" to 1)) }
    }
  }

  @Test
  fun run_propagatesMethodException() {
    val tool = ReflectiveTools.fromMethod(fixture, "boom")

    assertFailsWith<IllegalStateException> {
      runBlocking { tool.run(testToolContext(), emptyMap()) }
    }
  }

  @Test
  fun declaration_unmappedParamType_fails() {
    val tool = ReflectiveTools.fromMethod(fixture, "unmapped")

    assertFailsWith<IllegalArgumentException> { tool.declaration() }
  }

  @Test
  fun fromMethod_missingParamAnnotation_fails() {
    assertFailsWith<IllegalArgumentException> {
      ReflectiveTools.fromMethod(fixture, "missingParam")
    }
  }

  @Test
  fun fromMethod_overloadedMethodWithoutTool_fails() {
    // Overloads with no @Tool at all cannot be resolved to a single tool method.
    assertFailsWith<IllegalArgumentException> { ReflectiveTools.fromMethod(fixture, "overloaded") }
  }

  @Test
  fun fromMethod_overloadWithSingleAnnotatedOverload_resolvesAnnotatedOne() {
    // Overloaded names are allowed as long as exactly one overload carries @Tool.
    val tool = ReflectiveTools.fromMethod(fixture, "annotatedOverload")

    assertEquals("annotatedOverload", tool.name)
    assertEquals("annotatedOverload", tool.customMetadata[FunctionTool.SOURCE_METHOD_METADATA_KEY])
  }

  @Test
  fun fromMethod_multipleAnnotatedOverloads_fails() {
    // The name-based fromMethod rejects this earlier, so pass a Method directly to exercise the
    // Method overload's "sole @Tool overload" guard.
    val method = fixture.javaClass.methods.first { it.name == "ambiguousTool" }

    val exception =
      assertFailsWith<IllegalArgumentException> { ReflectiveTools.fromMethod(fixture, method) }
    // Pin the sole-overload guard so the test can't pass on an unrelated argument failure.
    assertTrue(exception.message!!.contains("sole @Tool-annotated"))
  }

  @Test
  fun fromMethod_unknownMethod_fails() {
    assertFailsWith<IllegalArgumentException> { ReflectiveTools.fromMethod(fixture, "nope") }
  }

  @Test
  fun fromMethod_notAnnotated_fails() {
    assertFailsWith<IllegalArgumentException> { ReflectiveTools.fromMethod(fixture, "notATool") }
  }

  private fun answers(callId: String, response: Map<String, Any?>): (Any) -> Boolean = { message ->
    message is ContentInput &&
      message.content.parts.any {
        it.functionResponse?.id == callId && it.functionResponse.response == response
      }
  }

  /**
   * Runs [tool] live against [script] until the turn completes, then checks every gate was met.
   * With [ping], the caller says `ping(n)` for n = 1, 2, ... every 20 ms (real time: the tool
   * starts listening asynchronously, so it repeats until heard).
   */
  private suspend fun runLive(
    script: List<DummyLiveStep>,
    tool: BaseTool,
    ping: ((Int) -> String)? = null,
  ) {
    coroutineScope {
      val model = DummyLiveModel(script)
      val agent = LlmAgent(name = "agent", model = model, tools = listOf(tool))
      val queue = LiveRequestQueue()
      val pinger = launch {
        var n = 0
        while (ping != null) {
          queue.sendContent(Content(Role.USER, listOf(Part(text = ping(++n)))))
          delay(20.milliseconds)
        }
      }
      try {
        InMemoryRunner(agent = agent)
          .runLive("u", "s", queue, RunConfig(streamingMode = StreamingMode.BIDI))
          .onEach { if (it.turnComplete) queue.close() }
          .collect()
        model.connections.single().assertAllWaitsMet()
      } finally {
        pinger.cancel()
      }
    }
  }

  private fun pricesScript() =
    listOf(
      Respond(modelFunctionCallResponse("prices", id = "c1")),
      AwaitSent("the pending answer", answers("c1", PENDING)),
      AwaitSent("the first price", answers("c1", mapOf("result" to "300"))),
      AwaitSent("the second price", answers("c1", mapOf("result" to "400"))),
      Respond(LlmResponse(turnComplete = true)),
    )

  /** The n of a "ping n" message, or null. */
  private fun pingNumber(content: Content?): Int? =
    content?.parts?.firstNotNullOfOrNull { it.text }?.removePrefix("ping ")?.toIntOrNull()

  @Test
  fun fromMethod_methodReturningAPublisher_streamsUnderALiveRun() {
    runBlocking {
      runLive(pricesScript(), ReflectiveTools.fromMethod(StreamingFixture(), "prices"))
    }
  }

  @Test
  fun fromMethod_publisherOfLiveRequestsParameter_receivesWhatTheUserSays() {
    runBlocking {
      val tool = ReflectiveTools.fromMethod(StreamingFixture(), "echoLive")
      val script =
        listOf(
          Respond(modelFunctionCallResponse("echoLive", id = "c1")),
          AwaitSent("an echo", answers("c1", mapOf("result" to "heard ping"))),
          Respond(LlmResponse(turnComplete = true)),
        )

      runLive(script, tool, ping = { "ping" })

      assertNull(tool.declaration()?.parameters, "the live input should not be a model parameter")
    }
  }

  @Test
  fun fromMethod_subscriberBlockingOnTheLiveInput_doesNotHoldBackTheSends() {
    // The n of the first "ping n" the subscriber gets; its onNext blocks until the test ends.
    val blockedOn = AtomicInteger()
    val release = CountDownLatch(1)
    val subscriber =
      object : Subscriber<LiveRequest> {
        override fun onSubscribe(subscription: Subscription) {
          subscription.request(Long.MAX_VALUE)
        }

        override fun onNext(request: LiveRequest) {
          val ping = pingNumber((request.input as? ContentInput)?.content) ?: return
          blockedOn.compareAndSet(0, ping)
          // Bounded, so a stalled send loop fails the script's wait instead of hanging the test.
          release.await(10, TimeUnit.SECONDS)
        }

        override fun onError(t: Throwable) {}

        override fun onComplete() {}
      }
    // Local, so KSP, which processes this source set in Gradle, never sees this tool.
    class SlowListener {
      @Tool(description = "Listens to the user with a subscriber that blocks.")
      fun listen(input: Publisher<LiveRequest>): Publisher<String> =
        flow<String> {
            input.subscribe(subscriber)
            awaitCancellation()
          }
          .asPublisher()
    }
    val script =
      listOf(
        Respond(modelFunctionCallResponse("listen", id = "c1")),
        // Only the blocked ping or a later one: an earlier one may be checked after the block.
        AwaitSent("a ping sent while the subscriber blocks") { message ->
          val sent = pingNumber((message as? ContentInput)?.content)
          sent != null && blockedOn.get() in 1..sent
        },
        Respond(LlmResponse(turnComplete = true)),
      )

    try {
      // Multi-threaded, so a blocked sender cannot also stall the script's timeout.
      runBlocking(Dispatchers.Default) {
        runLive(script, ReflectiveTools.fromMethod(SlowListener(), "listen"), ping = { "ping $it" })
      }
    } finally {
      release.countDown()
    }
  }

  @Test
  fun fromMethod_publisherOfALiveRequestWildcard_takesTheLiveInput() {
    val tool = ReflectiveTools.fromMethod(StreamingFixture(), "echoOut")

    assertNull(tool.declaration()?.parameters, "the live input should not be a model parameter")
  }

  @Test
  fun fromMethod_publisherOfAnotherType_fails() {
    // Local, so KSP, which processes this source set in Gradle, never sees these invalid tools.
    class BadStreamingFixture {
      @Tool(description = "Takes a Publisher of anything.")
      fun anything(input: Publisher<*>): String = "x"

      @Tool(description = "Takes a Publisher of strings.")
      fun strings(input: Publisher<String>): String = "x"
    }

    assertFailsWith<IllegalArgumentException> {
      ReflectiveTools.fromMethod(BadStreamingFixture(), "anything")
    }
    assertFailsWith<IllegalArgumentException> {
      ReflectiveTools.fromMethod(BadStreamingFixture(), "strings")
    }
  }

  @Test
  fun fromMethod_twoPublisherParameters_fails() {
    // Local, so KSP, which processes this source set in Gradle, never sees this invalid tool.
    class BadStreamingFixture {
      @Tool(description = "Takes two live inputs.")
      fun two(first: Publisher<LiveRequest>, second: Publisher<LiveRequest>): String = "x"
    }

    val failure =
      assertFailsWith<IllegalArgumentException> {
        ReflectiveTools.fromMethod(BadStreamingFixture(), "two")
      }

    assertEquals(
      "two may take at most one Publisher parameter, for the live input.",
      failure.message,
    )
  }

  @Test
  fun fromMethod_publisherSubtypeParameter_fails() {
    // Local, so KSP, which processes this source set in Gradle, never sees this invalid tool.
    abstract class LivePublisher<T> : Publisher<T>

    class BadStreamingFixture {
      @Tool(description = "Takes a Publisher subtype.")
      fun subtype(input: LivePublisher<LiveRequest>): String = "x"
    }

    val failure =
      assertFailsWith<IllegalArgumentException> {
        ReflectiveTools.fromMethod(BadStreamingFixture(), "subtype")
      }

    assertEquals(
      "A Publisher parameter of subtype must be a Publisher<LiveRequest>.",
      failure.message,
    )
  }

  companion object {
    private val PENDING =
      mapOf("status" to "The function is running asynchronously and the results are pending.")
  }
}
