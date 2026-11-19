# ADK Kotlin: Spring Boot WebFlux example in Java

An order-support service written in plain Java with Spring Boot and WebFlux. It
runs an ADK workflow, which combines Gemini agents, function nodes that run
business rules in code, and tools backed by a Spring bean, and serves it two
ways: a request that gets the finished reply, and a request that streams the
reply as server-sent events (SSE). Where [the Kotlin version](../kotlin) uses
the Kotlin API directly, this one goes through Java interop: tools that
`ReflectiveTools` reads at runtime, `FunctionNodes` function nodes, and
`PublisherRunner` with Reactor, as [How it works](#how-it-works) describes.

```
START ─▶ triage ─▶ check_order
                     ├─ "tracking" ─▶ shipping ─────────────────────┐
                     ├─ "refund"   ─▶ refund_policy                 │
                     │                  ├─ true  ─▶ refunds ────────┤
                     │                  └─ false ─▶ refund_denied ──┤
                     └─ otherwise  ─▶ ask_for_order ────────────────┴─▶ reply
```

-   **triage**, an `LlmAgent`, classifies the message and extracts the order
    number as structured output.
-   **check_order**, a function node, takes that output, looks up the order in
    `OrderService`, and routes on the customer's intent. It passes on the
    order's number and item.
-   **refund_policy**, a function node, applies the refund policy in code and
    routes on the decision: `true` for an approved refund, `false` for a denied
    one.
-   **shipping** and **refunds**, `LlmAgent`s, write the reply, calling the
    `OrderService` methods annotated with `@Tool` to track the order or to
    create a return label.
-   **refund_denied** and **ask_for_order**, function nodes, reply without a
    model: the first explains a denied refund, and the second asks for an order
    number when there is no order or the request is unsupported.
-   **reply**, the function node that every branch leads to, returns the reply
    it receives as the workflow's output.

The code:

-   [`SupportWorkflowConfiguration.java`](src/main/java/com/google/adk/kt/examples/springboot/SupportWorkflowConfiguration.java)
    defines the workflow as a Spring bean: the agents, the function nodes,
    and the routes.
-   [`OrderService.java`](src/main/java/com/google/adk/kt/examples/springboot/OrderService.java)
    is the Spring bean the workflow uses: order lookups, the refund policy, and
    the `@Tool` methods.
-   [`SupportService.java`](src/main/java/com/google/adk/kt/examples/springboot/SupportService.java)
    is the Spring `@Service` that runs the workflow, in a session of its own for
    each request.
-   [`SupportController.java`](src/main/java/com/google/adk/kt/examples/springboot/SupportController.java)
    maps the two endpoints onto the service.
-   [`AdkConfiguration.java`](src/main/java/com/google/adk/kt/examples/springboot/AdkConfiguration.java)
    defines the other Spring beans: the `Gemini` model, the session service as
    a `CompletableFutureSessionService`, the `App`, and the `PublisherRunner`.
    [`application.yaml`](src/main/resources/application.yaml) sets the model and
    the Vertex AI project.
-   [`SupportControllerTest.java`](src/test/java/com/google/adk/kt/examples/springboot/SupportControllerTest.java)
    tests both endpoints offline, with a scripted model in place of Gemini.

## Run

On Vertex AI, with Application Default Credentials:

```shell
gcloud auth application-default login
GOOGLE_CLOUD_PROJECT=my-project ./gradlew :google-adk-kotlin-examples-spring-boot-java:bootRun
```

On the Gemini API, with `GOOGLE_CLOUD_PROJECT` unset:

```shell
GOOGLE_API_KEY=... ./gradlew :google-adk-kotlin-examples-spring-boot-java:bootRun
```

The service listens on `localhost:8080`. `GOOGLE_CLOUD_LOCATION` defaults to
`global`. To try another model, change `adk.model` in `application.yaml` or pass
it on the command line, for example `--args='--adk.model=gemini-3.8-flash'`.

The sample orders in `OrderService` are 1042, which is still out for delivery;
1043, delivered 5 days ago; and 1044, delivered 45 days ago. Only 1043 can be
refunded, since the policy allows a refund within 30 days of delivery.

## Case 1: request and response

`POST /support` calls `SupportService.answer`, which runs the workflow and reads
the reply from the `reply` node's output. It returns the reply as a
`Mono<SupportReply>`, which WebFlux writes as JSON:

```shell
curl -s localhost:8080/support -H 'Content-Type: application/json' \
    -d '{"message": "Please refund order 1043, the machine arrived broken."}'
```

```json
{"text": "I am sorry to hear that your espresso machine arrived broken. Your refund for order 1043 has been approved, and your prepaid return label number is RL-1043. Please use this label to ship the item back to us."}
```

## Case 2: streaming with server-sent events

`POST /support/stream` calls `SupportService.streamAnswer`, which runs the
workflow and returns a `Flux<AgentMessage>`. The controller sends each message
as an SSE event as soon as it is ready. Each message has a `type`:

-   `progress`: a message that a workflow node publishes while it works.
-   `reply`: the whole reply from the `reply` node's output, which is the text
    that `POST /support` returns.

The service forwards every partial event as progress without knowing which node
published it, since no model streams tokens. The Java nodes publish no progress,
unlike the nodes in [`../kotlin`](../kotlin), so the stream carries only the
reply:

```shell
curl -N localhost:8080/support/stream -H 'Content-Type: application/json' \
    -d '{"message": "Where is my order 1042?"}'
```

```
data:{"type":"reply","text":"Your order 1042 is currently out for delivery and was last scanned in Brooklyn, NY. It is scheduled to arrive today by 8:00 PM via Example Express."}
```

## How it works

-   **Refund policy in code.** A model never decides a refund: `refund_policy`
    asks `OrderService` for the decision, so the refunds agent runs only for an
    approved refund, and `refund_denied` explains a denial without a model call.
    `createReturnLabel` checks the policy again, so a model cannot create a
    label that the policy forbids.
-   **Conditional routing.** A function node picks the next node with
    `context.setRoutes`: `check_order` emits the customer's intent as a string,
    and `refund_policy` emits the decision as a boolean. Each `route(r -> ...)`
    call maps those routes to nodes, and `otherwise` runs `ask_for_order` when
    no route matches or `check_order` emits none.
-   **Spring beans as tools.** The `OrderService` methods annotated with `@Tool`
    and `@Param` become agent tools. This module compiles with `javac` alone, so
    `ReflectiveTools.fromMethod` reads the annotations at runtime. With the
    Kotlin compiler in the build, as in [`../kotlin`](../kotlin), the KSP
    processor (`google-adk-kotlin-processor`) generates the tools at build time
    instead. Each `@Tool` gives the tool's description, and each `@Param`
    describes an argument.
-   **Node outputs, not session state.** Each `LlmAgent` node sets `mode` to
    `AgentMode.SINGLE_TURN`, the default for an agent with no parent or
    sub-agents: its input is its last user message, and its final answer is its
    output. `check_order` receives the triage agent's `outputSchema` answer as a
    `Triage` record, so it routes on fields rather than on free text. It then
    passes on the order as an `OrderFacts` record, which `FunctionNodes` writes
    as JSON for the next agent. The shipping and refunds agents set
    `includeContents` so they also see the customer's message.
-   **One final node.** Every branch ends at `reply`, which passes on its String
    input: a single-turn agent outputs the text of its final answer, and the
    function nodes return Strings. The service reads the reply from that node's
    output alone, which it finds by `Event.getNodeName()`.
-   **Function nodes as lambdas.** `FunctionNodes.of` builds a `FunctionNode`
    from a Java lambda and a type for each port, whereas the Kotlin version
    calls `node`.
-   **Non-blocking runs.** `PublisherRunner.runAsync` returns a Reactive Streams
    `Publisher<Event>`, which the service adapts with `Flux.from`, so no thread
    blocks while the agents work. When a client disconnects, WebFlux cancels the
    subscription, which cancels the run and its model calls.
-   **Logging context.** `PublisherRunner.runAsync` runs the workflow on
    `Dispatchers.IO` threads, which do not have the caller's SLF4J MDC. So the
    service passes an `MDCContext` from kotlinx-coroutines-slf4j as the run's
    coroutine context: it copies the MDC of the thread that calls `answer` or
    `streamAnswer` and sets it wherever the run continues. The Kotlin version's
    run inherits the caller's coroutine context instead, so a Kotlin caller that
    wants the MDC adds `MDCContext()` to that context.
-   **One session per request.** The service creates a session, runs the
    workflow in it, and deletes it when the run completes, fails, or is
    canceled, with Reactor's `Flux.usingWhen`. `SessionService` is a Kotlin
    `suspend` API, so the service calls it through
    `CompletableFutureSessionService` and adapts each future with
    `Mono.fromFuture`.
-   **Model errors.** A model call that does not finish normally, such as a
    blocked reply, arrives as an event with an error code rather than as an
    exception. An exception from the model API, such as a quota error, also
    arrives as an error event first: the workflow emits one for the failed node
    and rethrows the exception only when the run ends. The service fails the run
    with an `AgentFailedException` at the first error event, as it does when a
    run ends without a reply. The controller answers `POST /support` with 502
    Bad Gateway; the stream has already sent its 200 status, so it ends with an
    `error` event instead.
-   **Spring Boot dependency versions.** The build imports the Spring Boot BOM
    with Gradle's `platform()`, where the highest requested version of a library
    wins. The `io.spring.dependency-management` plugin would instead force the
    BOM's versions, such as an older `kotlinx-coroutines` than the ADK needs.

The workflow API is experimental (`@ExperimentalWorkflowApi`) and may change.
The endpoints have no authentication, so the service binds loopback; put
authentication in front of it before setting `server.address` to expose it.
