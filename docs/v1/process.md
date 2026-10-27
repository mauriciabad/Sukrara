# Sukrara V1 night run: process

> Record of the Sukrara V1 night run (2026-10-27), copied from the run's working file `METHODOLOGY.md + CHUNK-AGENT.md` with paths
> made readable. `OPS/…` paths name the run's scratch files (briefs, reviews, issue lists), which are not in
> the repo. Index: [README.md](README.md).

## Part 1: methodology (every agent)

Goal tonight: turn the Sukrara showcase PoC into a production-ready V1 monorepo (Android + Mac), reviewed, tested,
released as **0.x.x release candidates** (never 1.0.0). The owner (with diabetes, uses the app daily on a real
sensor) installs it in the morning. Nobody is available to answer questions: decide yourself, write the decision
and the reason down, move on.

`OPS` = `the night run's scratch directory (not in the repo)`
Repo = `the repository root` (GitHub `mauriciabad/Sukrara`, renamed from cgm-reading-mac-spike).

### Branches
- `main`: untouched until the end. Only the orchestrator merges `next` into it.
- `next`: integration branch ("alternative main"). The main checkout stays on it. Only the orchestrator merges.
- Work branches `v1-<chunk>` in your own git worktree based on `next`. Commit often, small, with clear messages.
  Never push `main`. Never force-push. Never `--no-verify` (use the trailers in CLAUDE.md when a check is wrong).

### Gates (every chunk passes all of them, in order)
- **G0 Plan**: research online first (official docs, recent best practice, real users' forums/issues — never rely on
  memory alone), then write the chunk plan to `OPS/plan/<chunk>.md`; criticise it yourself (what's wrong, missing,
  over-engineered, risky?) and revise. An independent critic agent reviews it; revise until approved.
- **G1 Build**: implement in the worktree. Don't copy-paste the PoC blindly: read it, understand the behaviour, then
  write the clean version (better names, smaller units, clear boundaries, no dead code, tests). Keep behaviour that
  the owner relies on unless the plan says why it changes. Unit tests + `tools/check/run.sh` pass.
- **G2 Review**: an independent reviewer (fresh agent, given the diff, not your conclusions) for code; a UX reviewer
  with screenshots (emulator / Mac snapshot) for anything visible. Findings are fixed or answered in writing.
- **G3 Merge**: orchestrator merges into `next` after checks pass.
- Final gates on `next`: whole-app critical reviews (code, security/privacy, UX/usability/utility, accessibility,
  performance/battery), feature test pass over `docs/FEATURES.md`, then release.

### Rules that never bend
- Read `CLAUDE.md` (project rules). They still apply (GPL headers, strings en+es, secrets, cleartext allow-list,
  debug fakes, alert scenarios, settings schema, one graph renderer, FEATURES.md, README, complete flows...).
- Safety first: this is a CGM app the owner acts on. Never show fake data on release builds; alerts must be reliable;
  never lose the owner's sensor pairing, identity keys or settings on upgrade without an explicit, tested migration.
- Never touch the owner's phone (it's not connected anyway) or run anything against real relays/sensors from tests.
- Never commit secrets, keystores, APKs, build dirs, health screenshots.

### Heartbeat (mandatory — a watchdog checks it)
Append one line to `OPS/status/<your-agent-name>.log` at start, at every phase change, and at least every ~15 min
of work: `date -u +%FT%TZ` + ` <phase> <short what>`. Last line when you finish: `<ts> DONE <one-line result>` or
`<ts> BLOCKED <why>`. If you're stuck >20 min on the same problem, write BLOCKED with what you tried and finish.

