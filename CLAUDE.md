# Ants

Runs BPMN Processes whose Tasks are AI agents.

- Vocabulary: `GLOSSARY.md`. Decisions: `docs/adr/`.
- Issues live on the public GitHub repo `oyvij/ants` (personal account, never an org), driven with
  `gh`. See `docs/agents/issue-tracker.md`.
- Testing: while building, run only what the change touches, locally: `scripts/affected-tests --run` (seconds to a
  minute). Before pushing, run its classes, or the whole local suite (`./gradlew test -Pshard=rest`) when it says so.
  The browser shard (BrowserTest, LookTest, …) can't run locally (no Chromium in the sandbox), and the user won't
  run it for you: for a change to the pages, run it on CI yourself with
  `gh workflow run ci.yml --ref <branch> -f shards='["browser","look"]'` and wait for it. Pushes to a branch start
  no CI; the full suite runs on the pull request and on main, and gates the merge and the release. A red job that
  is a flake reruns alone (`gh run rerun <id> --failed`), and the flaky test gets a fix of its own. Before handing
  tickets to parallel agents, run `gh workflow run soak.yml` (the suite five times at once, under CPU load, in random
  order) and fix any test it finds flaky.
- The spec is `docs/spec.md`, "Spec: Ants v1"; issue #27 points to it and its Tickets hang off #27. It collapses
  the closed `/wayfinder` map #1 and its sub-issues.
- Helper scripts live in `scripts/` (each prints its usage with `--help`); project skills in `.claude/skills/`.
