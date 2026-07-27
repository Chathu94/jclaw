# JCLAW-881 — Nub as a pnpm replacement (spike)

**Date**: 2026-07-27 · **Verdict: NO for package management and installation. Possible narrow follow-up for script execution only (`nub run` / `nubx`), unbenchmarked.**

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

## Possible follow-up

`nubx` / `nub run` for **script execution only**, leaving installation on
corepack + pnpm. That captures the largest claimed multiplier (24×/19×) without
touching dependency resolution or the integrity guard, and is reversible — it
changes how scripts are invoked, not what lands in `node_modules`.

Not benchmarked against this repo. Worth doing before considering, since the
~2 s figure above is derived from Nub's published numbers rather than measured
here, and script-runner overhead is only visible when it dominates the script
itself — which for `vitest`, `vue-tsc` and `nuxi build` it does not.

## Sources

- <https://nubjs.com/> · <https://nubjs.com/docs/install> ·
  <https://nubjs.com/docs/install/pnpm> · <https://nubjs.com/blog/introducing-nub>
- <https://github.com/nubjs/nub>
