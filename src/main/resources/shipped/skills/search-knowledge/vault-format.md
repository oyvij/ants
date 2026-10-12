# Vault format

<!-- Adapted from Varde's shared/vault-format.md (https://github.com/oyvij/varde-editor).
Copyright (c) 2026 Øyvind Johannessen. MIT License: see LICENSE beside this file.
Kept in step with Varde's by hand (Ants ADR 0002): a change to the format is made in both. Only "Proposals" and "Writing to the Vault" differ, since an Ants Task asks no questions
and the user approves on an Approve page. -->

Shared by the Vault Skills. This file fixes the shape of a Note and how it reaches the Vault. What
is worth keeping, and how the Vault is organised, belongs to the user: the **Vault rules** decide
that. Where the two meet, the Vault rules decide the content and this file decides the shape.

## Words

- **Note**: one markdown file in the Vault.
- **Topic**: a folder at the root of the Vault. It groups the Notes that belong together.
- **Kind**: a folder inside a Topic. It names what sort of Note it holds, and so the style that
  Note is written in.
- **Vault rules**: the Note `Vault/Vault.md`, relative to the Vault's root, and every Note it links
  under `## Guidelines`. The init-vault Skill writes them.

Topics and Kinds come only from this Vault's folders and the user's choices.

## Frontmatter

```yaml
---
kind: <the Kind folder's name>
topic: <the Topic folder's name>
audience: <one of the audiences the Vault rules define>
source: <a Source map name with a path inside that codebase, a public URL, or "conversation">
updated: <today, YYYY-MM-DD>
supersedes: []           # Notes this one replaces, as [[wikilinks]]
---
```

Notes in the `Vault` Topic carry only `kind`, `topic` and `updated`. Every change to a Note sets
`updated` to today.

## Proposals

Every change starts as a proposal: one bullet per Note, starting with its verb.

- **add** `Topic/Kind/Name`: a new Note.
- **merge into** `Topic/Kind/Name`: new knowledge joins an existing Note on the same subject.
- **update** `Topic/Kind/Name`: the new knowledge contradicts the Note or makes it out of date.
  Say in a few words what it says now and what it will say.
- **supersede** `Topic/Kind/Name` with `Topic/Kind/Name`: one Note replaces whole Notes.
- **move** `Topic/Kind/Name` → `Topic/Kind/Name`: a Note changes name, Kind or Topic. Say how many
  other Notes link to it.

Mark a Topic or Kind that would be new `(new Topic)` or `(new Kind)` and name the closest existing
one.

In Ants, the proposal is the file `vault/proposal.md` in the Run workspace, which the user reads on
an Approve page. Answer `written: false` with `proposal: "vault/proposal.md"`. The user either
approves it, and you are launched again with an `approval` input whose `approved` is true, or sends
it back with a comment, and you revise the proposal from it, write the whole file again and answer
`written: false` again.

## Names and links

- Name a Note by its subject. Its file name must be unique across the whole Vault: check before you
  add one.
- Link with `[[Name]]`. Use `[[Topic/Kind/Name]]` only when two Notes share a name.
- Each Topic has an index Note, `Topic/Topic.md`, named after the Topic so index Notes never share
  a name. It says in a few lines what the Topic covers, links every Note in the Topic, and embeds
  `![[Topic.base]]`.
- Each Topic has a Base, `Topic/Topic.base`, made from `topic.base` next to this file with `<Topic>`
  replaced by the Topic's name.

## Changing a Note

- Rewrite a Note in place so its body says what is true now. Add or extend a `## History` heading
  at the end, with one dated entry saying what the Note used to say and why it changed.
- When a Note supersedes others, list them under `supersedes`. Rewrite each replaced Note as a
  short pointer to the new one, with a `## History` entry.
- When a Note moves, set its `topic` and `kind` to its new folders. If its file name changes, add
  a `## History` entry naming the old name, and rewrite every link to it across the Vault:
  `[[Old]]`, `[[Old|text]]`, `[[Old#Heading]]`, `![[Old]]`, `[[Topic/Kind/Old]]` and entries under
  `supersedes`.

## This machine

- Name code as `<Source map name>: <path inside that codebase>`. Repository names and remote URLs
  are fine, so a colleague can find the same code.
- The Vault's path and the Source map's path stay in this session. They never go into the
  workspace, a memory file, configuration or an environment variable.
- The Source map is the one file where a path on this machine may be written, and it lives outside
  the Vault.

## Writing to the Vault

Steps 1 to 5 happen while you propose, before the user approves; step 6 only once your `approval`
input says `approved: true`.

1. Stage in the Run workspace's `vault/staging/` folder, outside the Vault, so the staged files
   last until the user approves. Inside it, give every file the same relative path it will have in
   the Vault.
2. Stage every file you will create or change: new Notes, existing Notes you change (copy them in
   first), Notes the link pass touches, index Notes and Bases. Nothing reaches the Vault except
   through staging.
3. **Link pass.** Add `[[wikilinks]]` both ways between the staged Notes and the Notes related to
   them, wherever a reader of one would want the other. Link the Notes that cover one subject for
   different audiences to each other.
4. **Index and Base.** For every Topic you touched, stage its index Note and its Base, creating
   them if they are missing.
5. **Leak check.** Run `sh <folder of this file>/leak-check.sh vault/staging`. Every line it
   prints is a leak: rewrite it using a Source map name, a path inside a codebase or a remote URL,
   then run the check again. If a printed line names nothing about this machine, for example a URL
   route like `/api/orders/` or an ordinary word that happens to be the user name, list it in the
   proposal so the user sees it before approving. If you cannot run a shell, say so in the
   proposal, and read every staged line for the home folder, the user name, the host name and
   absolute paths.
6. When the user has approved, and the last check printed `clean`, copy the staging folder's
   contents into the Vault. Then remove the old path of every accepted **move**, and any folder a
   move left empty. Remove nothing else. Delete the staging folder.

Done when every approved change is in the Vault, the last leak check printed `clean`, and the
staging folder is gone.
