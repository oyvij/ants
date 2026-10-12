---
name: walkthrough
description: Write a Story set for the Run's changes, guided stories that follow the code across its repos, as walkthrough/stories.json for Review changes.
metadata:
  ants-inputs: spec clones
---

<!-- Adapted from Varde's walkthrough Skill (https://github.com/oyvij/varde-editor).
Copyright (c) 2026 Øyvind Johannessen. MIT License: see LICENSE in the search-knowledge Skill's folder. -->

# Walkthrough

Write a guided reading of what the Run changed, so the user reviews the change the way it runs,
not file by file. You write it for this one review.

Your inputs; paths are relative to the Run workspace:

- `spec`: what the Run set out to build, `tickets/spec.md`.
- `clones`: the repos cloned for the Run, `[{name, path, commit}]`; each clone is at `path`, on
  the work branch.

You read the clones, with `git log`, `git diff` and `git show` run from inside a clone
(`cd repos/<name> && git log …`); you write only in `walkthrough/`. You change no code.

## 1. Read the change

For each clone, the change under review is `origin/<default branch>...HEAD`. Its base is the
merge-base, `git merge-base origin/<default branch> HEAD`, and its head is `HEAD`'s commit. A
clone with no commits since its base is left out.

Read the Spec, the Tickets in `tickets/issues/` and the diffs, until you can tell the change as a
few stories: each one a path a request, a call or a piece of data takes through the code.

## 2. Write `walkthrough/stories.json`

The format is `story-set.schema.json` beside this file: Varde's story format widened to a Run.

- `ranges`: one `{repo, base, head}` per clone with changes, `repo` its name.
- `stories`: each with an `id`, a `name`, a one-paragraph `premise` and its `steps` in the order
  the code runs.
- Each step has an `id`, a `name`, a `claim` (what this code does here), a `why` (why it matters
  to the story) and a `site`: `{file, side, kind, from, to}`, `file` relative to the Run
  workspace (`repos/<name>/…`), `side` `new` for the head's lines or `old` for the base's, `kind`
  `changed` when the lines are in the diff or `context` when they aren't, and the 1-based lines
  `from` to `to`.
- Optional: `flow` (`{in, out}`: what enters and leaves the step), `values` (a concrete value the
  step shows, with its `provenance` and a `cite` of the file and line it is written on) and a
  `nudge` (what to look at closely).

Together the stories should touch every hunk of the change.

## 3. The checks

When you answer `done`, Ants checks the Story set: every file exists at its side, every Site is
inside its file, a `changed` Site touches a hunk, and every cited value is on its line. A failure
comes back naming the steps; fix them and answer again.

## When the changes come back

After the user sends the changes back and they are fixed, you are run again. Rewrite
`walkthrough/stories.json` whole against the new heads: never patch the old one.

Answer `done` with `stories: "walkthrough/stories.json"`.
