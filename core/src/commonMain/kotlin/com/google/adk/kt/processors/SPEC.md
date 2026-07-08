# Instructions and the LLM request pipeline

> Internal. Part of the ADK Kotlin specification: the repo-root `SPEC.md` is the
> charter and index, and `DECISIONS.md` holds the `[D-n]` design decisions. Not
> exported to GitHub.

## Instruction interpolation

*Package `com.google.adk.kt.processors` (`InstructionStateInjector`, internal).*

`InstructionStateInjector` resolves `{var}` placeholders in a dynamic
instruction against session state and artifacts. It is internal architecture,
not public API. `InstructionsProcessor` calls it once per text part of the
resolved dynamic instruction; the static instruction is never interpolated.

The interpolation algorithm:

1.  A single regex pass walks the template and matches every `{...}` placeholder
    (one-or-more `{`, a run of non-brace characters, one-or-more `}`). Literal
    text between matches is copied verbatim, so the result is built in order in
    one pass. An empty template yields the empty string.
2.  Each matched placeholder is trimmed of all surrounding braces and whitespace
    to produce the variable name (so `{{x}}` yields `x`).
3.  A trailing `?` marks the variable optional and is stripped from the name
    before resolution.
4.  The name is resolved by category:
    -   `artifact.` prefix - the remainder after `artifact.` is loaded via the
        artifact service and JSON-encoded into the output.
    -   State variable - the name must be a valid state name. Valid prefixes are
        `app:`, `user:`, and `temp:`; a name with one of these prefixes must be
        followed by a valid identifier, and a name with no prefix must itself be
        a valid identifier (first character a letter or `_`, remaining
        characters letters, digits, or `_`). A valid name is looked up in
        session state.
    -   Invalid name - if the name is not a valid state name, the original
        placeholder text is left literal (no substitution and no error).
