# AGENTS.md

This file is the canonical, harness-agnostic guide for AI coding agents working in this repository — read by Claude Code, Codex, Cursor, and other coding harnesses. (`CLAUDE.md` points here.)

## Project Overview

JClaw is an AI-powered automation platform built on **Play Framework 1.x** (Java) with a **Nuxt 4** (Vue 3 + TypeScript) SPA frontend. It combines OpenClaw agent orchestration and JavaClaw job scheduling into a single Java-first platform.

**Status**: pre-v1 (beta), work in progress.

## First-time setup

After every fresh clone (including `rm -rf` + re-clone cycles), run:

```bash
./jclaw.sh setup
```

On Windows PowerShell, run `bash ./jclaw.sh setup` (Git for Windows) or run the
clone inside WSL2. See `docs/windows.md` for the platform support matrix.

This wires git hooks (`.githooks/`), installs frontend dependencies (so the pre-commit hook's `lint-staged` is available), and verifies both `origin` (Bitbucket) and `github` remotes are configured. Idempotent — safe to re-run any time.

Why it's needed: `.git/config` lives inside `.git/` which git refuses to track, and `frontend/node_modules/` is gitignored. So `core.hooksPath`, the `github` remote, and `lint-staged` itself don't survive a fresh clone. Without the setup, hooks silently don't fire and `/deploy` fails on the github push.

## Development Commands

### Backend (Play 1.x)
```bash
play run                  # Start dev server on :9000
play autotest             # Run all tests (unit + functional)
play dist                 # Build production distribution
```

### Frontend (Nuxt 4)
```bash
cd frontend
pnpm install              # Install dependencies
pnpm dev                  # Dev server on :3000
pnpm build                # Production build
pnpm preview              # Preview production build

pnpm lint                 # ESLint (logic + Vue + TS + a11y + stylistic)
pnpm format               # eslint --fix — stylistic auto-fix across the tree
pnpm stylelint            # Stylelint on .css + <style> blocks
pnpm stylelint:fix        # stylelint --fix
pnpm typecheck            # vue-tsc --noEmit via nuxi typecheck
pnpm audit                # pnpm audit --prod --audit-level=moderate
pnpm test                 # Vitest (unit)
```

### Evals
```bash
./jclaw.sh evals                                       # validate the eval dataset
./jclaw.sh evals --responses run.json --out rep.json   # score a recorded agent run
./jclaw.sh evals --capture run.json --agent __evaltest__ --suite <id>   # drive a live agent
```

Agent-behaviour datasets live in `evals/suites/` (`<id>.json`);
`evals/README.md` is the format contract. Validating and scoring are offline —
no backend, no model call, no DB — and `play autotest` validates the dataset via
`EvalSuiteConformanceTest`, so a malformed suite fails the build.

Suites are measuring sticks, so edit them in place and let git hold the history:
every run records a content fingerprint (`tool-selection@6e7927aeefe4`), and
comparing two runs whose fingerprints differ says so before printing anything
else. The fingerprint covers case inputs and checks, not `rubric`/`description`,
so clarifying prose never invalidates a baseline. JCLAW-883 replaced the older
`<id>.v<N>.json` convention, which nothing enforced — an in-place edit under an
unchanged version was undetectable.

`--capture` is the exception: it drives real agent turns, so it needs the
backend running and it spends model calls. It POSTs to `/api/evals/capture`
behind the same loopback + `X-Loadtest-Auth` gate as the loadtest endpoints, and
`--agent` is required rather than defaulted. Capture turns leave no
conversation, no history and no memories behind, and are never recorded into the
Chat Performance histograms — see `evals/README.md` for why each of those holds.

What capture does **not** isolate is tool side effects: tools execute for real,
so a suite case that provokes a `task_manager` call creates an actual scheduled
task. Run sweeps against `__evaltest__` — the eval sibling of `__loadtest__`,
auto-provisioned on first capture, for which **every tool is opt-in**. Each suite
declares its own `requiredTools` and capture grants exactly those before the
sweep, revoking the rest, so a run is reproducible from the repo rather than from
whatever was last clicked in the agent editor. Only `__evaltest__` is calibrated
this way — an agent you configured yourself is never rewritten. Deleting the eval
agent cleans up everything a sweep created.

Related, and not eval-specific: per-agent tool config used to be schema-only —
it hid a tool from the model but did not stop it running, so a model that guessed
a real tool name bypassed the operator's configuration. JCLAW-883 added the
execution guard in `ToolRegistry.execute`/`executeRich` for native tools; MCP
tools already gated through `AgentSkillAllowedTool`.

### Running Both Together
Start the Play backend (`play run`) and the Nuxt frontend (`cd frontend && pnpm dev`) in separate terminals. The frontend proxies `/api/**` requests to `localhost:9000`.

## Git Hooks

Checked-in hooks live in `.githooks/`. They're wired up automatically by `./jclaw.sh setup` (see [First-time setup](#first-time-setup) above). The underlying command, for reference or manual use:

```bash
git config core.hooksPath .githooks
```

Three hooks. The first two run on the commit/push path, layered by speed:

- **`pre-commit`** — runs `lint-staged` on staged frontend files only (ESLint + Stylelint `--fix`). Target: < 5 s typical. Auto-fixes formatting and re-stages; blocks the commit if a non-fixable rule violates. Short-circuits instantly when no `frontend/**` file is staged, so backend-only commits pay zero cost. Requires `cd frontend && pnpm install` first; before that, the hook fails open with a note.
- **`pre-push`** — runs the full backend + frontend test suite. Caches per-HEAD so the two-remote deploy flow (origin + github) only pays the ~30 s cost once.
- **`post-checkout`** — fires `./jclaw.sh init-worktree` when `git worktree add` (or a fresh clone) creates a working tree, seeding its `certs/.env` secret and a deterministic `PLAY_TEST_PORT` so parallel `play autotest` runs across worktrees don't collide. No-op on routine branch switches.

Bypass for a single commit / push (use sparingly):

```bash
git commit --no-verify -m "…"          # skip pre-commit
JCLAW_SKIP_TESTS=1 git push …          # skip pre-push tests
```

Disable per-worktree (overrides the checked-in hook for this clone only):

```bash
git config --unset core.hooksPath      # opts out entirely
# or, to keep pre-push but disable pre-commit:
echo '#!/bin/sh' > .git/hooks/pre-commit && chmod +x .git/hooks/pre-commit
git config core.hooksPath .git/hooks
```

## Commit and Push Workflow

**Stop at the local commit. Do not push.** When you finish a unit of work:

1. Stage the files that belong to the change.
2. Create a local commit. The `pre-commit` hook runs here; fix every issue it surfaces and re-commit until the commit succeeds. Never use `--no-verify` to bypass the hook. If a rule is wrong, fix the rule; if a formatting auto-fix modifies files, re-stage and try again.
3. Stop. Report the commit hash and wait.

**Pushing is the user's job via `/deploy`.** The `/deploy` slash command is the only automation that bumps `application.version`, creates the release commit, and pushes to both remotes (`origin` + `github`). Do not `git push` as part of ordinary coding, and do not combine a final commit with a push in one step. A prior `/deploy` authorises only that release — the next one requires a new explicit invocation.

Why this matters: every push to `main` triggers the `pre-push` hook's full backend + frontend suite, occupies the two-remote deploy flow, and mutates shared state. Keeping push behind `/deploy` makes releases a deliberate act rather than a side-effect of finishing a task.

## Architecture

### Backend
- **Play 1.x** conventions: controllers are static methods in `app/controllers/`, models in `app/models/`, views (Groovy templates) in `app/views/`
- Routes defined in `conf/routes` — uses Play's `{controller}.{action}` catch-all pattern
- Configuration in `conf/application.conf` — supports environment prefixes (`%prod.`, `%test.`)
- Dependencies managed via `build.gradle.kts` using the `org.playframework.play1` plugin from the Play fork at `PLAY1_HOME` (default `/opt/play1`). `settings.gradle.kts` resolves that plugin from the fork's flat `framework/gradle-plugin-repo` Maven repo — **not** `includeBuild(...)`, which builds the plugin from source and needs write access into the fork, so it fails on read-only installs. The plugin version is read from the installed fork's `framework/src/play/version`, and `build.gradle.kts` cross-checks that against `.play-version` (plus a pinned range), failing the build on drift. To bump the fork: edit `.play-version` **and** check out the matching version under `PLAY1_HOME`.
  - Consequence: `PLAY1_HOME` is a separate root build with its own Gradle wrapper, so builds there start their own daemon. `./gradlew --stop` in this repo does not stop it — run the wrapper from `PLAY1_HOME` too.
- Tests in `test/` — JUnit 6 (Jupiter 6.1.2, bundled by the play1 fork in `framework/lib`), extending Play's `UnitTest` or `FunctionalTest`
- Test mode uses H2 in-memory database (`%test.db.url` in application.conf)

### Outbound HTTP — OkHttp 5
JClaw uses **OkHttp 5.x** (with `okhttp-sse` for streaming) as its single outbound HTTP stack. The JDK `java.net.http.HttpClient` is no longer used in `app/` (only mentioned in Javadoc comparisons). The migration was JCLAW-185 through JCLAW-188; the rationale stack:

- **No h2c upgrade on cleartext** — OkHttp doesn't send `Upgrade: h2c` on plain HTTP, so the LM Studio Express upgrade-event hang the JDK client had to dodge with a routing rule is structurally absent. One client config, no per-provider HTTP-version pin needed.
- **Virtual-thread-clean** — Okio 3 has no synchronized blocks in its read path (verified by zero `monitorenter` in disassembled `RealBufferedSource.class` plus zero `Thread is pinned` events under `-Djdk.tracePinnedThreads=full` during a 50-stream c=10 sweep). Pointing OkHttp's `Dispatcher` at `Executors.newVirtualThreadPerTaskExecutor()` gives blocking socket reads the same unmount-during-block behavior the JDK client gets via `AsynchronousSocketChannel`.
- **Ergonomic SSE** — `okhttp-sse`'s `EventSourceListener` model is more direct than parsing `data:` lines off a `BufferedReader` from the JDK client. Streaming chat ships through a single `OkHttpLlmHttpDriver.streamSse` call.
- **Performance parity** — cloud median 0.94× JDK avg across 7 c=10 runs (kimi-k2.5, qwen3.5, gemini-3-flash-preview); local with `OLLAMA_NUM_PARALLEL=8` at 0.85×. AC3 ±5% met at the median.

All HTTP-client provisioning lives in `app/utils/HttpFactories.java` — a single class that exposes named factory methods (`llmStreaming()`, `llmSingleShot()`, `general()`) so call sites declare *intent* rather than reach into named static fields. Internally it shares two connection pools (LLM/64-slot, general/32-slot) and two dispatchers (LLM uses a virtual-thread executor, general uses OkHttp's default cached pool — request volume on the non-LLM path doesn't justify VT scheduling). The Telegram SDK and `WebFetchTool`/`SsrfGuard` build their own clients with stack-specific tuning that doesn't fit any of the three `HttpFactories` tiers (the SDK has its own internal usage; `SsrfGuard` plugs in a per-request DNS allow-list against tool-fetch SSRF).

### Frontend
- Nuxt 4 SPA in `frontend/` with Tailwind CSS v4
- API proxy: dev requests to `/api/*` are forwarded to the Play backend via Nitro devProxy (see `frontend/nuxt.config.ts`)
- Backend calls use Nuxt's auto-imported `useFetch` / `$fetch` directly; `frontend/composables/` adds `useApiParsed` (schema-validated reads, JCLAW-287) and `useApiMutation` (POST/PUT/DELETE) as consistent wrappers
- Package manager: **pnpm**, version pinned in `frontend/package.json`'s `packageManager` field with a `+sha512-...` integrity hash. Three layers keep the hash present and verified:
  1. `./jclaw.sh setup` runs `corepack use pnpm@<version>` to add the hash if missing (idempotent — no-op once the hash is in place).
  2. The `.githooks/pre-commit` guard refuses to commit a `frontend/package.json` whose `packageManager` value lacks a hash, so a hand-edit that drops it can't slip through.
  3. `./jclaw.sh start` (both dev and prod) runs `corepack install` before any pnpm step — read-only validate, never mutates `package.json`. Downloads the pinned version on first use, verifies the tarball against the hash on every subsequent run. Hard-fails the start on missing hash or mismatch — never bypass.

  To bump the pin: `cd frontend && corepack use pnpm@<version>` (writes both the version and a fresh hash), then commit `frontend/package.json`.

### API Contract
Backend exposes JSON endpoints under `/api/` (e.g., `ApiController.status` at `GET /api/status`). The frontend consumes these through the proxy — no CORS configuration needed.

## Prerequisites

- JDK 25+ (Zulu recommended)
- `play` command in PATH (custom fork: github.com/tsukhani/play1)
- Node.js 22.19+ (the dev container ships 24; Nuxt's `engines` rejects Node 20)
- pnpm


## graphify

Optional codebase knowledge graph, installed per-machine with `uv tool install graphifyy` (the CLI is `graphify`). Build it with `/graphify .`; `graphify-out/` is gitignored, so the graph is local to each clone and never committed.

This project has a knowledge graph at graphify-out/ with god nodes, community structure, and cross-file relationships.

When the user types `/graphify`, use the installed graphify skill or instructions before doing anything else.

Rules:
- For codebase questions, first run `graphify query "<question>"` when graphify-out/graph.json exists. Use `graphify path "<A>" "<B>"` for relationships and `graphify explain "<concept>"` for focused concepts. These return a scoped subgraph, usually much smaller than GRAPH_REPORT.md or raw grep output.
- Dirty graphify-out/ files are expected after hooks or incremental updates; dirty graph files are not a reason to skip graphify. Only skip graphify if the task is about stale or incorrect graph output, or the user explicitly says not to use it.
- If graphify-out/wiki/index.md exists, use it for broad navigation instead of raw source browsing.
- Read graphify-out/GRAPH_REPORT.md only for broad architecture review or when query/path/explain do not surface enough context.
- After modifying code, run `graphify update .` to keep the graph current (AST-only, no API cost).


## Behavioral Guidelines

Behavioral guidelines to reduce common LLM coding mistakes. Merge with project-specific instructions as needed.

**Tradeoff:** These guidelines bias toward caution over speed. For trivial tasks, use judgment.

### 1. Communicate Concisely

**Direct answers. Digestible nuggets. Progressive exchange.**

The user is tracking the work as it happens, not reading a report at the end. Make it easy to follow.

- Lead with the answer or the action. Skip the preamble. The user can ask for more if they want it.
- Break multi-step work into small confirmable units rather than dumping a full multi-stage plan up front. Each step's outcome may change the next; don't pre-commit to plans the user hasn't seen the inputs for.
- After each meaningful step, state in one or two sentences: what just happened, what's next, what (if anything) is blocked. So the user can track progress without re-reading prior messages.
- Match response length to the size of the question. A one-line ask gets a one-line answer. A multi-step refactor gets the diff summary plus a "next step" hint, not a five-paragraph essay.
- Keep insights and tangents brief. If a longer explanation might help, offer it ("want me to expand?") rather than emitting it by default.

The signal you're getting this right: the user can scan your message in seconds and know exactly what state things are in.

### 2. Think Before Coding

**Don't assume. Don't hide confusion. Surface tradeoffs.**

Before implementing:
- State your assumptions explicitly. If uncertain, ask.
- If multiple interpretations exist, present them - don't pick silently.
- If a simpler approach exists, say so. Push back when warranted.
- If something is unclear, stop. Name what's confusing. Ask.

### 3. Simplicity First

**Minimum code that solves the problem. Nothing speculative.**

- No features beyond what was asked.
- No abstractions for single-use code.
- No "flexibility" or "configurability" that wasn't requested.
- No error handling for impossible scenarios.
- If you write 200 lines and it could be 50, rewrite it.
- If you need a paragraph-long comment to justify why a workaround is OK, the code is wrong — fix the code.

Ask yourself: "Would a senior engineer say this is overcomplicated?" If yes, simplify.

### 4. Surgical Changes

**Touch only what you must. Clean up only your own mess.**

When editing existing code:
- Don't "improve" adjacent code, comments, or formatting.
- Don't refactor things that aren't broken.
- Match existing style, even if you'd do it differently.
- If you notice unrelated dead code, mention it - don't delete it.

When your changes create orphans:
- Remove imports/variables/functions that YOUR changes made unused.
- Don't remove pre-existing dead code unless asked.

The test: Every changed line should trace directly to the user's request.

### 5. Goal-Driven Execution

**Define success criteria. Loop until verified.**

Transform tasks into verifiable goals:
- "Add validation" → "Write tests for invalid inputs, then make them pass"
- "Fix the bug" → "Write a test that reproduces it, then make it pass"
- "Refactor X" → "Ensure tests pass before and after"

For multi-step tasks, state a brief plan:
```
1. [Step] → verify: [check]
2. [Step] → verify: [check]
3. [Step] → verify: [check]
```

Strong success criteria let you loop independently. Weak criteria ("make it work") require constant clarification.

### 6. Know What Is Verified

**Separate what you ran from what you inferred. Say which is which.**

- **Claims about external systems need the external system.** How Slack renders an audio attachment, what a provider's API accepts, whether a client plays a codec — these are facts about someone else's software, and reading docs or reasoning from adjacent behaviour is inference, not verification. Infer freely; just label it, and don't let an inference reach a commit message or a ticket as fact.
- **A green result you did not scope is not evidence.** A quality gate can pass because nothing was measured; a test can pass because its fixture avoids the interesting case. Before reporting a pass, check that the thing you care about was actually exercised.
- **Validate a measurement harness against a known-zero case** before trusting a number it produces. Time a no-op; assert on an empty input. A harness that reports plausible nonsense is worse than no measurement, because it gets believed.
- **A flake is a defect until proven environmental — and "environmental" means naming the mechanism.** "Re-ran and it passed" is not a diagnosis. There are known interference cases here (a live app on the test port, parallel worktrees); those are identifiable by name, and anything else is a real failure.

### 7. Reach For What Exists

**Instrumentation, helpers, and prior verdicts already in the repo beat improvising.**

- Latency questions have `LatencyStats` / `LatencyTrace`, `/api/metrics`, the `./jclaw.sh loadtest` harness, and JProfiler. Prefer them over a hand-rolled stopwatch.
- Before deciding a static-analysis finding, check how the same rule was already resolved elsewhere in this project. A verdict that contradicts an established one either needs to change both places or is wrong.
- Match the existing construction for test fixtures and helpers (`AgentService.create`, `commitInFreshTx`, the probe `setForTest` seams) rather than building a parallel one.

### 8. Comments Carry Reasoning, Not Narration — Tersely

**Never write a comment that restates the code. Default to no comment; when one is warranted, default to one line.**

Redundant commenting is the most common defect in agent-written code, and the pull toward it is strong enough to survive a general instruction to stop. So this section is checks and examples rather than adjectives — apply it mechanically, not by feel.

**The delete test — run it on every comment you write.** Delete the comment and re-read the code. If the code still conveys everything the comment did, it stays deleted. A comment survives only by carrying what the code cannot: a reason, a constraint, a measurement, or a bug.

**Never write these:**

```java
// Loop through the discovered printers        ← restates the next line
for (var p : printers) { ... }

// Added image types (JCLAW-911)               ← changelog; belongs in the commit message
// Now also deletes the transcode cache        ← narrates your change, not the code
// This is important because...                ← if it needs a paragraph, fix the code (§3)
// TODO: could be faster                       ← aspirational; open a ticket or delete it
```

**Write these:**

```java
// Canon rejects text/plain outright — negotiate the format before sending.
// A4 job against a Legal tray reports as spool-area-full, not as a size error.
// JIPP is Kotlin: getStatus() is @NotNull, so a null guard here is dead code.
```

**A comment earns its place only by recording one of:**

- why the code is this way and not the obvious way — name the ticket, benchmark, or bug that decided it
- a non-obvious invariant or precondition a caller has to hold
- the specific failure a guard prevents
- an external contract the code cannot state itself: wire format, provider quirk, library nullability

**Length is one line.** A second line requires a reader who would otherwise get it wrong. A fifth means the code is unclear — fix the code (§3), don't explain it. Match the density around you: a new entry in a list of one-line comments gets one line. In `SettingsUnmanagedBanner.vue`'s prefix list, `playwright.` and `jtokkit.` run several lines because one is a deliberately-retained retired namespace and the other is written autonomously by a job — both genuinely surprising. Every ordinary entry beside them is a single line, and a new one that isn't will be sent back.

**Javadoc is held to the same bar.** Stating a public contract — parameters, return, thrown conditions, threading — earns its length. Prose rationale *inside* a Javadoc block does not: it faces the same delete test as any other comment, and a private helper usually needs no Javadoc at all. A ten-line block explaining why a two-line method exists is the same defect as a ten-line inline comment.

**Provenance for a *change* belongs in the commit message, not the code.** "Why this must stay" is a comment; "why I added this" is history. Explaining the bug you just fixed, in the code that fixes it, duplicates the commit body and ages into noise within one refactor.

**During refactors, comments move with the logic they explain.** A comment recording *why* a check exists is frequently the only surviving record of a bug that was actually hit, and deleting it as clutter is how the bug comes back.

### 9. Commit Messages Are Public

`/deploy` pushes to **both** remotes, and the GitHub mirror (`github.com/tsukhani/jclaw`) is **public**. Commit bodies, tags and release notes are published artifacts, not internal notes.

Write them factually: what changed, why, what was verified. That is also what makes them useful internally — the constraint costs nothing. Keep genuinely internal material (credentials, customer specifics, unreleased commercial plans) out of them entirely.

---

**These guidelines are working if:** fewer unnecessary changes in diffs, fewer rewrites due to overcomplication, comments that stay one line, and clarifying questions come before implementation rather than after mistakes.
