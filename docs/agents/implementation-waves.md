# Implementation waves

The v1 spec (`docs/spec.md`, issue #27) is cut into 51 tickets, #28–#78, linked with native blocking edges. Build them in
waves, not in one `/implement-spec` run over the whole spec. Each wave has its own integration branch
and its own code review, and the user merges it to `main` before the next wave starts. Every ticket's
blockers sit in an earlier wave or in the same wave.

Run each command in a fresh session (`/clear` first). Paste it as written.

Before each wave, run `gh workflow run soak.yml` and fix any test it finds flaky, so the implementers start
from a suite that fails only on real bugs.

## Wave 1: Foundation (#28–#31), by hand

A straight chain that fixes the shape of the codebase: the Gradle layout, the test harness and the
fake `claude`. Run one ticket per session and review each before the next.

```
/mattpocock-skills:implement #28 on branch wave-1-foundation (create it from main). Follow docs/agents/issue-tracker.md: when done, comment what was built and close the ticket.
```

Then the same for `#29`, `#30` and `#31`, in order, on the same branch. #30 has a "verify" criterion:
read its finding before going on, and change the spec if it contradicts it. Merge `wave-1-foundation`
to `main`.

## Wave 2: Runtime and settings (#32–#45)

Transcript, Notification list, `[failed]`, Send and Stop, usage limit, Run page, Home, settings, login,
Agents, Skills, `ants-uses`, Used by, Subagents.

```
/mattpocock-skills:implement-spec Spec #27. Implement only tickets #32–#45; every other ticket is out of scope, even if it is on the frontier. Integration branch: wave-2-runtime, from main. Resolve tickets as docs/agents/issue-tracker.md says (comment, close). Stop after the code-review fixes; don't merge to main.
```

#36 has a "verify" criterion: read its finding before merging.

## Wave 3: Modeler, Schedules and the pre-Run check (#46–#56)

```
/mattpocock-skills:implement-spec Spec #27. Implement only tickets #46–#56; every other ticket is out of scope, even if it is on the frontier. Integration branch: wave-3-modeler, from main. Resolve tickets as docs/agents/issue-tracker.md says (comment, close). Stop after the code-review fixes; don't merge to main.
```

## Wave 4: Git, User Tasks, Tickets and review (#57–#68)

#84 adds Playwright browser tests: wave 3's untried modeler criteria, plus wave 4's own browser criteria. Restore
the work moved out of wave 3 (`git show ba85df6 6a6223d`). Chromium runs in a local session only under the nono
rules #84 lists.

```
/mattpocock-skills:implement-spec Spec #27. Implement only tickets #57–#68 and #84; every other ticket is out of scope, even if it is on the frontier. Integration branch: wave-4-git-and-review, from main. Resolve tickets as docs/agents/issue-tracker.md says (comment, close). Stop after the code-review fixes; don't merge to main.
```

## Wave 5: Shipped content (#69–#73)

The Vault, the default Processes end to end, and codex.

```
/mattpocock-skills:implement-spec Spec #27. Implement only tickets #69–#73; every other ticket is out of scope, even if it is on the frontier. Integration branch: wave-5-shipped, from main. Resolve tickets as docs/agents/issue-tracker.md says (comment, close). Stop after the code-review fixes; don't merge to main.
```

#73 has a "verify" criterion: read its finding before merging.

## Side wave: Packaging (#74–#78)

Any time after wave 2 is on `main` (#76 needs #41, #77 needs #39, #78 needs #33). It touches little
of what waves 3–5 touch, so it can run beside them.

```
/mattpocock-skills:implement-spec Spec #27. Implement only tickets #74–#78; every other ticket is out of scope, even if it is on the frontier. Integration branch: wave-p-packaging, from main. Resolve tickets as docs/agents/issue-tracker.md says (comment, close). Stop after the code-review fixes; don't merge to main.
```
