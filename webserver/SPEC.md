# Web server module

> Internal. Part of the ADK Kotlin specification: the repo-root `SPEC.md` is the
> charter and index, and `DECISIONS.md` holds the `[D-n]` design decisions. Not
> exported to GitHub.

*Module `google-adk-kotlin-webserver`.*

Embedded Ktor (Netty) HTTP+SSE server that exposes the ADK dev/web API and
serves the prebuilt Angular "Dev UI" static assets. Its REST paths mirror the
Python ADK FastAPI api_server.

## AdkWebServer

Server entry point. It builds an `embeddedServer(Netty, port)`, installs
`adkModule(...)`, and starts/stops it. The current constructor takes no
`Runner` - a fresh runner is created per request instead. The secondary
constructor that accepts a `runner: Runner` is deprecated.

```kotlin
/**
 * Embedded Ktor server exposing the ADK dev/web API.
 *
 * @property captureMessageContent When true, records prompt/response content into
 *   telemetry spans (may capture PII); defaults to false.
 */
class AdkWebServer(
  private val port: Int = 8080,
  private val sessionService: SessionService,
  private val artifactService: ArtifactService,
  private val agentLoader: AgentLoader,
  private val apiServerSpanExporter: ApiServerSpanExporter,
  private val captureMessageContent: Boolean = false,
) {

  @Deprecated(
    message = "Use constructor without runner",
    replaceWith =
      ReplaceWith(
        "AdkWebServer(port, sessionService, artifactService, agentLoader, apiServerSpanExporter)"
      ),
    level = DeprecationLevel.WARNING,
  )
  constructor(
    port: Int = 8080,
    sessionService: SessionService,
    artifactService: ArtifactService,
    runner: Runner,
    agentLoader: AgentLoader,
    apiServerSpanExporter: ApiServerSpanExporter,
    captureMessageContent: Boolean = false,
  )

  fun start(wait: Boolean = false)

  fun stop()

  // Nested public types
  class InstantTypeAdapter : TypeAdapter<Instant>() {
    override fun write(out: JsonWriter, value: Instant?)
    override fun read(reader: JsonReader): Instant?
  }

  public class StatusAwareLogger(private val delegate: Logger) : Logger by delegate {
    override fun info(msg: String?)
  }
}
```

## Application.adkModule

Ktor `Application` module that installs `CallLogging` and
`ContentNegotiation(gson)` (Ktor 2.x has no SSE plugin, so `/run_sse` streams
manually as `text/event-stream`), configures OpenTelemetry via
`OpenTelemetryConfig`, sets `TelemetryConfig.captureMessageContent`, and wires
all routes under `routing { ... }`. The current overload takes no `Runner`; the
overload that accepts a `runner: Runner` is deprecated.

```kotlin
fun Application.adkModule(
  sessionService: SessionService,
  artifactService: ArtifactService,
  agentLoader: AgentLoader,
  apiServerSpanExporter: ApiServerSpanExporter,
  captureMessageContent: Boolean = false,
)
```

```kotlin
@Deprecated(
  message = "Use adkModule without runner",
  replaceWith =
    ReplaceWith("adkModule(sessionService, artifactService, agentLoader, apiServerSpanExporter)"),
  level = DeprecationLevel.WARNING,
)
fun Application.adkModule(
  sessionService: SessionService,
  artifactService: ArtifactService,
  runner: Runner,
  agentLoader: AgentLoader,
  apiServerSpanExporter: ApiServerSpanExporter,
  captureMessageContent: Boolean = false,
)
```

## AgentLoader

Interface implemented by callers to expose agents to the web server by name.

```kotlin
/** Interface for loading agents to the ADK Web Server. */
interface AgentLoader {
  /** Returns available agent names; never null (empty list if none). */
  fun listAgents(): List<String>

  /** Loads the agent by name, or null if not found. */
  fun loadAgent(agentName: String): BaseAgent?
}
```

## SingleAgentLoader

`AgentLoader` that serves one fixed agent regardless of the requested name.

```kotlin
/** An [AgentLoader] implementation that loads a single agent. */
class SingleAgentLoader(private val agent: BaseAgent) : AgentLoader {
  override fun listAgents(): List<String>
  override fun loadAgent(agentName: String): BaseAgent?
}
```

## ApiModels

Gson-serialized request/response data classes for the HTTP API.

```kotlin
data class AgentRunRequest(
  val appName: String,
  val userId: String,
  val sessionId: String? = null,
  val newMessage: Content? = null,
  val streaming: Boolean = false,
  val stateDelta: Map<String, Any>? = null,
  val invocationId: String? = null,
)

data class RunRequest(val agentId: String, val input: String, val sessionId: String? = null)

data class RunResponse(val output: String, val sessionId: String)

data class TurnModel(val role: String, val content: String)

data class SessionModel(val sessionId: String, val turnHistory: List<TurnModel>)

data class ErrorResponse(val error: String, val message: String, val details: String? = null)

// Model representing an SSE (Server-Sent Event) message.
data class SseModel(val type: String, val content: String, val timestamp: String)

data class SessionDto(
  val id: String?,
  val appName: String,
  val userId: String,
  val state: Map<String, Any>?,
  val events: List<com.google.adk.kt.events.Event>?,
  val lastUpdateTime: Long?,
)
```

## AgentGraphGenerator

Generates Graphviz DOT for an agent tree (agents as ellipses, tools as boxes),
optionally highlighting call edges via `HighlightDirection`.

