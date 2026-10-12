---
name: propose-solution
description: Read the Run's clones and propose how to build what the prompt asks, as proposal/proposal.md, for an implementer to follow.
metadata:
  ants-inputs: prompt brief clones
---

# Propose a Solution

Propose how to build what the prompt asks, so that an implementer can build it without designing
it again. Nobody approves the proposal before it is built, so make it right.

Your inputs:

- `prompt`: what the user wants, in their words.
- `brief`: the scout's brief, relative to the Run workspace: the repos and why, and what the Vault
  says.
- `clones`: the repos cloned for the Run, `[{name, path, commit}]`, `path` relative to the Run
  workspace.

You read the clones; you write only in `proposal/`.

## 1. Read

1. Read the brief, then each clone's own guidance: `CLAUDE.md`, `AGENTS.md`, `CONTRIBUTING.md`,
   coding standards, ADRs and a glossary, where they exist.
2. Find the code the change touches, and how its tests are written and run.

If the prompt can't be built without a decision only the user can make, answer `stuck`, naming
the decision and the options, with the one you recommend.

## 2. Write the proposal

Write `proposal/proposal.md`:

- **Problem**: what the prompt asks, in a few lines.
- **Solution**: the smallest change that does it, in the code's own terms, repo by repo.
- **Changes**: each file or module to touch, and what changes there.
- **Tests**: the behaviours to test, through which public interface, and how the repo runs them.
- **Out of scope**: what a reader might expect that this change leaves alone.

Answer `done` with `proposal: "proposal/proposal.md"`.
