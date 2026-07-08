# The KSP processor

> Internal. Part of the ADK Kotlin specification: the repo-root `SPEC.md` is the
> charter and index, and `DECISIONS.md` holds the `[D-n]` design decisions. Not
> exported to GitHub.

*Module `google-adk-kotlin-processor`.*

This module is a KSP (Kotlin Symbol Processing) compiler plugin, not a runtime
library [D-7]. It is registered through the KSP `SymbolProcessorProvider`
service (`FunctionToolProcessorProvider`), which creates a
`FunctionToolProcessor` (a `SymbolProcessor`). The processor finds all functions
carrying `@Tool`, groups class methods by parent class and top-level functions
by containing file, and hands each group to `FunctionToolGenerator`.

**Logic.** For each `@Tool` function, `FunctionToolGenerator` emits a
`FunctionTool` subclass (using KotlinPoet) with two overrides:

-   `suspend fun execute(context: ToolContext, args: Map<String, Any>): Any` -
    coerces each JSON argument to the declared Kotlin type, invokes the user
    function, and serializes the result. Coercion is dispatched per supported
    type:
    -   primitives and `String` (`Int`, `Double`, `Float`, `Boolean`, `String`)
        via numeric/`as?` coercion;
    -   enums via `valueOf`;
    -   data classes recursively, rebuilt from their primary-constructor
        parameters with circular-reference detection;
    -   `List<primitive|dataclass>` and `Map<String, primitive|dataclass>`
        element-wise;
    -   `Unit` (the "no response yet" signal). A `ToolContext`-typed parameter
        is passed straight through as `context`. An invalid enum value or a
        missing required argument short-circuits to an error map keyed by
        `FunctionTool.ERROR_KEY`. On success the return value is serialized to
        JSON-native maps and wrapped as `mapOf(BaseTool.RESULT_KEY to
        <serialized>)`. The generated `execute` does not wrap the user call in
        try/catch, so user exceptions propagate to the tool-error callback
        pipeline.
-   `declaration(): FunctionDeclaration?` - builds the GenAI
    `FunctionDeclaration` (name, description, and, when there are non-context
    parameters, a `Schema(type = OBJECT, properties = ..., required = ...)`),
    filtering out any `ToolContext` parameter.

Alongside the tool classes, the generator emits accessor functions:
`generatedTools()` (an extension on the enclosing class receiver, constructing
each tool with `this`) for class methods, and a top-level
`get<File>GeneratedTools()` (constructing each tool with no args) for top-level
functions.

**Compile-time error conditions.** Each is reported via `KSPLogger.error(...)`,
which fails the KSP build and prevents a partial tool from being emitted:

1.  a `Flow`-returning (streaming) tool function;
2.  a non-nullable parameter that has a default value;
3.  an unsupported parameter type;
4.  an unsupported `List` element type (not a primitive/String or data class);
5.  a circular data-class reference (detected in both coercion and schema
    building);
6.  an unsupported return type for serialization;
7.  a `List` parameter missing its element type argument in the schema.

**Not a runtime API.** The processor internals (`FunctionToolProcessorProvider`,
`FunctionToolProcessor`, `FunctionToolGenerator`) are `public` but are
compile-time implementation details invoked only by KSP; they are not part of
the runtime API.