```kotlin
/** Generates Graphviz DOT representations of agent structures. */
class AgentGraphGenerator(private val agentLoader: AgentLoader) {

  enum class HighlightDirection {
    NONE,
    FORWARD,
    REVERSE,
  }

  /** Generates the DOT graph for the named agent (empty string if not found). */
  fun generateGraph(
    agentName: String,
    highlightPairs: List<Pair<String, String>> = emptyList(),
  ): String

  /** Generates the DOT graph for the given root agent. */
  fun generateGraph(rootAgent: BaseAgent, highlightPairs: List<Pair<String, String>>): String

  // companion object Colors { ... } holds only private members.
}
```

## ApiServerSpanExporter

`SpanExporter` that stores relevant span data in-memory, keyed by event id and
session id, for the trace view.

```kotlin
/** A custom SpanExporter that stores relevant span data by event-id and session-id. */
class ApiServerSpanExporter : SpanExporter {
  // NOTE: eventIdTraceStorage and sessionToTraceIdsMap are `internal val` -> excluded.

  fun getEventTraceAttributes(eventId: String): Map<String, Any>?

  fun getSessionToTraceIdsMap(): Map<String, List<String>>

  fun getAllExportedSpans(): List<SpanData>

  override fun export(spans: Collection<SpanData>): CompletableResultCode

  override fun flush(): CompletableResultCode

  override fun shutdown(): CompletableResultCode
}
```

## OpenTelemetryConfig

Builds the tracer provider and OpenTelemetry SDK wired to an
`ApiServerSpanExporter`.

```kotlin
class OpenTelemetryConfig(private val apiServerSpanExporter: ApiServerSpanExporter) {
  fun sdkTracerProvider(): SdkTracerProvider

  fun openTelemetrySdk(sdkTracerProvider: SdkTracerProvider): OpenTelemetry
}
```

## HTTP / SSE endpoints

Responses are gson JSON unless noted. The per-route registration helpers (`fun
Route.runRoutes(...)`, `sessionRoutes`, `artifactRoutes`, ...) are declared
`public` for the Ktor routing DSL but are not intended as a user API, so they
are omitted from the listings above.

Method | Path                                              | Response
------ | ------------------------------------------------- | --------
GET    | `/api/health`                                     | `"OK"` text
GET    | `/list-apps`                                      | `List<String>` agent names
POST   | `/run`                                            | `List<Event>` (collected, non-streaming)
POST   | `/run_sse`                                        | SSE stream (`text/event-stream`) of gson-encoded `Event`s, streamed manually
GET    | `/apps/{appName}/users/{userId}/sessions`         | `List<SessionDto>`
POST   | `/apps/{appName}/users/{userId}/sessions`         | new `SessionDto` (server-generated id)
GET    | `.../sessions/{sessionId}`                        | `SessionDto` or 404
POST   | `.../sessions/{sessionId}`                        | `SessionDto` (create w/ id)
DELETE | `.../sessions/{sessionId}`                        | 204 No Content
GET    | `.../sessions/{sessionId}/artifacts`              | `List<artifactKeys>`
POST   | `.../artifacts`                                   | saved `Part` (200)
GET    | `.../artifacts/{artifactName}`                    | `Part` or 404
DELETE | `.../artifacts/{artifactName}`                    | 204 No Content
GET    | `.../sessions/{sessionId}/events/{eventId}/graph` | `{"dot": <graphviz DOT>}`
GET    | `/debug/trace/{eventId}`                          | trace attribute map or 404
GET    | `/debug/trace/session/{sessionId}`                | `List<span map>`
GET    | `/apps/{app_name}/eval_results`                   | 501 Not Implemented
GET    | `/apps/{app_name}/eval_sets`                      | 501 Not Implemented
POST   | `/apps/{app_name}/eval_sets/{eval_set_id}`        | 501 Not Implemented
POST   | `.../eval_sets/{eval_set_id}/add_session`         | 501 Not Implemented
GET    | `.../eval_sets/{eval_set_id}/evals`               | 501 Not Implemented
POST   | `.../eval_sets/{eval_set_id}/run_eval`            | 501 Not Implemented
GET    | `/dev-ui`, `/dev-ui/`                             | redirect / index.html (static UI)
GET    | `/`                                               | redirect to `/dev-ui`

**Logic.** Both `/run` and `/run_sse` build a fresh `InMemoryRunner(agent,
sessionService, artifactService, appName)` per request and call
`runner.runAsync(...)`. If `loadAgent` returns null they respond 404 "Agent not
found". `sessionId` defaults to a random UUID when absent. `/run` collects the
events and returns them non-streaming: it forces `StreamingMode.NONE` regardless
of the request's `streaming` flag, so its streaming branch is a known no-op.
`/run_sse` streams events manually as `text/event-stream` (Ktor 2.x has no SSE
plugin), using `StreamingMode.SSE` when `streaming=true` and `NONE` otherwise.

**Status.** All six `/eval*` endpoints return `HttpStatusCode.NotImplemented`
("Not implemented yet"): `GET /apps/{app_name}/eval_results`, `GET
/apps/{app_name}/eval_sets`, `POST /apps/{app_name}/eval_sets/{eval_set_id}`,
`POST .../eval_sets/{eval_set_id}/add_session`, `GET
.../eval_sets/{eval_set_id}/evals`, and `POST
.../eval_sets/{eval_set_id}/run_eval`. The `/run` streaming branch is a no-op.
No other not-implemented markers exist in the module.

--------------------------------------------------------------------------------
