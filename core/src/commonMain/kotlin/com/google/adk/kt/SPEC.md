# Supporting infrastructure

> Internal. Part of the ADK Kotlin specification: the repo-root `SPEC.md` is the
> charter and index, and `DECISIONS.md` holds the `[D-n]` design decisions. Not
> exported to GitHub.

*Package `com.google.adk.kt` and its sub-packages `serialization`, `logging`,
`ids`, `collections`, `platform`.*

These sub-packages hold the cross-cutting infrastructure that the rest of the
framework builds on: JSON, logging, id generation, concurrent collections,
schema validation, and the version constant. Several of them expose a single
instance through a companion that delegates to a platform-specific `actual` (see
the expect/actual note at the end).

## Json

Platform-independent JSON serialization. The companion delegates to the platform
implementation, so `Json.toJsonString(...)` is callable directly.

```kotlin
package com.google.adk.kt.serialization

/** Platform-independent utility for JSON serialization. */
interface Json {
  fun toJsonString(obj: Any?): String
  fun fromJsonToMap(json: String): Map<String, Any?>

  // Companion delegates to the platform-specific impl via the expect/actual `getJson()`.
  companion object : Json by getJson()
}
```

## Level

Severity levels for log messages.

```kotlin
package com.google.adk.kt.logging

/** Represents the severity level of a log message. */
enum class Level {
  TRACE,
  DEBUG,
  INFO,
  WARN,
  ERROR,
}
```

## Logger

A logger with lazy message lambdas and per-level convenience methods.

```kotlin
package com.google.adk.kt.logging

/** An interface representing a logger. Allows logging messages at various severity [Level]s. */
interface Logger {
  val name: String
  fun log(level: Level, cause: Throwable? = null, msg: () -> String)
  fun trace(cause: Throwable? = null, msg: () -> String) = log(Level.TRACE, cause, msg)
  fun debug(cause: Throwable? = null, msg: () -> String) = log(Level.DEBUG, cause, msg)
  fun info(cause: Throwable? = null, msg: () -> String) = log(Level.INFO, cause, msg)
  fun warn(cause: Throwable? = null, msg: () -> String) = log(Level.WARN, cause, msg)
  fun error(cause: Throwable? = null, msg: () -> String) = log(Level.ERROR, cause, msg)
}
```

## SafeLogger

`Logger` base that evaluates the message lambda safely, catching exceptions
thrown while building a message.

```kotlin
package com.google.adk.kt.logging

/** Abstract [Logger] base that safely evaluates the log message lambda, catching exceptions. */
abstract class SafeLogger : Logger {
  protected abstract fun doLog(level: Level, cause: Throwable? = null, msg: () -> String)
  override fun log(level: Level, cause: Throwable?, msg: () -> String)
}
```

## LoggerFactory

Obtains `Logger`s by class. The companion delegates to the platform factory.

```kotlin
package com.google.adk.kt.logging

import kotlin.reflect.KClass

/** A factory object for obtaining [Logger] instances. */
interface LoggerFactory {
  fun getLogger(kClass: KClass<*>): Logger

  // Companion delegates to the platform-specific factory via the expect/actual `getLoggerFactory()`.
  companion object : LoggerFactory by getLoggerFactory()
}

// Internal expect (see Internal section): internal expect fun getLoggerFactory(): LoggerFactory
```

## LoggingProvider

A service that supplies `Logger`s for given classes.

```kotlin
package com.google.adk.kt.logging

import kotlin.reflect.KClass

/** A service that provides [Logger] instances for given classes. */
interface LoggingProvider {
  fun getLogger(kClass: KClass<*>): Logger
}
```

## Uuid

Random UUID generation. The companion delegates to the platform implementation.

```kotlin
package com.google.adk.kt.ids

/** Utility for generating random UUIDs. */
interface Uuid {
  fun random(): String

  // Companion delegates to the platform-specific impl via the expect/actual `getUuid()`.
  companion object : Uuid by getUuid()
}
```

## concurrentMutableMapOf

Creates a concurrent mutable map. Declared `expect` in common code with a
per-platform `actual`.

```kotlin
package com.google.adk.kt.collections

/** Creates a concurrent mutable map. */
expect fun <K : Any, V : Any> concurrentMutableMapOf(): MutableMap<K, V>
```

## SchemaUtils

Validates argument maps and model output against a `Schema`. Both members return
a `Result`; the private `matchType` helper is excluded. `SchemaUtils` is a
Kotlin `object` (singleton). `validateOutputSchema` accepts only top-level
object schemas [D-13].

```kotlin
package com.google.adk.kt

import com.google.adk.kt.types.Schema

/** Utility class for validating schemas. */
object SchemaUtils {
  fun validateMapOnSchema(args: Map<String, Any?>, schema: Schema, argsName: String): Result<Unit>
  fun validateOutputSchema(output: String, schema: Schema): Result<Map<String, Any?>>
  // (matchType is private - excluded.)
}
```

## VERSION

The published framework version, a top-level constant.

```kotlin
package com.google.adk.kt

const val VERSION = "0.5.0"
```

## The expect/actual companion pattern

Several infra interfaces expose a single instance through a companion that
delegates to an `internal expect fun`; `commonMain` declares the `expect` and
each target source set supplies the `actual` [D-1]. `Uuid`, `LoggerFactory`, and
`Json` follow this shape (`getUuid()`, `getLoggerFactory()`, `getJson()`
respectively), while `concurrentMutableMapOf` and `platform.getEnv` are plain
`expect fun`s with no companion. The wiring functions, the `AnySerializer`
`KSerializer`, and `platform.getEnv` are `internal` and not part of the public
surface.

The kotlinx `adkJson` config and the `anyToJsonElement` / `jsonElementToAny`
converters are exposed as `@FrameworkInternalApi` (public for cross-module reuse
but opt-in, so not part of the stable contract), letting other modules reuse
ADK's reflection-free `kotlinx.serialization` setup instead of a parallel JSON
stack. Callers opt in with `@OptIn(FrameworkInternalApi::class)`. [D-6]

--------------------------------------------------------------------------------
