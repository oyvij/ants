---
name: walkthrough
description: Write a Story set for a code change across one or more repos, guided stories that follow the code the way it runs, so a reviewer reads the change in order instead of file by file.
---

<!-- Adapted from Varde's walkthrough Skill (https://github.com/oyvij/varde-editor).
Copyright (c) 2026 Øyvind Johannessen. MIT License: see LICENSE in the search-knowledge Skill's folder. -->

# Walkthrough

Write a guided reading of a change, so a reviewer reads it the way it runs, not file by file.

You read the repos with `git log`, `git diff`, `git show` and `git merge-base`, and you change no
code.

## 1. Read the change

For each repo, the change under review is `origin/<default branch>...HEAD`. Its base is the
merge-base, `git merge-base origin/<default branch> HEAD`, and its head is `HEAD`'s commit. A
repo with no commits since its base is left out.

Read what the change set out to build, where you were given it, and the diffs, until you can tell
the change as a few stories: each one a path a request, a call or a piece of data takes through
the code.

## 2. Write the Story set

The format is `story-set.schema.json` beside this file: Varde's story format widened to several
repos.

- `ranges`: one `{repo, base, head}` per repo with changes, `repo` its name.
- `stories`: each with an `id`, a `name`, a one-paragraph `premise` and its `steps` in the order
  the code runs.
- Each step has an `id`, a `name`, a `claim` (what this code does here), a `why` (why it matters
  to the story) and a `site`: `{file, side, kind, from, to}`, `file` the path to the file,
  `side` `new` for the head's lines or `old` for the base's, `kind` `changed` when the lines are in
  the diff or `context` when they aren't, and the 1-based lines `from` to `to`.
- Optional: `flow` (`{in, out}`: what enters and leaves the step), `values` (a concrete value the
  step shows, with its `provenance` and a `cite` of the file and line it is written on) and a
  `nudge` (what to look at closely).

Together the stories should touch every hunk of the change.

## 3. Check it

A Story set holds when every file exists at its side, every Site is inside its file, a `changed`
Site touches a hunk, and every cited value is on its line. Fix each step that fails.

When the change moves on, write the Story set again whole against the new heads: never patch the
old one.
