---
name: tdd
description: Build test-first in red-green slices at the seams the work's criteria name. Used by other Skills, never picked by a Task.
---

<!-- Adapted from Matt Pocock's tdd Skill (https://github.com/mattpocock/skills).
Copyright (c) 2026 Matt Pocock. MIT License: see LICENSE beside this file. -->

# TDD

Build test-first: one failing test, then the least code that passes it, slice by slice.

## Seams

A seam is the public boundary a test drives: an HTTP endpoint, a CLI, a module's exported
functions. Tests live at seams, never against internals.

There is no user to agree the seams with, so take them from the work: the Ticket's acceptance
criteria and the Spec, or the proposal's Tests. Prefer the seams the repo's own tests already use.
Write the seams down in your checklist before the first test. Answer `stuck` only when the work
names no behaviour you could test at any seam, saying what you would need.

## What a good test is

- It drives a whole behaviour through a public interface and reads like a line of the spec.
  It survives a rewrite of the code behind the seam.
- Its expected values come from an independent source: a literal, a worked example, the spec.
  Never recompute them the way the code does.
- It mocks only what the repo doesn't own: external APIs, time, randomness. Never the repo's own
  modules.

## The loop

1. Pick the next behaviour. Write one test for it, run it, and see it fail for the right reason.
2. Write only enough code to pass it. Run it, and the tests near it.
3. Commit, and go to 1.

Work in vertical slices: one test and its code at a time, never all the tests first. Refactor
after the behaviours are in, with the tests green, not inside the loop.
