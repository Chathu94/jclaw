# Evals

Versioned datasets that measure agent behaviour, the offline runner that scores
against them (JCLAW-875), and the capture path that drives a live agent to
produce a run worth scoring (JCLAW-883). Epic JCLAW-833.

The point of this directory is to be a **ruler**. Every story in the agentic
harness epic — planning, critic, best-of-N, retries — is a claim about quality,
and claims need something to be measured against that does not move while the
thing under test changes. That is why suites are versioned in git and why a
published suite is never edited in place.

```
evals/
  schema/eval-suite.schema.json   the format contract (what authors read)
  suites/<id>.v<N>.json           the datasets (what the runner reads)
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

One suite per file, named `<id>.v<version>.json`, with the version repeated in
the body. The filename is what keeps two versions of a suite side by side; the
loader refuses a file where the two disagree.

```json
{
  "id": "tool-selection",
  "version": 1,
  "description": "Does the agent reach for the right tool?",
  "cases": [
    {
      "id": "wall-clock-uses-datetime",
      "input": "What time is it?",
      "rubric": "The clock is a local tool, not a web fact.",
      "checks": [
        { "kind": "tool_called", "args": ["datetime"] },
        { "kind": "tool_not_called", "args": ["web_search"] },
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
| `tool_called` | `args`: one tool name | the agent called that tool |
| `tool_not_called` | `args`: one tool name | the agent did not call it |
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
  "version": 1,
  "responses": {
    "carries-the-figure-from-source": {
      "output": "The transfer lasted 209 days.",
      "toolsCalled": [],
      "llmCalls": 1
    },
    "distractor-is-not-repeated": {
      "error": "provider unreachable"
    }
  }
}
```

`error` marks a case the agent never answered. It is optional, and older
recordings without it read as answered.

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

This is not theoretical. The first live run of `tool-selection.v1` against the
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

Adding a case is safe: a case absent from the baseline is not a regression.

Changing or removing a case is not — it redefines what the pass rate means.
Copy the file to `<id>.v<N+1>.json`, bump the `version` in the body, and leave
the old one in place until nothing compares against it.
