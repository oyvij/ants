---
name: update-knowledge
description: Propose what a Run learned as Notes in the user's knowledge Vault, and write them once the user approves.
metadata:
  ants-inputs: rounds spec review clones approval
  ants-uses: search-knowledge
  varde-vault: true
  ants-verbosity: true
---

<!-- Adapted from Varde's update-knowledge Skill (https://github.com/oyvij/varde-editor).
Copyright (c) 2026 Øyvind Johannessen. MIT License: see LICENSE in the search-knowledge Skill's folder. -->

# Update Knowledge

Capture what this Run learned into the Vault: propose, revise, and write only once the user has
approved.

The line that handed you this Skill names the Run workspace, the Vault, the Source map and the
search-knowledge Skill, whose folder holds `vault-format.md` and `leak-check.sh`. Your inputs:

- `rounds`: the Run log, relative to the Run workspace: every Round, instruction and message.
- `spec`: what the Run built, relative to the Run workspace (a Spec or a proposal), if any.
- `review`: the implementers' review of their commits, if any: a summary, or their Results, each
  with the summary as `output.review`.
- `clones`: the repos the Run cloned, `[{name, path, commit}]`.
- `approval`: the user's answer to your last proposal, `{approved, comment}`, or null before you
  have proposed.

## With no Vault

When the line that handed you this Skill names no Vault, the Run's Profile has none: answer `done`
with `written: true` and nothing else.

## If `approval.approved` is true: write

The user approved the proposal in `vault/proposal.md`. Write the staged Notes in `vault/staging/`
by following step 6 of "Writing to the Vault" in `vault-format.md`, after a last leak check. Write
nothing the proposal didn't name. Answer `done` with `written: true`.

## Otherwise: propose

### 0. Check the Vault has rules

Check that the Note `Vault/Vault.md` exists in the Vault and links `[[Writing knowledge Notes]]`.
If it does not, answer `stuck`, needing: "This Vault has no rules yet. Run the Set up Vault Process,
then Continue."

### 1. Read

1. Read `vault-format.md` in full. It fixes the shape of every Note and how Notes reach the Vault.
2. Read the Vault rules. They decide what is worth keeping, how much detail a Note carries, and
   how Topics, Kinds and audiences are chosen.
3. Read the Run: `rounds`, `spec`, `review`, and the commits on each clone's work branch. Find what was
   learned: decisions and their reasons, how something behaves, answers to questions somebody
   asked, mistakes and what fixed them.
4. Find what the Vault already holds on the Run's subject by following the search-knowledge Skill.
5. Read the clones wherever a takeaway depends on code, and confirm it is still true of the source.

Done when every takeaway is confirmed against the source or needs none, and you know which
existing Notes each one touches.

If nothing in the Run passes the Vault rules, write nothing and answer `done` with
`written: true` and a summary saying so: there is nothing to approve.

### 2. Stage and propose

Stage the Notes by following steps 1 to 5 of "Writing to the Vault" in `vault-format.md`. Write
each Note in its Kind's style, as the Vault rules and the Notes already in that Kind show it. For
`source`, name the clone by its Source map name.

Then write `vault/proposal.md` with two bullet lists:

- **Takeaways**: one line each, a summary for review. Drop every takeaway that fails the Vault
  rules, and say in one line what you dropped.
- **Notes**: the proposal, as `vault-format.md` describes it. In an empty Vault, say that every
  Topic and Kind is new.

Answer `done` with `proposal: "vault/proposal.md"` and `written: false`.

## When the user sends the proposal back

You are resumed with their comment. Revise the staged Notes and `vault/proposal.md` from it, show
the whole proposal again in the file, and answer `written: false` again.
