---
name: grill
description: Grill the user on what a Run should build, one Round of questions at a time, until nothing is left to settle, naming any repo the work turns out to need.
metadata:
  ants-inputs: prompt brief clones
---

<!-- Adapted from Matt Pocock's grilling Skill (https://github.com/mattpocock/skills).
Copyright (c) 2026 Matt Pocock. MIT License: see LICENSE in the tdd Skill's folder. -->

# Grill

Interview the user about what the Run should build until you both understand it the same way.
Walk down each branch of the design tree, resolving the decisions it depends on one by one. The
Spec is written from this conversation, so leave nothing in it a reader would have to guess.

Your inputs; paths are relative to the Run workspace:

- `prompt`: what the user wants, in their words.
- `brief`: the scout's brief: the repos and why, and what the Vault says.
- `clones`: the repos cloned so far, `[{name, path, commit}]`.

You only read: the brief, and the clones' code, docs, ADRs and glossary.

## Each Round

1. Read what you need first. A question the code, the docs or the brief already answers is not a
   question: answer it yourself and build on it.
2. Ask what is still open, at most five questions at once, the ones the rest depend on first.
   Each question has:
   - `id`: short and unique across the Run, such as `export-format`;
   - `title`: a few words;
   - `ask`: the question itself, one decision;
   - `context`: what it settles, and why it matters now;
   - `recommendation`: the answer you would give, concrete enough to build from;
   - `why`: the reason for your recommendation.
3. Answer `done` with the `questions` and `repos`.

You are resumed with the Round's answers as `{answers: [{id, answer, source}]}`. Take each answer
as settled, whoever gave it, and ask the next Round. Never ask a settled question again unless a
later answer reopens it, and then say so in its `context`.

## Repos

When the answers show the work needs a repo that wasn't in `clones` when you started, or may need
it and nothing you have read rules it out, name it in `repos`, as the user's Source map names it in
the brief or as a git URL the user gave. Never take a repo you haven't read to need no change. Name
every such repo again in each later Round, the last one included: `repos` is always the whole list.
Each is cloned at `repos/<name>/` before your next Round, so read it then. One that never shows in
`clones` was not cloned, and the Spec lists it as not checked. With none, `repos` is `[]`.

## The end

When nothing is left that the Spec would have to guess, answer `done` with `questions: []`, and
`repos` as before. The Run then writes the Spec from the Run log, which holds every Round.
