# Annotations

> Internal. Part of the ADK Kotlin specification: the repo-root `SPEC.md` is the
> charter and index, and `DECISIONS.md` holds the `[D-n]` design decisions. Not
> exported to GitHub.

*Package `com.google.adk.kt.annotations`.*

The annotations are declared in core but have no runtime behavior on their own;
their effect is produced at compile time by the KSP processor module described
in the `processor` module spec.

## Tool

Marks a function to be exposed as a tool. `name` and `description` default to
the function name and its KDoc summary; `requireConfirmation` and
`isLongRunning` are forwarded into the generated `FunctionTool`.

```kotlin
package com.google.adk.kt.annotations

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class Tool(
  val name: String = "",
  val description: String = "",
  val requireConfirmation: Boolean = false,
  val isLongRunning: Boolean = false,
)
```

## Param

Supplies an explicit per-parameter description; when absent the `@param` KDoc
tag is used instead.

```kotlin
package com.google.adk.kt.annotations

@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.BINARY)
annotation class Param(val description: String = "")
```

## FrameworkInternalApi

An opt-in marker (`@RequiresOptIn`, ERROR level) applied to APIs reserved for
framework use.

```kotlin
package com.google.adk.kt.annotations

@RequiresOptIn(
  message = "This API is for ADK framework use only.",
  level = RequiresOptIn.Level.ERROR,
)
@Target(
  AnnotationTarget.CLASS,
  AnnotationTarget.FUNCTION,
  AnnotationTarget.PROPERTY,
  AnnotationTarget.CONSTRUCTOR,
)
annotation class FrameworkInternalApi
```

## ExperimentalResumabilityFeature

An opt-in marker (`@RequiresOptIn`, ERROR level) guarding the experimental
resumability API, whose surface may change at any time.

```kotlin
package com.google.adk.kt.annotations

@MustBeDocumented
@Retention(AnnotationRetention.BINARY)
@RequiresOptIn(
  level = RequiresOptIn.Level.ERROR,
  message =
    "ADK resumability is an experimental feature whose API may change at any time. " +
      "Opt in with @OptIn(ExperimentalResumabilityFeature::class) to acknowledge the risk.",
)
annotation class ExperimentalResumabilityFeature
```
