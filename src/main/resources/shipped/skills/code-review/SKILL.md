---
name: code-review
description: Review commits since a fixed point along two axes, Standards and Spec, in two parallel Subagents, and fix what they find as new commits. Used by other Skills, never picked by a Task.
---

<!-- Adapted from Matt Pocock's code-review Skill (https://github.com/mattpocock/skills).
Copyright (c) 2026 Matt Pocock. MIT License: see LICENSE beside this file. -->

# Code Review

Review your own commits on two separate axes:

- **Standards**: does the code follow the repo's documented standards?
- **Spec**: does the code do what the work asked: the Ticket and Spec, or the proposal?

## 1. Pin the fixed point

The Skill using this one gives the fixed point: each clone's commit before the work began. For
each clone with commits since it, note `git diff <fixed-point>...HEAD` and
`git log <fixed-point>..HEAD --oneline`. A clone with none is left out.

## 2. Find the sources

- **Standards**: anything in the clone that says how code is written, such as
  `CODING_STANDARDS.md`, `CONTRIBUTING.md`, `CLAUDE.md` or `AGENTS.md`, plus the smell baseline:
  Mysterious Name, Duplicated Code, Feature Envy, Data Clumps, Primitive Obsession, Repeated
  Switches, Shotgun Surgery, Divergent Change, Speculative Generality, Message Chains, Middle Man
  and Refused Bequest (Fowler, _Refactoring_, ch. 3). A documented standard overrides the
  baseline, and a smell is always a judgement call.
- **Spec**: the Ticket and the Spec, or the proposal, by path.

## 3. Start both Subagents at once

Start two Subagents in parallel, so neither sees the other's findings:

- **Standards**: give it the diff commands, the standards files and the smell baseline. Ask it to
  report every place the diff breaks a documented standard, citing the file and the rule, and any
  baseline smell, quoting the hunk; to keep breaches and judgement calls apart; to skip what
  tooling enforces; and to stay under 400 words.
- **Spec**: give it the diff commands and the spec's paths. Ask it to report what the spec asks
  that is missing or partial, what the diff does that wasn't asked, and what looks implemented
  but wrong, quoting the spec line for each, under 400 words.

## 4. Fix and report

Fix each finding you agree with as a new commit; never amend or rewrite the commits under review.
Keep the two reports apart when you summarise them, under `### Standards` and `### Spec`: what
each found, what you fixed, and what you left and why.
