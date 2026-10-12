---
name: to-tickets
description: Cut the Run's Spec into Tickets, thin vertical slices with their Blocked by, as files in tickets/issues/.
metadata:
  ants-inputs: spec clones
---

<!-- Adapted from Matt Pocock's to-issues Skill (https://github.com/mattpocock/skills).
Copyright (c) 2026 Matt Pocock. MIT License: see LICENSE in the tdd Skill's folder. -->

# To Tickets

Cut the Spec into Tickets that implementers build in parallel, each in its own worktree, each
landing on the work branch as it is done.

Your inputs; paths are relative to the Run workspace:

- `spec`: the approved Spec, `tickets/spec.md`.
- `clones`: the repos cloned for the Run, `[{name, path, commit}]`.

You read the clones; you write only in `tickets/`. Read `docs/agents/issue-tracker.md` first: it
says how a Ticket's file looks.

## 1. Slice

Each Ticket is a thin vertical slice: it cuts through every layer it needs, end to end, and is
demoable or testable on its own once it lands. Prefer many thin slices to a few thick ones.

- **Blocked by**: a Ticket that needs another's code to start is blocked by it. Block only where
  it is true: an unblocked Ticket starts at once, in parallel with the others, and two Tickets
  editing the same lines will conflict when the second lands.
- **ready-for-human**: a slice only the user can do, such as a decision, a credential or a manual
  check, is `ready-for-human`; its dependents wait until the user marks it done. Every other
  Ticket is `ready-for-agent`.

## 2. Write the Tickets

One file per Ticket, `tickets/issues/NN-<slug>.md`, numbered from `01` in dependency order, as
`issue-tracker.md` says:

```markdown
# <title>

Status: ready-for-agent

Blocked by: none

## What to build

<the end-to-end behaviour, in the Spec's terms, citing its sections rather than repeating them>

## Acceptance criteria

- [ ] <a behaviour a test can show>

## Comments
```

## When you are sent back

You are resumed with a comment. Revise the Tickets from it, and answer again.

Answer `done` with `tickets`: every Ticket's path, in number order.
