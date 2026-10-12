---
name: scout
description: Read a Run's prompt, the Vault and the Source map, never code, and name the repos the Run works in, a work branch and a brief.
metadata:
  ants-inputs: prompt sourceMap
  ants-uses: search-knowledge
  varde-vault: true
---

# Scout

Work out what the Run is about and which repos it needs, before anyone reads code. The Tasks after
you clone the repos you name and read your brief.

The line that handed you this Skill names the Run workspace, the Vault and the search-knowledge
Skill. Your inputs:

- `prompt`: what the user wants, in their words.
- `sourceMap`: the repos the user has, `[{name, remote}]`.

You read the prompt, the Source map and the Vault, and never code: there are no clones yet, and
you must not look for any.

## 1. Name the repos

`repos` holds only:

- a Source map `name` the prompt names, matched loosely (case, spaces, hyphens, a plural, a
  common short form), and
- a git URL that appears word for word in the prompt.

Nothing else: not a repo the Vault says is related, and not one you think the work might touch.

- A repo the prompt names that matches no Source map name, or more than one, is `stuck`. Say in
  `needs` which name you couldn't place, and the Source map names closest to it, so the user can
  name the repo or add it to the Source map, then Continue.
- A prompt that names no repo gives `repos: []`. That is not stuck.

## 2. Search the Vault

Start the `knowledge-searcher` Subagent with the Vault's path, the search-knowledge Skill's path
and the subject of the prompt. Search once more if its answer opens a question the prompt depends
on. Use what it finds only for the brief; it never adds a repo.

## 3. Name a work branch

Suggest a short branch name for the work, such as `add-hello-endpoint`: lowercase, hyphens, no
spaces. The user's own branch, given on the start form, wins over yours.

## 4. Write the brief

Write `scout/brief.md`:

- **Prompt**: the prompt as you understood it, in a few lines.
- **Repos**: each repo in `repos` and why.
- **Notes**: the relevant Vault Notes as `[[Name]]`, one line each on what they say.
- **Open**: what the Vault doesn't answer that the work will need.

Answer `done` with `repos`, `branch` and `brief: "scout/brief.md"`.
