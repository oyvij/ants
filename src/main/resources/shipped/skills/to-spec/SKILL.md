---
name: to-spec
description: Write the Run's Spec, tickets/spec.md, from the prompt, the brief, every Round and the clones.
metadata:
  ants-inputs: prompt brief rounds clones repos
---

<!-- Matt Pocock's to-spec Skill (https://github.com/mattpocock/skills, v1.3.1), word for word below the line.
Copyright (c) 2026 Matt Pocock. MIT License: see LICENSE in the tdd Skill's folder. -->

## In Ants

Below the line is Matt Pocock's to-spec Skill, word for word. Follow it. Ants runs it without a
chat, so where the two differ, this section wins.

The conversation is the Run's. Your inputs; paths are relative to the Run workspace:

- `prompt`: what the user wants, in their words.
- `brief`: the scout's brief.
- `rounds`: the Run log, every grilling round with its answers and who gave them.
- `clones`: the repos cloned for the Run, `[{name, path, commit}]`. They are the codebase.
- `repos`: the repos the grilling found the work needs, as the griller named them.

You read the clones; you write only in `tickets/`. The issue tracker is
`docs/agents/issue-tracker.md` in the Run workspace: publishing the spec means writing
`tickets/spec.md`, starting with a `# <title>` line, then the template. The Spec takes no triage
label.

- **Checking the seams with the user:** write them under Testing Decisions. The user checks them
  when they approve the Spec, and sends it back if they don't match.
- An answer stamped `agent` or `recommended` was not the user's: build on it, but list it under
  Further Notes.
- A repo in `repos` that isn't in `clones` was not cloned, so nobody has read it and the Run can't
  change it: list it under Further Notes as not checked, with what to check there and what may
  need to change. Never write that it needs no change, or should need none: that is a guess about
  code nobody read.
- When the user sends the Spec back, you are resumed with their comment. Revise `tickets/spec.md`
  from it, and answer again.

Answer `done` with `spec: "tickets/spec.md"`.

---

This skill takes the current conversation context and codebase understanding and produces a spec. Do NOT interview the user; just synthesize what you already know.

The issue tracker and triage label vocabulary should have been provided to you. If not, tell the user to run `/setup-matt-pocock-skills`.

## Process

1. Explore the repo to understand the current state of the codebase, if you haven't already. Use the project's domain glossary vocabulary throughout the spec, and respect any ADRs in the area you're touching.

2. Sketch out the seams at which you're going to test the feature. Existing seams should be preferred to new ones. Use the highest seam possible. If new seams are needed, propose them at the highest point you can. The fewer seams across the codebase, the better - the ideal number is one.

Check with the user that these seams match their expectations.

3. Write the spec using the template below, then publish it to the project issue tracker. Apply the `ready-for-agent` triage label - no need for additional triage.

<spec-template>

## Problem Statement

The problem that the user is facing, from the user's perspective.

## Solution

The solution to the problem, from the user's perspective.

## User Stories

A LONG, numbered list of user stories. Each user story should be in the format of:

1. As an <actor>, I want a <feature>, so that <benefit>

<user-story-example>
1. As a mobile bank customer, I want to see balance on my accounts, so that I can make better informed decisions about my spending
</user-story-example>

This list of user stories should be extremely extensive and cover all aspects of the feature.

## Implementation Decisions

A list of implementation decisions that were made. This can include:

- The modules that will be built/modified
- The interfaces of those modules that will be modified
- Technical clarifications from the developer
- Architectural decisions
- Schema changes
- API contracts
- Specific interactions

Do NOT include specific file paths or code snippets. They may end up being outdated very quickly.

Exception: if a prototype produced a snippet that encodes a decision more precisely than prose can (state machine, reducer, schema, type shape), inline it within the relevant decision and note briefly that it came from a prototype. Trim to the decision-rich parts, not a working demo, just the important bits.

## Testing Decisions

A list of testing decisions that were made. Include:

- A description of what makes a good test (only test external behavior, not implementation details)
- Which modules will be tested
- Prior art for the tests (i.e. similar types of tests in the codebase)

## Out of Scope

A description of the things that are out of scope for this spec.

## Further Notes

Any further notes about the feature.

</spec-template>
