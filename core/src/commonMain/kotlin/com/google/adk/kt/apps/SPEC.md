# Apps

> Internal. Part of the ADK Kotlin specification: the repo-root `SPEC.md` is the
> charter and index, and `DECISIONS.md` holds the `[D-n]` design decisions. Not
> exported to GitHub.

*Package `com.google.adk.kt.apps`.*

An `App` bundles the root agent with its plugins and optional resumability and
compaction config. It is the recommended single source of truth passed to a
runner.

## App

Immutable bundle of an app name, root agent, plugins, and optional resumability
and events-compaction config.

```kotlin
data class App(
  val appName: String,
  val rootAgent: BaseAgent,
  val plugins: List<Plugin> = emptyList(),
  val resumabilityConfig: ResumabilityConfig? = null,
  val eventsCompactionConfig: EventsCompactionConfig? = null,
)
```

**Logic.** The `init` block validates `appName`: it must match the identifier
pattern `[a-zA-Z_][a-zA-Z0-9_]*` and must not be the reserved value `"user"`.
The companion object is private.
