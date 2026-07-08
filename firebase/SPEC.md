# Firebase module

> Internal. Part of the ADK Kotlin specification: the repo-root `SPEC.md` is the
> charter and index, and `DECISIONS.md` holds the `[D-n]` design decisions. Not
> exported to GitHub.

*Module `google-adk-kotlin-firebase-android`.*

A `Model` implementation backed by Firebase AI (Vertex AI in Firebase / Gemini
via the Firebase SDK). It is an Android library that provides only model
generation - no auth, session, artifact, or Firestore integration.

## Firebase

`Model` created through the `create` factory. `generateContent` converts the
request, calls the Firebase generative model, and returns the response as a
single-emission `Flow`.

```kotlin
/**
 * Implementation of the Model interface using Firebase AI.
 *
 * Limitation: with thinking models only InMemorySessionService is supported
 * (thought signatures cannot be persisted).
 */
class Firebase private constructor(override val name: String, val firebaseAI: FirebaseAI) : Model {

  companion object {
    /** Creates a new Firebase model. */
    fun create(
      name: String,       // underlying model name, e.g. "gemini-3.1-flash-lite"
      firebaseAI: FirebaseAI,
    ): Firebase
    // (private trace(...) overloads omitted)
  }

  override fun generateContent(request: LlmRequest, stream: Boolean): Flow<LlmResponse>
}
```

**Status.**

-   Streaming is not supported: the streaming path throws
    `UnsupportedOperationException("Streaming is not supported yet.")`, so
    `generateContent(stream = true)` fails.
-   Tracing is a no-op (private `trace(...)` stubs), tracked by b/514250362.
-   With thinking models only `InMemorySessionService` works, because Firebase's
    thought-signature `Part` field is not public and cannot be persisted or
    restored.
-   `Conversions` logs a warning for three features it cannot map: the
    `Retrieval` tool (returns null), `GoogleSearch.excludeDomains`, and
    `GoogleMaps.enableWidget`. It also drops `safetySettings` and `toolConfig`
    (both hard-coded to null), but silently - with no warning.

**Internal (excluded).** `Conversions` (ADK <-> Firebase AI type mapping) and
`AnySerializations` (`Any <-> JsonElement` helper) are `internal` and not part
of the public API.

--------------------------------------------------------------------------------
