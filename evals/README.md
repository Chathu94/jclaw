# Evals

Datasets that measure agent behaviour, the offline runner that scores against
them (JCLAW-875), and the capture path that drives a live agent to produce a run
worth scoring (JCLAW-883). Epic JCLAW-833.

The point of this directory is to be a **ruler**. Every story in the agentic
harness epic — planning, critic, best-of-N, retries — is a claim about quality,
and claims need something to be measured against that does not move while the
thing under test changes. Suites live in git so the history is there, and every
run records a fingerprint of the suite content that scored it — so when the ruler
does move, nothing compares across the change without saying so.

```
evals/
  schema/eval-suite.schema.json   the format contract (what authors read)
  suites/<id>.json                the datasets (what the runner reads)
  reports/                        run output — gitignored
```

## Running it

```bash
./jclaw.sh evals                                              # validate the dataset
./jclaw.sh evals --responses run.json --out reports/now.json  # score a recorded run
./jclaw.sh evals --responses run.json --baseline reports/last.json   # catch regressions

# drive a live agent and write the run the scorer consumes
./jclaw.sh evals --capture run.json --agent __evaltest__ --suite tool-selection
```

Validating and scoring are **offline**: no backend, no database, no model call.
Running them costs nothing on the serving path, which is the epic's efficiency
NFR applied to its own measurement tooling. Capture is the one command that
spends model calls, and it is opt-in by construction — see below.

`play autotest` runs the same validation via `EvalSuiteConformanceTest`, so a
malformed suite fails the build rather than the next eval run.

## Dataset format

One suite per file, named `<id>.json`. The filename is the suite's identity and
nothing else; the loader refuses a file whose body id disagrees with its name.

```json
{
  "id": "tool-selection",
  "description": "Does the agent reach for the right tool?",
  "cases": [
    {
      "id": "wall-clock-datetime-optional",
      "input": "What time is it?",
      "rubric": "The harness already injects the current time, so the clock tool is allowed but not required.",
      "checks": [
        { "kind": "tools_called_within", "args": ["datetime"] },
        { "kind": "max_llm_calls", "limit": 2 }
      ]
    }
  ]
}
```

`rubric` is required: a case that cannot say in one line what it is testing
does not belong in the suite.

### Check kinds

Every kind is decidable from the response alone — no judge model.

| kind | payload | passes when |
| --- | --- | --- |
| `contains_all` | `args`: substrings | every substring appears (case-insensitive) |
| `not_contains_any` | `args`: substrings | none of them appears |
| `matches` | `args`: one regex | the regex finds a match |
| `json_schema` | `schema` | the response parses as JSON and validates |
| `tools_called_exactly` | `args`: every tool name allowed | the calls made equal that list as a multiset — no extras, no repeats. `[]` means no tool at all |
| `tools_called_within` | `args`: the tools permitted | every call is in the list, but none is required — the "or" form. Extras and repeats still fail |
| `max_llm_calls` | `limit` | the turn spent at most `limit` model calls |

`json_schema` implements a subset: `type`, `properties`, `required`, `items`,
`enum`, `additionalProperties`, `description`. JClaw carries no JSON Schema
validator dependency, and a hand-rolled subset that ignored the keywords it does
not understand would report passes it never checked — so the loader **rejects**
any other keyword instead. Add the keyword to `EvalScorer` if a suite needs it.

The response is parsed verbatim: no fence-stripping, no brace-hunting. Prose
around a JSON body is the defect a structured-output case exists to catch.

`max_llm_calls` is the epic's efficiency NFR written as an assertion. It scores
against the per-turn `llm_call_count` (JCLAW-882), so a story that buys quality
with an extra model call has to declare the purchase.

### Why tool checks are allowlists

There used to be a `tool_called` / `tool_not_called` pair. JCLAW-883 removed
them: they were a **denylist**, which only catches rogue behaviour someone
predicted, and is therefore structurally unable to express "only what was
necessary".

`arithmetic-needs-no-tool` is the worked example. Its rubric says *"any tool call
here is pure overhead"*, but the denylist version of it could only forbid the two
tools the author happened to name — a stray `task_manager` or `generate_image`
call passed while breaking the case's stated intent. The same hole is why the
first live sweep's errant task creation was invisible to the suite: the case that
caused it asserted one `tool_called` and nothing else, so the agent could have
called that tool five times plus `exec` and still scored a pass. It now asserts
`tools_called_exactly: []`, which is what the rubric said all along.

`tools_called_exactly` is the **allowlist** counterpart, and it collapses three
concerns into one assertion:

- **Wrong tool** — anything outside the list is `unexpected`.
- **Superfluous repeat** — the comparison is a multiset, so calling a
  once-listed tool twice fails.
