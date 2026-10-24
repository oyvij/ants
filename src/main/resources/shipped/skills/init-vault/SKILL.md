---
name: init-vault
description: Set up or revise a knowledge Vault's rules, what it keeps and how it is organised, by asking the user its open decisions and writing the rule Notes once they approve.
---

<!-- Adapted from Varde's init-vault Skill (https://github.com/oyvij/varde-editor).
Copyright (c) 2026 Øyvind Johannessen. MIT License: see LICENSE in the search-knowledge Skill's folder. -->

# Init Vault

Write the Vault rules: what the Vault keeps and how it is organised. Every other Vault Skill reads
them before it touches a Note, and stops when they are missing.

`vault-format.md` and `leak-check.sh` are in the search-knowledge Skill's folder.

## 1. Read

1. Read `vault-format.md` in full. It fixes the shape of a Note; the rules you write here decide
   content and organisation within that shape.
2. If the Note `Vault/Vault.md` exists, you are revising: read it and every Note it links under
   `## Guidelines`. Ask only about the decisions it leaves open or that the Vault's Notes now
   contradict, and recommend keeping what it says.
3. List the Vault's Topics and Kinds and read a few Notes from each, so your recommendations rest
   on what is there. An empty Vault is fine.

## 2. Ask the decisions

Ask the user every open decision below at once, one question each. Each question says what it
asks, what the answer settles, and gives your recommendation and why. When the Vault already
answers a decision, say what you found and recommend it. An answer that contradicts another, or
`vault-format.md`, may take one more question; otherwise every decision is settled.

Topic and Kind names grow from content, so leave them to the first capture.

1. **Readers**: who reads the Vault: the user alone, a team, newcomers. This sets how much
   context a Note carries.
2. **Worth keeping**: the test a takeaway must pass. Recommend: still useful and true in six
   months, to a colleague who was not there.
3. **Level of detail**: what a Note holds and what it leaves to the code. Recommend: general
   knowledge; call chains, line numbers, edge cases and logs only when they change how someone uses
   or changes the system.
4. **Audiences**: the values of `audience`, and which of them get separate Notes. Recommend
   `product` (what it does, why, how people use it) and `engineering` (how it is built), in
   separate Notes linked both ways.
5. **Kinds**: how a Kind's style is set, and where knowledge goes that fits two Kinds. Recommend:
   a Kind's style is what its Notes already show; knowledge that fits two Kinds lives in one Note,
   linked from the other Kind's index.
6. **Change**: when a Note is updated and when it is superseded. Recommend: update while the
   subject stays the same, supersede when Notes are merged or split.
7. **Never in the Vault**: what is never written down, in the user's own words. Recommend:
   credentials, personal data, and anything naming this machine (the leak check enforces the last).

## 3. Stage and propose

Stage both Notes by following steps 1 to 5 of "Writing to the Vault" in `vault-format.md`:

- `Vault/Vault.md`, the Vault Topic's index Note: one paragraph on what the Vault is for and who
  reads it, then a section each for Audiences, Kinds, Change and Never in the Vault, then
  `## Guidelines` linking `[[Writing knowledge Notes]]`.
- `Vault/Guidelines/Writing knowledge Notes.md`: Readers, Worth keeping and Level of detail.

When revising, change the existing Notes in place, with a `## History` entry for each change.

Show the user both Notes in full, and revise them from what they say until they approve.

## 4. Write

Once the user approves, write the staged Notes by following step 6 of "Writing to the Vault" in
`vault-format.md`, after a last leak check.