5.  Missing value: if the variable is optional (`?`), the result is the empty
    string; otherwise an `IllegalArgumentException` is thrown with a
    category-specific message (`Artifact <name> not found.` for artifacts,
    ``Context variable not found: \`<name>\`.`` for state variables).

Worked examples:

-   `{user_name}` with state `user_name = "Ada"` - `Ada`.
-   `{user:pref}` with state key `user:pref = "dark"` - `dark` (valid prefix).
-   `{missing?}` (absent, optional) - the empty string.
-   `{missing}` (absent, not optional) - throws ``Context variable not found:
    \`missing\` ``.
-   `{artifact.notes}` - the loaded artifact as JSON, or throws `Artifact notes
    not found.` unless suffixed with `?`.
-   `{not a var!}` - not a valid identifier, so left literal as `{not a var!}`.

--------------------------------------------------------------------------------

## The LLM request pipeline (internal)

*Package `com.google.adk.kt.processors`.*

The request-processor pipeline is internal architecture and may change without
notice; making it overridable is tracked as b/496978873, and Python's
`IdentityProcessor` (which would sit between Instructions and Contents) is
currently omitted (b/488289124).

Each turn builds an `LlmRequest` by folding a fixed list of request processors,
in order, over an initially empty request. The default order is [D-8]:

1.  `BasicRequestProcessor`
2.  `RequestConfirmationProcessor`
3.  `InstructionsProcessor`
4.  `ContentsProcessor`
5.  `AgentTransferProcessor`
6.  `OutputSchemaProcessor`

The response (after-turn) processor list is empty. After the six processors
fold, each direct tool's `processLlmRequest` runs, then each toolset's
`processLlmRequest` - this is where ordinary declared tools are attached to the
request. `ToolProcessor` exists but is not part of the default pipeline;
declared tools reach the request through the per-tool `processLlmRequest` phase,
not through `ToolProcessor` [D-8].

Order is significant: later processors depend on earlier ones.
`BasicRequestProcessor` runs first and pre-decides the output-schema strategy by
anticipating the injected transfer tool, and `OutputSchemaProcessor` runs last
so it sees the fully assembled tool set, including the `transfer_to_agent` tool
added by `AgentTransferProcessor`.

### BasicRequestProcessor

**Logic.**

1.  Require the agent to be an `LlmAgent`.
2.  Read the base config from `agent.generateContentConfig`, defaulting to an
    empty `GenerateContentConfig()`.
3.  If `agent.outputSchema != null` and `appliesOutputSchemaDirectly` is true,
    copy the config with `responseSchema = outputSchema` and `responseMimeType =
    "application/json"` (direct output schema); otherwise leave the config
    unchanged and let `OutputSchemaProcessor` handle the schema later.
4.  Return the request with `model = agent.model` and the computed config.

### RequestConfirmationProcessor

**Logic.**

1.  Require the agent to be an `LlmAgent`.
2.  Scan all session events on the current branch (not invocation-scoped), so a
    confirmation reply that lands in a fresh invocation is still recovered.
3.  Find the last user-authored event; if there is none, or it carries no
    function responses, return the request unchanged.
4.  Keep responses named `adk_request_confirmation` that have a non-null id and
    a parseable `ToolConfirmation`, keyed by synthetic id.
5.  Recover the original held call for each synthetic id from the embedded
    `originalFunctionCall`, keyed by original id.
6.  De-dup executed ids: drop any original id that already has a function
    response appearing after the last user event (a previous pass this turn
    already re-executed it).
7.  Re-execute the remaining original calls via `handleFunctionCalls`, passing
    the confirmations so a tool that requires confirmation proceeds past its
    `requiresConfirmation` check; emit any resulting event.
8.  Return the request unchanged - this processor never mutates the request.

The confirmation payload is accepted in two wire formats: a wrapped form (a
single `response` key holding a JSON string that decodes to a
`ToolConfirmation`) or a direct form (the map itself carrying `confirmed` /
`payload` / `hint`).

### InstructionsProcessor

**Logic.**

1.  No-op for non-LlmAgents.
2.  If a static instruction is present, append it to the request verbatim - no
    `{var}` substitution is applied (byte-stable cache prefix).
3.  Resolve the dynamic instruction; if there is none, return with only the
    static instruction applied.
4.  Interpolate each text part of the dynamic instruction via
    `InstructionStateInjector` (section 10); non-text parts pass through
    unchanged.
5.  Placement depends on `staticInstruction`: with no static instruction, the
    interpolated instruction is appended to the system instruction; with a
    static instruction present, the interpolated instruction must have role USER
    and is appended as user content (so the cacheable system prefix stays
    stable).

### ContentsProcessor

**Logic.**

1.  Fetch events session-wide across all branches (branch filtering happens
    inside the rewriter).
2.  Determine `includeContents` from the agent, defaulting to `DEFAULT` for
    non-LlmAgents.
3.  Rewrite the history via `HistoryRewriterProcessor.rewrite`. `DEFAULT` uses
    the full filtered history; `NONE` uses only the current turn (the suffix
    starting at the most recent user input or other-agent reply, including tool
    calls and responses within it).
4.  Splice: if `request.contents` is already non-empty (a dynamic instruction
    placed as user content when a static instruction exists), insert those
    contents just after the last non-user / function-response content -
    immediately before the final run of trailing user turns; if no such content
    exists, insert at the front.
5.  Return the request with the assembled contents.

### HistoryRewriterProcessor

This is a helper invoked by `ContentsProcessor`, not itself an
`LlmRequestProcessor`. It rewrites raw session events into the content list the
model sees, in this exact order.

**Logic.**

1.  Scope by `includeContents`: `DEFAULT` keeps all events; `NONE` keeps only
    the current-turn events.
2.  Apply rewinds: for each rewind marker, drop every event from the earliest
    event of the rewound invocation through the rewind event itself.
3.  Filter events: drop off-branch events and framework (`adk_framework`), auth
    (`adk_request_credential`), confirmation (`adk_request_confirmation`), and
    empty-content events. `adk_request_input` events are kept because they carry
    an answer the model must read.
4.  Per-event transform: keep compaction events as-is (expanded later); drop
    null-content events; present other agents' output as USER turns (prefixed
    `For context:`); keep the rest.
5.  Expand compaction: replace kept compaction events with their stored summary
    (re-authored as MODEL, timestamped at the range end) and drop the raw events
    those ranges cover, interleaving summaries and surviving events
    chronologically.
6.  Pair function calls with responses: run
    `rearrangeEventsForLatestFunctionResponse` to place the latest response
    block next to its call event, then
    `rearrangeEventsForAsyncFunctionResponsesInHistory` to move and merge async
    responses immediately after their calls.
7.  Emit contents, stripping framework-generated call and response ids whose id
    starts with `adk-` so the model does not see them.

### AgentTransferProcessor

**Logic.**

1.  Compute transfer targets: always the agent's sub-agents, plus the parent and
    peers when a parent exists - the parent unless `disallowTransferToParent`,
    the peers unless `disallowTransferToPeers`. De-dup by name and require the
    names to be unique.
2.  If there are no targets, return the request unchanged.
3.  Otherwise append the transfer instruction to the system instruction and
    inject the `transfer_to_agent` tool into the request's tool set.

### OutputSchemaProcessor [D-13]

**Logic.**

1.  No-op for non-LlmAgents or agents with no output schema.
2.  If `appliesOutputSchemaDirectly` is true, return the request unchanged - the
    schema was already applied directly by `BasicRequestProcessor`, so this
    avoids double application.
3.  Otherwise install the workaround: add the `set_model_response` tool (its
    declaration uses the output schema as parameters) and append an instruction
    telling the model it may use other tools but must deliver its final answer
    by calling `set_model_response` in the required schema.
4.  The structured answer is later consumed into a synthetic final MODEL event,
    so downstream turn termination and state saving treat it as a normal final
    text response [D-13].
