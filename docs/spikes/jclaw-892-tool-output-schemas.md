# JCLAW-892 — Spike: do tool output schemas earn their place, and what would they validate?

**Status:** research / decision doc — no production code.
**Epic:** JCLAW-833 (agentic harness enhancement).
**Origin:** JCLAW-836 AC1, "JSON schema validation for structured tool outputs", which could not be met as written.
**Date:** 2026-08-03.

## Recommendation

**Do not build it.** Close AC1 as not worth the surface. The requirement rests on a
premise that does not hold in this codebase, targets a defect class that belongs in
tests rather than the request path, and costs materially more than "reuse the
validator we already have."

One adjacent gap *is* real and is a different piece of work: JClaw discards the MCP
protocol's `outputSchema` and `structuredContent` entirely. That is a plumbing gap
with an externally-defined contract, and it is the one place a runtime check has an
argument. Scope it separately (§7).

## 1. What the ticket assumed, and where it was wrong

JCLAW-892 was written from what JCLAW-836 saw at the tool-result chokepoint. Two of
its premises are inaccurate, and correcting them makes the case *stronger*, not weaker.

**"Only `WebSearchTool` emits a `structuredJson` payload at all."** `McpServerTool`
does too. Its discovery call passes the *identical* payload as both fields:

```java
// app/mcp/McpServerTool.java:211
return new ToolRegistry.ToolResult(GSON.toJson(payload), GSON.toJson(payload));
```

**"Everything the model consumes from a tool arrives as prose text."** Nine native
tools return a fixed-key JSON object as their model-visible text, via a shared
`GsonHolder.GSON.toJson(payload, Map.class)` idiom. So the model already reads
structured output; it just arrives through `text`, not `structuredJson`.

That said, the ticket's *operational* claim survives intact: `structuredJson` reaches
`Message.toolResultStructured` and stops there, consumed only by
`frontend/components/chat/ChatToolCalls.vue`. Validating that field still catches a
rendering defect, not an agent-quality one.

## 2. Q1 — Do any tools have a stable enough output shape to declare?

**Yes, and it is not the shape a schema can describe.**

The registry is 24 native tools declaring 63 actions. Model-visible JSON envelopes,
with their success keys:

| Tool / action | Keys | Site |
|---|---|---|
| `exec` | `exitCode, output, durationMs, truncated, timedOut` | `ShellExecTool.java:506,671` |
| `subagent_spawn` (sync) | `run_id, conversation_id, reply, status` *(+`truncated`)* | `SubagentSpawnTool.java:501` |
| `subagent_spawn` (fan-out) | `run_ids, count, status, hint` *(+`skipped, skipped_count`)* | `SubagentSpawnTool.java:620` |
| `subagent_spawn` (async) | `run_id, conversation_id, status` | `SubagentAsyncRunner.java:144` |
| `subagent_yield` (collect) | `results, count` | `SubagentAsyncRunner.java:259` |
| `subagent_yield` (suspend) | `action, run_id` | `SubagentYieldTool.java:264` |
| `message` (send) | `action, channel, target` | `MessageTool.java:427` |
| `message` (7 other actions) | `action, status` *(+`reason`)* | `MessageTool.java:633` |
| `app_install` (install) | `installed, slug, url, files, source` | `AppInstallTool.java:187` |
| `app_install` (validate) | `valid, issues` | `AppInstallTool.java:371` |
| `app_install` (stage) | `staged, slug, workspacePath, alreadyPresent` | `AppInstallTool.java:382` |
| `conversation_list` | `count, has_more, runs[]` | `ConversationListTool.java:251` |
| `mcp_<server>` (discovery) | `server, actions[{name, description, inputSchema}]` | `McpServerTool.java:211` |

`exec` goes furthest: its five keys are named constants specifically because they are
a contract, with the reason recorded in the code —

> Named because they are a CONTRACT, not incidental strings: two writers … must agree
> with one reader (`postConditionFailure`), and a typo in any of them fails silently.
> — `ShellExecTool.java:105`

