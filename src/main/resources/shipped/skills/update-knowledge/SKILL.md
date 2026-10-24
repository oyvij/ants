---
name: update-knowledge
description: Propose what a piece of work learned as Notes in the user's knowledge Vault, and write them once the user approves.
---

<!-- Adapted from Varde's update-knowledge Skill (https://github.com/oyvij/varde-editor).
Copyright (c) 2026 Øyvind Johannessen. MIT License: see LICENSE in the search-knowledge Skill's folder. -->

# Update Knowledge

Capture what a piece of work learned into the Vault: propose, revise, and write only once the
user has approved.

`vault-format.md` and `leak-check.sh` are in the search-knowledge Skill's folder.

## 0. Check the Vault has rules

Check that the Note `Vault/Vault.md` exists in the Vault and links `[[Writing knowledge Notes]]`.
If it does not, stop: the Vault needs its rules first (the init-vault Skill writes them).

## 1. Read

1. Read `vault-format.md` in full. It fixes the shape of every Note and how Notes reach the Vault.
2. Read the Vault rules. They decide what is worth keeping, how much detail a Note carries, and
   how Topics, Kinds and audiences are chosen.
3. Read the work: the conversation, the spec or plan, the review and the commits you were pointed
   at. Find what was learned: decisions and their reasons, how something behaves, answers to
   questions somebody asked, mistakes and what fixed them.
4. Find what the Vault already holds on the work's subject by following the search-knowledge
   Skill.
5. Read the code wherever a takeaway depends on it, and confirm it is still true of the source.

Done when every takeaway is confirmed against the source or needs none, and you know which
existing Notes each one touches.

If nothing passes the Vault rules, write nothing and say so: there is nothing to approve.

## 2. Stage and propose

Stage the Notes by following steps 1 to 5 of "Writing to the Vault" in `vault-format.md`. Write
each Note in its Kind's style, as the Vault rules and the Notes already in that Kind show it. For
`source`, name the code by its Source map name.

Then write the proposal with two bullet lists:

- **Takeaways**: one line each, a summary for review. Drop every takeaway that fails the Vault
  rules, and say in one line what you dropped.
- **Notes**: the proposal, as `vault-format.md` describes it. In an empty Vault, say that every
  Topic and Kind is new.

When the user sends the proposal back with a comment, revise the staged Notes and the proposal
from it, and show the whole proposal again.

## 3. Write

Once the user approves, write the staged Notes by following step 6 of "Writing to the Vault" in
`vault-format.md`, after a last leak check. Write nothing the proposal didn't name.
