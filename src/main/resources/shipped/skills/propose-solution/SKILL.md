---
name: propose-solution
description: Read the Run's clones and propose how to build what the prompt asks, as proposal/proposal.md, for an implementer to follow, naming any repo the work turns out to need.
metadata:
  ants-inputs: prompt brief clones
  ants-verbosity: true
---

# Propose a Solution

Propose how to build what the prompt asks, so that an implementer can build it without designing
it again. Nobody approves the proposal before it is built, so make it right.

Your inputs:

- `prompt`: what the user wants, in their words.
- `brief`: the scout's brief, relative to the Run workspace: the repos and why, and what the Vault
  says.
- `clones`: the repos cloned so far, `[{name, path, commit}]`, `path` relative to the Run
  workspace.

You read the clones; you write only in `proposal/`.

## 1. Read

1. Read the brief, then each clone's own guidance: `CLAUDE.md`, `AGENTS.md`, `CONTRIBUTING.md`,
   coding standards, ADRs and a glossary, where they exist.
2. Find the code the change touches, and how its tests are written and run.

If the prompt can't be built without a decision only the user can make, answer `stuck`, naming
the decision and the options, with the one you recommend.

**Repos.** When the work needs a repo that wasn't in `clones` when you started, or may need it and
nothing you have read rules it out, or the user asks you to look at one, name it in `repos`, as the
user's Source map names it in the brief or as a git URL the user gave. Ants clones it: never clone
it yourself, nor ask for a command that does. Name every such repo again each time you answer:
`repos` is always the whole list. Never take a repo you haven't read to need no change: until it
shows in `clones`, the proposal lists it as not checked, with what to check there. Each is cloned
at `repos/<name>/` and your session resumed, so read it then and propose again. With none, `repos`
is `[]`.

## 2. Write the proposal

Write `proposal/proposal.md`:

- **Problem**: what the prompt asks, in a few lines.
- **Solution**: the smallest change that does it, in the code's own terms, repo by repo.
- **Changes**: each file or module to touch, and what changes there.
- **Tests**: the behaviours to test, through which public interface, and how the repo runs them.
- **Out of scope**: what a reader might expect that this change leaves alone.

Answer `done` with `proposal: "proposal/proposal.md"`, and `repos`.
