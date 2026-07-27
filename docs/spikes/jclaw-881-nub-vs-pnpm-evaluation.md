# JCLAW-881 — Nub as a pnpm replacement (spike)

**Date**: 2026-07-27 · **Verdict: NO for package management and installation. Also NO for `nub run` — benchmarked against this repo, it changes test outcomes. `nubx` is sound but the win is ~0.24 s per invocation.**

The blocker is not that Nub is bad. Its pnpm compatibility surface is genuinely
good, and it clears the one gap that would have ended the evaluation outright.
The blocker is that pnpm is not a bottleneck in this repo, and replacing it
would forfeit a supply-chain guard the project deliberately built.

## What Nub is

[nubjs.com](https://nubjs.com/) — an all-in-one Node.js toolkit in a single Rust
binary that "augments Node.js instead of trying to replace it": a TypeScript/JSX
file runner, a script runner (`nub run`), a CLI runner (`nubx`), a Node version
manager, and a pnpm-compatible package manager. Recently launched (~3.3k GitHub
stars at time of writing).

## What clears

Checked against this repo's actual configuration, not against the general case.

| JClaw requirement | Nub | Note |
|---|---|---|
| `lockfileVersion: '9.0'` | ✅ | Nub reads v9 only; v6/v5.4 are rejected outright. We are on 9.0 |
| `pnpm install --frozen-lockfile` (Jenkinsfile:57) | ✅ | Plus `nub ci` as a frozen-install alias |
| `frontend/pnpm-workspace.yaml` | ✅ | Supported, including `workspace:` and `catalog:` |
| `allowBuilds:` allowlist | ✅ | *"pnpm.onlyBuiltDependencies / pnpm.neverBuiltDependencies / pnpm.allowBuilds — feed Nub's lifecycle-script policy"* |
| `pnpm audit`, `dedupe` | ✅ | Listed supported |
| `node_modules` shape | ✅ | Default `isolated` linker matches pnpm's symlinked layout |

The `allowBuilds` result is the one that mattered most and is easy to get wrong
by assumption. Nub has its **own** deny-by-default build-script policy, so the
obvious guess is that it ignores pnpm's allowlist. It does not — it feeds from
it. Had that gone the other way, `esbuild`'s postinstall would not run and the
Nuxt toolchain would break on the first install.

## What blocks it

### 1. It would hollow out the corepack integrity guard

`AGENTS.md` documents a deliberate three-layer mechanism around the
`packageManager` sha512 pin:

1. `./jclaw.sh setup` runs `corepack use pnpm@<version>` to add the hash
2. `.githooks/pre-commit` refuses a `frontend/package.json` whose
   `packageManager` value lacks a hash
3. `./jclaw.sh start` runs `corepack install` — read-only validate, hard-fails
   on missing hash or mismatch, on **every** run

and `jclaw.sh` routes *every* pnpm invocation through `corepack pnpm` so the pin
is authoritative regardless of what is installed globally.

If Nub performs the installation, the hash-verified binary is no longer the
thing doing the installing, and the pin becomes decorative — three layers of
guard protecting a field nothing consumes. **Corepack integrity verification
does not appear anywhere in Nub's pnpm compatibility documentation.** Nub's own
install path is `curl -fsSL https://nubjs.com/install.sh | bash`, which is a
weaker trust model than a hash-verified corepack tarball.

This is the decisive objection. It is not a missing feature — it is that the
property the project engineered would silently stop holding.

### 2. `minimumReleaseAge` is not mentioned in Nub's docs at all

`frontend/pnpm-workspace.yaml` sets `minimumReleaseAge: 0` deliberately, with a
comment instructing that it not be reintroduced — frontend deps are gated on
whether they compile and pass tests, not on release age. A tool that does not
read the setting cannot honour the opt-out. Whether Nub applies a cooldown of
its own is unverified; either way the existing declaration would stop meaning
anything.

### 3. The compatibility claim is verified one major behind us

Nub's round-trip is documented as *"verified against pnpm 10.15.1"*. This repo
moved to **pnpm 11.17.0** in v0.17.5. Both `allowBuilds` and `minimumReleaseAge`
are pnpm 11 surfaces — so the verification does not cover the configuration that
actually matters here.

## The benefit, measured

Against real numbers from this repo rather than vendor benchmarks:

| Operation | Measured here | Nub's claim | Actual saving |
|---|---|---|---|
| `pnpm install --frozen-lockfile` | **164 ms** | ~2.5× | ~100 ms |
| Full lockfile regen (`rm -rf` + install) | **9.5 s** | ~5× | ~7.6 s, on an operation run only at renovate merges |
| Five-script frontend gate | — | `nub run` 24× | ~2 s, against a backend suite of ~**360 s** |

pnpm is not the bottleneck. The wins are milliseconds on operations that gate
nothing; the cost is a supply-chain guard and a deps-cooldown setting.

## Benchmarked: `nub run` / `nubx` against this repo

The follow-up proposed above — keep pnpm for installs, use Nub only to *run*
scripts — was benchmarked with Nub **v0.6.0** on Node v24.18.0, pnpm 11.17.0.
**It is rejected.** `nub run` is not a transparent substitute for `pnpm run`
here: it changes test outcomes.

### Correctness first — `nub run test` fails 5 files that pnpm passes

| runner | exit | result |
|---|---|---|
| `pnpm test` (control) | 0 | `Test Files 111 passed (111)` · 1197 tests |
| `nub run test` | **1** | `Test Files 5 failed \| 106 passed (111)` · 1192 passed |

Deterministic — the identical five files fail on repeated runs:
`chat.appcreator-prefill`, `chat.flows`, `chat.page`, `chat`,
`subagent-rendering`. All five die the same way, a Vue *"Unhandled error during
execution of render function"* on `modelAutocomplete.open.value` in `chat.vue`.

The likely mechanism: Nub transpiles TS/JSX itself through its Rust oxc addon,
so running vitest under `nub run` puts a different transpiler in the path than
the Vite/Nuxt pipeline the suite is written against. That is not a bug to report
so much as a statement that the runner is not neutral — and a script runner that
is not neutral cannot be swapped in under a test suite.

### Performance — real, and far too small to matter

Runner overhead in isolation, measured against the tool invoked directly
(`esbuild --version`, their own headline example):

| invocation | total | overhead above the binary |
|---|---|---|
| `./node_modules/.bin/esbuild --version` | 0.346 s | — |
| `nubx esbuild --version` | 0.373 s | **~27 ms** |
| `pnpm exec esbuild --version` | 0.609 s | **~263 ms** |

So Nub's overhead claim replicates: roughly 10× less runner overhead, ~236 ms
saved per invocation, and identical output (`0.28.1` both ways). Note the *19×*
headline is overhead-only — end to end this is 1.6×, because the binary itself
costs 346 ms.

Per-run on real gate scripts (derived from timed loops, 3 runs each):

| script | `pnpm` | `nub run` | delta |
|---|---|---|---|
| `stylelint` | 1.55 s | 1.18 s | **−0.37 s** |
| `typecheck` | 6.91 s | 6.36 s | **−0.54 s** |
| `lint` | 5.34 s | 5.38 s | +0.04 s (noise) |
| `test` | 16.8 s | 17.7 s | not comparable — different work, see above |

Whole-gate saving is therefore **~0.9 s**, against a frontend gate of ~30 s and
a backend suite of ~360 s. The fixed overhead is only visible on the shortest
script and disappears into noise on the longest.

### What this means

`nubx` is sound — same output, ~0.24 s faster per CLI invocation — but nothing
in this repo invokes one-off CLIs in a loop, so there is no workload to point it
at. `nub run` is disqualified on correctness regardless of speed.

Prediction logged before measuring was "low single-digit seconds across the
gate", which held (~0.9 s). The correctness failure was **not** predicted, and
is the finding that actually decides it — a reminder that a runner swap needs an
equivalence check, not just a stopwatch.

## Sources

- <https://nubjs.com/> · <https://nubjs.com/docs/install> ·
  <https://nubjs.com/docs/install/pnpm> · <https://nubjs.com/blog/introducing-nub>
- <https://github.com/nubjs/nub>