- **Rogue side effects** — an agent can only touch the world through tools, so
  an unsanctioned effect shows up as an unsanctioned call.

That last one is the important one and it has a boundary worth stating: this
detects the *call*, not the *effect*. A tool the case legitimately allows can
still do more than intended — `task_manager` called once, with wrong content.
Catching that needs assertions over resulting state, which nothing here does.

Order is not compared. Two tools the agent could equally have called in either
order are not a behaviour difference worth failing a suite over.

### When a tool is optional: `tools_called_within`

Sometimes two behaviours are both correct. Asked "what time is it?", an agent that
calls `datetime` is right, and so is one that answers straight from the timestamp
`CurrentTimeInjector` already stamped onto the user message — that injection
exists precisely to save the round-trip, so demanding the tool would reward a
wasted call.

`tools_called_within: ["datetime"]` accepts both. Every call must be in the
allowance, but none is required. Extras and repeats still fail, so the case keeps
its teeth: a web search fails it, and so does calling the clock twice.

Reach for `tools_called_exactly` when the tool genuinely is mandatory —
`date-difference-uses-datetime` in `tool-selection` is the contrast, because
the injected clock answers "now" and nothing else, so a span between two dates
has to come from the tool.

Prevention and measurement are different jobs, and the two mechanisms compose:
`__evaltest__`'s opt-in tool surface means an ungranted tool *cannot* fire, while
`tools_called_exactly` catches a granted tool used when it should not have been.

### What is deliberately missing

Semantic verification ("does this answer the question?") and free-form rubric
grading need a judge model. They are not here — that is JCLAW-836's critic,
which this story was descoped away from. The grounding suite approximates
hallucination detection deterministically instead: plant a fact and a
distractor, require the first and forbid the second. It is blunter than a judge
and it costs zero calls.

## Recording a run

The runner scores whatever produced the responses; the file is the contract.

```json
{
  "suite": "grounding",
  "fingerprint": "bf5ff8047a1d",
  "responses": {
    "carries-the-figure-from-source": {
      "output": "The transfer lasted 209 days.",
      "toolsCalled": ["web_fetch"],
      "toolsAttempted": ["web_fetch", "httpFetch"],
      "llmCalls": 2
    },
    "distractor-is-not-repeated": {
      "error": "provider unreachable"
    }
  }
}
```

`error` marks a case the agent never answered. It is optional, and older
recordings without it read as answered.

### `toolsCalled` vs `toolsAttempted`

- **`toolsCalled`** — names where a tool actually ran. **The checks score against
  this**, because it is what produced side effects and did the work.
- **`toolsAttempted`** — every name the model emitted, including ones it invented
  and ones the agent was not granted.

They were one field until JCLAW-883, and it recorded attempts while being named
for calls. A live sweep made the cost concrete: a turn that executed *nothing*
recorded `toolsCalled: ["httpFetch", "http_fetch", "webSearch"]`, because the
model guessed three names that do not exist. Scored against that, the agent
looked like it had used three tools.

The difference between the lists is itself worth reading. An agent guessing tool
names is spending model calls to accomplish nothing — in that same sweep it
burned four calls to produce an apology. `max_llm_calls` catches the cost;
`toolsAttempted` says why.

Older recordings carry only `toolsCalled`, and at that time it meant *attempted*.
The loader defaults `toolsAttempted` to it rather than to empty, so those files
keep meaning what they recorded.

## Errored is not failed

"The agent answered wrongly" and "the agent never answered" are different
findings, and the report keeps them apart:

- A **failed** case was measured. It counts against the pass rate and, if it
  passed in the baseline, it is a regression.
- An **errored** case was not measured. It is excluded from the pass rate's
  denominator and is never a regression, because nothing about the agent's
  behaviour was observed. The reason is printed next to the case, and the totals
  line always states how many cases errored — a rate computed over four of ten
  cases would otherwise read like a rate over ten.

Both fail the run. A sweep that could not run is not a green sweep.

A case with no recorded response at all errors with `no response recorded`, so a
partial sweep reads as unmeasured cases rather than a suspiciously short suite.

## Capturing a run from a live agent

```bash
./jclaw.sh evals --capture run.json --agent __evaltest__ --suite tool-selection
```

This needs the backend running, because driving an agent needs what the offline
CLI deliberately does without: JPA, a configured provider, and the tool registry.
It POSTs to `/api/evals/capture`, gated the same way the loadtest endpoints are —
loopback origin plus `X-Loadtest-Auth` carrying the application secret.

Capture and scoring stay two steps on purpose: the recorded file is the boundary,
so a sweep can be scored now, re-scored later against a changed suite, or diffed
against a baseline, without paying for the model twice.

**`--agent` is required and never defaults.** Use `__evaltest__` (below), not an
agent you actually work with.

