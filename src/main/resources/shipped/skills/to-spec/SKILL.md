---
name: to-spec
description: Write the Run's Spec, tickets/spec.md, from the prompt, the brief, every Round and the clones.
metadata:
  ants-inputs: prompt brief rounds clones repos
---

<!-- Adapted from Matt Pocock's to-prd Skill (https://github.com/mattpocock/skills).
Copyright (c) 2026 Matt Pocock. MIT License: see LICENSE in the tdd Skill's folder. -->

# To Spec

Turn the Run's conversation into a Spec: what the Run will build, written so that whoever cuts it
into Tickets and builds them needs nothing else. Don't ask again; settle from what you have.

Your inputs; paths are relative to the Run workspace:

- `prompt`: what the user wants, in their words.
- `brief`: the scout's brief.
- `rounds`: the Run log, every Round with its answers and who gave them.
- `clones`: the repos cloned for the Run, `[{name, path, commit}]`.
- `repos`: the repos the grilling found the work needs, as the griller named them.

You read the clones; you write only in `tickets/`. Read `docs/agents/issue-tracker.md` first: it
says how the Spec's file looks.

## 1. Read

Read the brief, the Run log and the parts of the clones the answers touch, with their own
guidance, glossaries and ADRs, so the Spec speaks the code's language. An answer stamped `agent`
or `recommended` was not the user's: build on it, but list it under Further Notes.

## 2. Write `tickets/spec.md`

```markdown
# <title>

## Problem Statement

## Solution

## User Stories

<a numbered list: "As a <who>, I want <what>, so that <why>">

## Implementation Decisions

<the modules to build or change, their interfaces, schemas and the decisions the Rounds settled;
no file paths or code, which go stale>

## Testing Decisions

<what makes a good test here, which modules are tested, and the prior art in the repos>

## Out of Scope

## Further Notes
```

A repo in `repos` that isn't in `clones` was not cloned, so nobody has read it and the Run can't
change it: list it under Further Notes as not checked, with what to check there and what may need
to change. Never write that it needs no change, or should need none: that is a guess about code
nobody read.

## When the user sends it back

You are resumed with their comment. Revise `tickets/spec.md` from it, and answer again.

Answer `done` with `spec: "tickets/spec.md"`.