**Three properties of these shapes defeat a per-tool output schema:**

1. **The real contract is a union, and the alternative branch is deliberately prose.**
   Every tool in that table also returns a bare `"Error: …"` string on its failure
   paths — `MessageTool` for an undeliverable channel, `AppInstallTool.err(…)`,
   `ShellExecTool` for an empty command. That is not an oversight. `ToolResultVerifier`
   states the design position: *"A tool that failed should still hand the model its
   error — the model needs it to react."* A schema over the success envelope would be
   violated by every correct error return.

2. **The unit is the action, not the tool — and 63 is a floor.** `message` has 8 actions
   across 2 envelope shapes, `app_install` 3 actions across 3, `task` 9 actions.
   `subagent_yield` declares *one* action and returns two unrelated shapes depending on
   whether it collected children or suspended the parent. Declaring output shapes means
   at least 63 declarations plus their error branches, not 24.

3. **Optional keys are load-bearing.** `truncated`, `skipped`, `reason` appear only
   when they apply, each for a documented reason (JCLAW-291, JCLAW-823). A schema
   would mark them optional, which means it asserts nothing about the interesting case.

## 3. Q2 — Would structured output *to the model* improve anything measurable?

**No evidence either way, and the wire format removes the mechanical argument.**

`LlmProvider.serializeMessages` puts a tool result on the wire as a plain string:

```java
// app/llm/LlmProvider.java:672-673
if (msg.content() instanceof String s) {
    obj.addProperty(JSON_CONTENT, s);
}
```

A JSON envelope is a JSON *string* to every provider JClaw talks to. Any benefit is
the model parsing that string more reliably than it parses prose — a claim about model
behaviour, not about the protocol, and one nothing in this repo has tested.

Measuring it is now cheap and was not before: JCLAW-875's eval runner plus the
`tool_result_includes` check could A/B a prose variant against a JSON variant of one
tool over a suite. **That experiment has not been run, so the claim stays unverified
and nothing should be built on it.**

## 4. Q3 — What would a violation mean operationally?

**It would mean we shipped a bug** — a tool broke its own contract. That is
deterministic, reproducible without a model, and detectable before the code merges.

This codebase has already answered this exact question once. JCLAW-827 faced
hand-maintained `@Schema` records sitting beside `HashMap`-based emitters that could
silently drift, and solved it with a **unit test**:

> These tests populate every optional field, run the real emitters via reflection, and
> assert the emitted keys are a subset of the corresponding record's component names —
> failing the moment a map key lacks a documented component.
> — `test/ApiConversationsControllerSchemaDriftTest.java`

