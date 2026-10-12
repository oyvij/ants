# Ants

Runs BPMN Processes whose Tasks are AI agents.

- Vocabulary: `GLOSSARY.md`. Decisions: `docs/adr/`.
- Issues live on the public GitHub repo `oyvij/ants` (personal account, never an org), driven with
  `gh`. See `docs/agents/issue-tracker.md`.
- Testing: local runs skip `BrowserTest` (no Chromium in the sandbox), so push and wait for CI before calling
  work done. Before handing tickets to parallel agents, run `gh workflow run soak.yml` (the suite five times at
  once, under CPU load, in random order) and fix any test it finds flaky.
- The spec is `docs/spec.md`, "Spec: Ants v1"; issue #27 points to it and its Tickets hang off #27. It collapses
  the closed `/wayfinder` map #1 and its sub-issues.
- Helper scripts live in `scripts/` (each prints its usage with `--help`); project skills in `.claude/skills/`.
