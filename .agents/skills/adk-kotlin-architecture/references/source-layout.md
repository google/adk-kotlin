# Source layout and expect/actual pairs

Core is Kotlin Multiplatform with targets `jvm()` and the AGP 9 KMP Android
library. Two custom intermediate source sets sit between `commonMain` and the
leaves, which is why `gradle.properties` disables the default hierarchy
template.

```
commonMain
  agents annotations apps artifacts callbacks collections crypto events ids
  logging memory models platform plugins processors runners serialization
  sessions skills summarizer telemetry telemetry/noop tools types
commonJvmAndroidMain          (dependsOn commonMain)
  artifacts/FileArtifactService  interop/*  models/GoogleCredentials(actual)
  plugins/DebugLoggingPlugin  runners/ReplRunner  sessions/Lock(actual)
  telemetry/otel  tools/FunctionToolExtensions  collections crypto ids platform skills
jvmMain                       (dependsOn commonJvmAndroidMain)
  artifacts/GcsArtifactService  gcp  interop/ReflectiveTools  logging (Flogger, Slf4j)
  memory (Vertex Memory Bank, RAG)  serialization  sessions/VertexAiSessionService  tools/mcp
androidMain                   (dependsOn commonJvmAndroidMain)
  artifacts/FileArtifactServiceAndroid  logging  memory/appsearch
  serialization  sessions/room  skills/AssetSkillSource  tools/appfunctions
```

Tests mirror this: `commonTest`, `commonJvmAndroidTest`, `jvmTest`,
`androidHostTest` (Robolectric), `androidDeviceTest` (instrumented).
`src/jvmAndroidKspTest/kotlin` is an extra source dir added to both leaf test
sets for KSP-generated `@Tool` fixtures, because a `common*` test set cannot
reference per-platform KSP output.

## Placement rules

- Platform-neutral runtime code: `commonMain`. It may use kotlinx
  coroutines, serialization, `kotlin.uuid`, `kotlin.time`, and the GenAI
  Kotlin SDK, but no `java.*` beyond what the stdlib surfaces.
- Anything that needs `java.util.concurrent`, `java.io`, OpenTelemetry, or
  Reactive Streams: `commonJvmAndroidMain`.
- MCP, Google Cloud client libraries, Flogger: `jvmMain`.
- Room, AppSearch, AppFunctions, Android `Context`: `androidMain`.
- Test fixtures shared with other modules: the `testing` module (source-only,
  not published).

## expect / actual pairs

| `expect` in commonMain | `actual` |
|---|---|
| `concurrentMutableMapOf()`, `concurrentMutableListOf()` (`collections/Concurrent.kt`) | commonJvmAndroidMain: `ConcurrentHashMap`, `CopyOnWriteArrayList` |
| `internal fun sha256Hex(String)` (`crypto/Sha256.kt`) | commonJvmAndroidMain |
| `internal fun getUuid(): Uuid` (`ids/Uuid.kt`) | commonJvmAndroidMain |
| `internal fun getLoggerFactory()` (`logging/LoggerFactory.kt`) | jvmMain (`JvmLoggerFactory`), androidMain (`AndroidLoggerFactory`) |
| `expect class GoogleCredentials` (`models/GoogleCredentials.kt`) | commonJvmAndroidMain: `actual typealias` to the Google auth library class |
| `internal fun getEnv(String)` (`platform/Env.kt`) | commonJvmAndroidMain |
| `internal fun getJson(): Json` (`serialization/Json.kt`) | jvmMain (`JvmJson`), androidMain (`AndroidJson`) |
| `fun Lock(): Lock` (`sessions/Lock.kt`) | commonJvmAndroidMain |
| `defaultTracer()`, `getTestTracer()`, `internalSetTestTracer()`, `internalResetTestTracer()`, `currentTelemetryContext()` (`telemetry/Telemetry.kt`) | commonJvmAndroidMain (OpenTelemetry) |

`-Xexpect-actual-classes` is on globally because `GoogleCredentials` is an
expect class. Only logging and JSON are actualised separately per platform;
everything else is shared in `commonJvmAndroidMain`.

## Other modules

| Module | Depends on core as | Contents |
|---|---|---|
| `processor` | `implementation` | KSP `FunctionToolProcessor`, registered in `META-INF/services` |
| `testing` | test fixtures | fake models and helpers used by core and a2a tests |
| `webserver` | `implementation` | Ktor server: `AdkApiServer`, `AdkDevServer`, `AdkServerConfig`, Dev UI static assets |
| `integrations` | `api` | BigQuery analytics plugin |
| `a2a` | `api` | A2A client agent and server wiring |
| `litertlm` | `api` | `LiteRtLm` on-device `Model` |
| `firebase` | `api` | Firebase AI `Model` for Android |
| `mlkit` | `api` | ML Kit Gemini Nano `Model` for Android, no tool calling |
