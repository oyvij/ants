---
name: implement
description: Build a Ticket of the Run's Spec, or the Run's proposal, test-first in the clones, review the commits and commit on the work branch.
metadata:
  ants-inputs: ticket spec prompt proposal
  ants-uses: tdd code-review
---

<!-- Adapted from Matt Pocock's implement Skill (https://github.com/mattpocock/skills).
Copyright (c) 2026 Matt Pocock. MIT License: see LICENSE in the tdd Skill's folder. -->

# Implement

Build one piece of work in the clones, test-first, review it, and leave it committed.

The line that handed you this Skill names your workspace and the tdd and code-review Skills. The
clones are in `repos/<name>/` in your workspace, each on the branch you commit to. Your inputs are
one of two pairs; paths are relative to the Run workspace:

- `ticket` and `spec`: a Ticket of the Run's Spec, `tickets/issues/NN-<slug>.md`, and the Spec,
  `tickets/spec.md`. Build the Ticket, and only it; the Spec is its context.
- `prompt` and `proposal`: what the user asked, and the proposal for it,
  `proposal/proposal.md`. Build the proposal.

## Rules

- Commit on the branch each clone is on. Never push, and never switch, rebase or reset a branch:
  Ants lands your commits and pushes them.
- Follow each clone's own guidance: `CLAUDE.md`, `AGENTS.md`, `CONTRIBUTING.md`, coding standards
  and ADRs, where they exist.
- Change only what the work needs.
- Work only in the clones. With no clone in `repos/`, never make a repo (`git init`) yourself:
  Ants can't land it. Answer `stuck`, saying the Run has no repo and that a new one is named in
  the start form's Repos, as `owner/name`.

## 1. Start

Note each clone's `HEAD` commit before you change anything: it is the fixed point your review
starts from.

With a Ticket, set its `Status:` line to `in-progress`. If the Ticket can't be built as written,
set it to `needs-info`, add a comment under `## Comments` saying why, and answer `stuck` with the
question.

## 2. Build

Build by following the tdd Skill, with the Ticket's acceptance criteria, or the proposal's Tests,
as the behaviours to test. Run single test files often, and each touched repo's whole suite once
at the end. Commit in small steps, in the repo's commit-message style.

## 3. Review

Review your commits by following the code-review Skill, from the fixed point, against the Ticket
and the Spec, or the proposal. Fix what it finds as new commits, and run the suites again.

## 4. Answer

Answer `done` with `review`: a short markdown summary of the review, under `### Standards` and
`### Spec`, saying what each found and what you fixed, and anything you left and why.