### Context hygiene
Don't read huge files whole; grep/sed ranges. Delegate wide reads to Explore subagents if you have the Agent tool.
If your context gets large (you've read/written a lot and still have much left), write a precise handoff
(`OPS/handoff/<name>.md`: done / remaining / gotchas / branch+worktree path), heartbeat `HANDOFF`, and finish.

### Outputs
- Long results go to files (paths given in your task). Your final message to the orchestrator: ≤ 200 words —
  what you did, where the files are, decisions taken, open risks. No file dumps.
- Things for later (features, decisions for the owner, bugs, investigations) → one line each in
  `OPS/issues/<your-name>.md` (`title — 1-3 sentence body — labels`). The orchestrator turns them into GitHub issues.
- Features you find in code that aren't in `docs/FEATURES.md` → `OPS/inventory/undocumented-<name>.md`.

## Part 2: how a chunk agent works

OPS = `the night run's scratch directory (not in the repo)`.
Read first: `process.md`, your brief `OPS/plan/chunks/<NN>-<id>.md`, the PLAN sections it cites
(`plan.md`, `d1-change.md` = new ids, no migration), the repo `CLAUDE.md`, and
`changes.md` if it exists (orchestrator amendments after the plan critique — they override PLAN/briefs).
Heartbeat: `OPS/status/c<NN>.log` (see METHODOLOGY).

### Setup
You run in your own git worktree. First: `git checkout -b v1-<NN>-<id> next` (the integration branch `next` is a
local ref). Run `tools/hooks/install.sh` once. Work only inside the paths your brief owns. If a dependency chunk
hasn't merged yet ("pre"), do G0 and the pure code first; before G1 tests and again before finishing,
`git merge next` (no rebase of shared history) and resolve conflicts.

### G0 — plan (≤ 15 min)
1. Search the web for current best practice on your chunk's core problem (≥ 2 good sources, official docs first;
   real-user issues where relevant). Note sources in your plan.
2. Read the PoC code the brief points to (grep/ranges; Explore subagents for wide reads). Understand behaviour,
   then design the clean version — not a copy.
3. Write `OPS/plan/chunks/<NN>-<id>.plan.md`: design, files, tests, risks, what you deliberately change vs the PoC.
   Criticise it yourself (wrong? missing? over-engineered? untestable? unsafe?) and revise.
4. Spawn a **critic** subagent (Agent tool, general-purpose, effort high) with only: the brief path, your plan path,
   METHODOLOGY.md path, and: "Criticise this plan hard — correctness, safety for a person acting on glucose data,
   simplicity, maintainability, testability, missing states/exits, platform best practice (search the web).
   Reply ≤ 250 words: verdict APPROVE or REVISE + numbered required changes." Revise until APPROVE (max 2 rounds;
   after that decide yourself and record why). Save the verdicts at the end of your plan file.

### G1 — build
Clean, idiomatic, small units, explicit names, no dead code, GPL headers, strings en+es (Android), tests for logic
(contracts/ fixtures where they exist). Commit in small coherent steps. Run your module's tests and
`tools/check/run.sh` until green. UI chunks: produce screenshots (Roborazzi / emulator / Mac snapshot, synthetic
data only) into `OPS/reviews/<NN>-shots/`.

### G2 — independent review
Spawn a **reviewer** subagent (general-purpose, effort high), giving it only: repo/worktree path, the diff command
`git diff next...HEAD`, your brief path, and the checklist: correctness bugs, safety (never fake data on release,
alerts reliable, no secrets/health data in logs), concurrency/lifecycle, error handling, simplicity/duplication,
naming, test quality, CLAUDE.md rules; for UI also `OPS/research/ux.md` checklist + your screenshots path
(a11y: TalkBack/VoiceOver labels, contrast, reduced motion, complete flows: every state has a visible way out).
Ask for ≤ 300 words, findings ranked by severity. Fix every real finding; write the review + your answers to
`OPS/reviews/<NN>-<id>.md`. Re-run tests and checks.

If you can't spawn subagents, run the critic/review passes yourself as separate explicit steps and mark them
"self-review" — the orchestrator will add an independent one.

### Finish
Everything committed on `v1-<NN>-<id>`, merged with latest `next`, tests + checks green. Update `docs/FEATURES.md`
/ README only for the sections your chunk owns. Issues for later → `OPS/issues/c<NN>.md`; features found in PoC
code but missing from FEATURES.md → `OPS/inventory/undocumented-c<NN>.md`. Heartbeat `DONE`.
Final message ≤ 200 words: branch, worktree path, last commit, tests/commands run with pass/fail, G0/G2 verdicts,
deviations from the brief, open risks. Do NOT merge into `next` and do NOT push — the orchestrator does.
