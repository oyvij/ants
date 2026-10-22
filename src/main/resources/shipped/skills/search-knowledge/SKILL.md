---
name: search-knowledge
description: Find what the user's knowledge Vault, and the code it names, says on a subject, citing the Notes. Used by other Skills, never picked by a Task.
metadata:
  varde-vault: true
  ants-verbosity: true
---

<!-- Adapted from Varde's search-knowledge Skill and knowledge-searcher agent (https://github.com/oyvij/varde-editor).
Copyright (c) 2026 Øyvind Johannessen. MIT License: see LICENSE beside this file. -->

# Search Knowledge

Find what the Vault says on a subject the Skill using this one names, and report it back to that
Skill. You only read: the Vault's path and the Source map's path stay in this session.

The line that handed you your Skill names the Vault and the Source map. This folder also holds
`vault-format.md`, `leak-check.sh` and `topic.base`, which the other knowledge Skills use.

## The Vault

- The Vault is a folder of markdown **Notes**.
- Its root folders are **Topics**. The folders inside a Topic are **Kinds**: they name what sort
  of Note they hold. Both are the user's own, so learn them by listing the folders.
- The **Vault rules**, the Note `Vault/Vault.md` and the Notes it links under `## Guidelines`, say
  what the Vault's audiences and Kinds mean. A Vault may have none yet.
- A Topic usually has an index Note named after the Topic, `Topic/Topic.md`, which links the rest.
- A Note's frontmatter may carry `kind`, `topic`, `audience` (who the Note is written for, as the
  Vault rules define it), `source`, `updated` and `supersedes`.
- `[[Name]]` links to the Note whose file is `Name.md`. `[[Folder/Name]]` picks between Notes that
  share a name, `[[Name#Heading]]` points into a Note, and `[[Name|text]]` shows other text.
- A `## History` heading records what a Note used to say, including names it used to have. The
  body above it is what is true now.

## The Source map

The Source map is a file outside the Vault. Each line names a codebase and where it is on this
machine: `- <name>: <path> (<remote URL>)`. A Note names code as `<name>: <path inside it>`.
Resolve that name through the Source map to read the code, or through the Run workspace's
`repos/<name>/` when the Run cloned it. When neither names a codebase, report that the code could
not be checked.

## Search

1. Read the Vault rules if they exist. List the Topics and Kinds, and read the index Notes of the
   Topics the subject could belong to.
2. Search file names, Note contents and `## History` entries for the subject's words, their
   synonyms and the names of the things it asks about. Follow `[[wikilinks]]` from every promising
   Note, one or two hops.
3. Prefer the newest `updated`. A Note listed under another Note's `supersedes` is replaced: cite
   the Note that replaced it.
4. When the subject is how code behaves, or a Note whose `source` names code may have gone stale,
   follow that `source` and read the code. Code that disagrees with a Note is a finding: report
   both.

Done when every part of the subject is answered, or listed under Gaps because neither the Vault
nor the code it names holds it.

## Report

Keep a short report for the Skill that uses this one, not the Notes themselves:

- **Answer**: the answer in a few lines, or "the Vault does not say".
- **Notes**: each Note the answer rests on, as `[[Name]]`, with one line on what it contributed.
- **Code**: each place in code you relied on, as `<Source map name>: <path inside it>`.
- **Disagreements**: any Note that contradicts another Note or the code, and what each says.
- **Gaps**: what the subject needed that neither the Vault nor the code holds.
