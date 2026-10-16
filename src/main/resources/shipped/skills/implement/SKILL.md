---
name: implement
description: Build a Ticket of the Run's Spec, or the Run's proposal, test-first in the clones, review the commits and commit on the work branch.
metadata:
  ants-inputs: ticket spec prompt proposal
  ants-uses: tdd code-review
---

<!-- Matt Pocock's implement Skill (https://github.com/mattpocock/skills, v1.3.1), word for word below the line.
Copyright (c) 2026 Matt Pocock. MIT License: see LICENSE in the tdd Skill's folder. -->

## In Ants

Below the line is Matt Pocock's implement Skill, word for word. Follow it. Ants runs it without a
chat, so where the two differ, this section wins.

The work is one of two pairs of inputs; paths are relative to the Run workspace:

- `ticket` and `spec`: a Ticket of the Run's Spec, `tickets/issues/NN-<slug>.md`, and the Spec,
  `tickets/spec.md`. Build the Ticket, and only it; the Spec is its context.
- `prompt` and `proposal`: what the user asked, and the proposal for it, `proposal/proposal.md`.
  Build the proposal.

There is no Skill tool. To call the Skill tool with a name, read and follow that Skill's
`SKILL.md`: the line that handed you this Skill gives each one's path.

- **The code** is in the clones, `repos/<name>/` in your workspace, each on the branch you commit
  to. Commit there; never push, and never switch, rebase or reset a branch: Ants lands your
  commits and pushes them. Follow each clone's own `CLAUDE.md`, `AGENTS.md` and
  `CONTRIBUTING.md`, where they exist. With no clone in `repos/`, never make a repo
  (`git init`): Ants can't land it. Answer `stuck`, saying the Run has no repo and that a new one
  is named in the start form's Repos, as `owner/name`.
- **Before you change anything**, note each clone's `HEAD` commit: it is the fixed point the
  review starts from. With a Ticket, set its `Status:` line to `in-progress`. If it can't be built
  as written, set it to `needs-info`, add a comment under `## Comments` saying why, and answer
  `stuck` with the question.
- **The pre-agreed seams** are those the Ticket's acceptance criteria and the Spec's Testing
  Decisions name, or the proposal's Tests.
- **The suites:** run each touched repo's whole suite once at the end.
- **The review** runs from the fixed point, against the Ticket and the Spec, or the proposal. Fix
  each finding you agree with as a new commit, never amending the commits under review, and run
  the suites again.

Answer `done` with `review`: the review's two reports, under `### Standards` and `### Spec`, saying
what each found, what you fixed, and anything you left and why.

---

Implement the work described by the user in the spec or tickets.

Call the Skill tool with "tdd" where possible, at pre-agreed seams.

Run typechecking regularly, single test files regularly, and the full test suite once at the end.

Once done, call the Skill tool with "code-review" to review the work.

Commit your work to the current branch.