A per-turn runtime check finds the same defect strictly later (on a user's turn),
cannot act on it — JCLAW-836 is observe-only by design — and charges every turn
forever for a signal that a test class delivers once at build time. Contract drift
does not belong in the request path.

## 5. Q4 — What would it cost? Is `EvalScorer`'s subset sufficient?

**No. Two gaps, both hit by payloads that exist today.**

The subset is `type, properties, required, items, enum, additionalProperties,
description`, and `EvalDatasetLoader` rejects anything else rather than ignoring it —
the property that makes it trustworthy.

**Gap 1 — no null.** `GsonHolder` enables `serializeNulls()` deliberately, to keep the
wire shape stable across the JCLAW-278 DTO migration. So `conversation_list` emits
literal nulls:

```java
// app/tools/ConversationListTool.java:236-243
row.put("childConversationId", run.childConversation != null ? … : null);
row.put("label", run.label);
row.put("startedAt", run.startedAt != null ? run.startedAt.toString() : null);
```

`EvalScorer.typeMatches` requires `isJsonPrimitive()` for every scalar type, so a
`JsonNull` fails `type: "string"`. Expressing these rows needs `type: ["string","null"]`
or a `nullable` keyword. Neither is in the subset.

**Gap 2 — no `oneOf`/`anyOf`,** which §2 established is the actual shape of every
tool's output.

So "reuse the existing validator" means: extract `validate` (currently `private
static`, wired to `response.output()`) out of `EvalScorer`; add two keyword families;
widen `EvalDatasetLoader`'s allowlist; widen `evals/schema/eval-suite.schema.json`. Each
widening weakens the strictness that makes the validator worth trusting for its
primary job. The efficiency NFR is satisfied — this is a zero-LLM-call check — but
cheap-per-call is not the cost that matters here.

## 6. Q5 — What do comparable harnesses do?

**Neither declares tool output schemas.** Both carry the MCP structured payload; neither
validates it.

**OpenClaw** (`~/Programming/openclaw-project`) — zero occurrences of `outputSchema` in
`src/`. It *emits* MCP `structuredContent` on its own server tools (9 sites in
`src/mcp/channel-tools.ts`) and *consumes* it in
`src/agents/pi-bundle-mcp-materialize.ts`, where it is JSON-stringified into a text part
when no text content is present. Payload without a contract.

**Hermes** (`~/Programming/hermes-agent`) — no `output_schema`. `tools/mcp_tool.py:2236`
combines `content` and `structuredContent` when both are present, with the comment
*"content is model-oriented (text), structuredContent supplements it."*
`tools/computer_use/cua_backend.py:351` reads specific keys off it
(`sc.get("windows")`) — hand-coded key access, no validation, with a fallback path when
the key is absent.

The converged position: carry structure when the protocol offers it, do not validate
it, do not treat it as an agent-quality lever.

## 7. The one gap worth a story: MCP `outputSchema` / `structuredContent`

JClaw drops both halves of the MCP structured-output feature (spec revision 2025-06-18):

- `McpToolDef` parses `inputSchema` only (`McpToolDef.java:27`); a server-declared
  `outputSchema` is discarded at parse time.
- `CallToolResult.fromResultObject` flattens the `content` array to a string and never
  reads `structuredContent` (`CallToolResult.java:27-38`) — so an MCP tool's structured
  result is lost even for UI rendering, which is the one thing `structuredJson` is for.

This is a different proposition from §2's. The schema is *given to us* by the server, so
there is no per-action declaration cost. The tool is not ours, so a test cannot cover it
and a runtime check has an argument. And both reference harnesses already do the
carrying half.

**Scope it as plumbing, not validation:** parse `outputSchema` onto `McpToolDef`, carry
`structuredContent` through `CallToolResult` into `ToolResult.structuredJson`. Decide
whether to validate afterwards, gated on JCLAW-836's `tool_verify_failed` metric showing
MCP results actually misbehave. Sized around 3 points.

## 8. If coverage is wanted anyway: the proportionate version

Should someone want AC1's *intent* — "a tool cannot silently break its output contract"
— the honest form is a single unit test in the JCLAW-827 mould: invoke each
JSON-returning tool's success path, assert the envelope carries its declared keys.
`exec` already has the constants to assert against. One test class, zero request-path
cost, catches drift at merge time.

This is offered as the cheap alternative, not as a recommendation. §4's argument is
that the defect class is rare enough and self-announcing enough — a broken envelope
breaks a tool's own tests first — that even this may not earn its keep.

## 9. Answers, in one line each

1. **Stable shapes?** Yes, in 9 tools — but the contract is `success-envelope | prose-error`, per action, with load-bearing optional keys. Not schema-shaped.
2. **Structure helps the model?** Unverified, and the provider sees a string either way. Do not build on it. JCLAW-875 could now test it.
3. **What does a violation mean?** Our bug. Belongs in a test — the pattern JCLAW-827 already established here.
4. **Cost?** Higher than advertised: ~63 declarations, plus two subset extensions that weaken the eval validator's strictness.
5. **Comparable harnesses?** OpenClaw and Hermes both declined. Both carry `structuredContent`; neither validates it.

**Outcome:** close AC1. Optionally raise the MCP structured-output plumbing story (§7).