What a sweep does not leave behind: turns run through `AgentRunner.runForTask`,
which builds a transient conversation it never persists — so there are no history
rows to clean up, which is a stronger guarantee than creating throwaway
conversations and deleting them, since nothing survives a sweep that dies midway.
`MemoryAutoCapture` fires from the two chat entrypoints and not from this one, so
an eval cannot teach the agent about its own eval questions. The per-case
`LatencyTrace` exists only to count model calls and is never ended, so no eval
turn reaches `LatencyStats` — hundreds of them landing in the request-path
histograms would corrupt the baseline JCLAW-833 measures against.

### What it does leave behind: tool side effects

The isolation above covers the turn's bookkeeping. It does not cover what the
agent *does*. Tools execute for real. A case that induces a `task_manager` call
creates a real scheduled task; a case that induces a write tool writes.

This is not theoretical. The first live run of `tool-selection` against the
operator's `main` agent created a recurring task (`check-deploy-queue-weekday`,
cron `0 0 9 * * 1-5`) that had to be deleted by hand — the suite contains a case
designed to make the agent reach for the task manager, and it did.

So the agent you point capture at is a safety boundary, not just a label.

## The `__evaltest__` agent

`__evaltest__` is the agent sweeps are meant to run against — the eval sibling of
`__loadtest__`. Capture provisions it on first use, copying provider and model
from `main` as a starting point.

**Every tool is opt-in for it.** An ordinary agent starts with the full tool set
and the operator switches things off; `__evaltest__` starts with nothing and the
operator switches things *on*, in the agent editor, per suite. That inversion
lives in `ToolRegistry.computeDisabledTools` alongside the same rule that makes
`generate_image` and `generate_video` opt-in, and it holds for tools added to the
registry later — which seeding disabled rows at provisioning would not.

It is enforced at execution, not only in the schema. That distinction cost a
round: the first `__evaltest__` sweep was offered no tools, guessed `web_search`
anyway, and it *ran* — because per-agent tool config was consulted only when
building the tool list sent to the model. `ToolRegistry.execute` now refuses a
native tool the agent has not been granted (JCLAW-883). MCP tools keep their own
execution gate, the `AgentSkillAllowedTool` allowlist, and are not double-gated.

The practical consequence: a fresh `__evaltest__` fails every `tool_called` check
until you grant the tools that suite needs. That is the safe direction to be
wrong in, and the failure names the missing tool.

Calibrate it the same way you configure any agent — tools and MCP servers in the
agent editor, skills through the per-agent skill config. Unlike `__loadtest__` it
is deliberately **not** in `ApiAgentsController.isReservedName`: reserved rows are
hidden from every user-facing API, and being able to calibrate this one in the UI
is the entire point. It is a conventional name with a safe default, not a hidden
internal row.

To clean up everything a sweep created, delete the agent — `AgentDeletionCascade`
takes its tasks, runs, and messages with it.

`--concurrency` (default 4, max 16) bounds how many cases are in front of the
model at once. Unbounded fan-out would contradict the NFR the suite exists to
police, and a rate-limited provider's retries would score as agent failures.

## Changing a suite

**Edit it in place.** Git holds the history, and `git diff` shows exactly which
cases changed — which a new file beside the old one does not.

Adding a case is safe: a case absent from the baseline is not a regression.

Changing or removing a case redefines what the pass rate means, and that is what
the fingerprint is for. Every run records a short hash of the suite content that
scored it, printed beside the id as `tool-selection@6e7927aeefe4`. Compare two
runs whose fingerprints differ and the CLI says so before it prints anything
else, because case ids can match across two rulers while meaning different
things.

What the hash covers: each case's id and input, and each check's kind, args,
schema and limit, in order. What it deliberately ignores: `description` and
`rubric`. Both are prose explaining why a case exists and neither changes a
verdict, so sharpening a rubric must not invalidate a baseline — a fingerprint
that moved on cosmetic edits would produce warnings people learn to click past.

### Why not a version number in the filename

That is what this directory used to do (`<id>.v<N>.json`, the version repeated in
the body, a new file for every change). JCLAW-883 removed it, for three reasons
found by trying to use it:

- **It was never enforced.** Nothing detected an edit made in place under an
  unchanged version, so the scheme protected against the author who remembered to
  bump and was blind to the one who did not — which is the failure that actually
  happens. The fingerprint is computed, not asserted.
- **It made the documented workflow impossible.** The loader deduplicated on id
  alone, so publishing the `v2` this README told you to publish was rejected as a
  duplicate. The first person to follow the instructions was the one who found out.
- **Git already did the versioning half.** History, diffs, and recovering a
  deleted suite were all available; the filename convention added duplication,
  highest-version resolution, and files that accumulate with no defined end.
