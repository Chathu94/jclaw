# Evals

Versioned datasets that measure agent behaviour, plus the offline runner that
scores against them (JCLAW-875, epic JCLAW-833).

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
```

Everything here is **offline**: no backend, no database, no model call. Running
evals costs nothing on the serving path, which is the epic's efficiency NFR
applied to its own measurement tooling.

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
    }
  }
}
```

A case with no recorded response fails with `no response recorded`, so a partial
sweep reads as failing cases rather than a suspiciously short suite.

An agent-backed responder — one that drives a real JClaw agent instead of
replaying a recording — plugs into `EvalRunner.Responder` and lands with the
work that owns invoking a model (JCLAW-836).

## Changing a suite

Adding a case is safe: a case absent from the baseline is not a regression.

Changing or removing a case is not — it redefines what the pass rate means.
Copy the file to `<id>.v<N+1>.json`, bump the `version` in the body, and leave
the old one in place until nothing compares against it.
