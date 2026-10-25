# Ants

Runs BPMN Processes whose Tasks are AI agents.

- Vocabulary: `GLOSSARY.md`. Decisions: `docs/adr/`.
- Issues live on the public GitHub repo `oyvij/ants` (personal account, never an org), driven with
  `gh`. See `docs/agents/issue-tracker.md`.
- Testing: while building, run only what the change touches, locally: `scripts/affected-tests --run` (seconds to a
  minute). Before pushing, run its classes, or the whole local suite (`./gradlew test -Pshard=rest`) when it says so.
  An Agent Task in an Ants Run doesn't run the whole local suite unless it has to.
  The browser shards (BrowserTest, LookTest, …) run locally too, once Chromium is installed
  (`./gradlew playwright --args="install chromium"`), and the user won't run them for you: for a change to the
  pages, run the affected browser tests locally (`./gradlew test -Pshard=browser`, `-Pshard=look`). Pushes to a branch start
  no CI; the full suite runs on the pull request and on main, and gates the merge and the release. A red job that
  is a flake reruns alone (`gh run rerun <id> --failed`), and the flaky test gets a fix of its own. Before handing
  tickets to parallel agents, run `gh workflow run soak.yml` (the suite five times at once, under CPU load, in random
  order) and fix any test it finds flaky. Main must never go red after a merge: before marking a PR ready or
  reporting a spec done, merge main into the branch, run the full suite on CI on that tip
  (`gh workflow run ci.yml --ref <branch>`), fix every failure and rerun until all shards are green. A green run
  from before the last merge doesn't count.
- A spec is a GitHub issue, and its Tickets are its sub-issues. The first one, "Spec: Ants v1" (#27), lived in
  `docs/spec.md` until it was deleted; #27 links its last version.
- Helper scripts live in `scripts/` (each prints its usage with `--help`); project skills in `.claude/skills/`.
