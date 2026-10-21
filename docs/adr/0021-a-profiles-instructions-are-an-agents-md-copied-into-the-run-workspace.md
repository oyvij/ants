# A Profile's instructions are an AGENTS.md copied into the Run workspace

The user has standing instructions (writing, code and commit style) that every Agent in a Run should follow, and they
differ between their work and personal Profiles. What reached Agents was an accident: the Run workspace lives under
the user's home folder and claude walks up from its cwd, so the home CLAUDE.md loaded into every claude Agent Task of
every Run, whatever the Profile. codex Agents got nothing.

- **The file per Profile is `profiles/<profile>/AGENTS.md` in ANTS_HOME**, not a path in `settings.yaml`. The path
  follows from the Profile's name, so the Profile row stays as it is and can't point at a missing file. A missing or
  blank file means no instructions. The Settings page edits it from an **Instructions** button on the Profile's row.
- **AGENTS.md is the one file the user edits.** At Run start, if the Run's Profile (`ants.profile`, ADR 0012) has
  instructions, Ants copies its AGENTS.md to the Run workspace root and writes a CLAUDE.md beside it. That CLAUDE.md is
  the ranking sentence, a blank line, then the AGENTS.md text inlined, not `@AGENTS.md` (probe 3). With no
  instructions Ants writes neither file and changes no flag.
- **A copy at Run start, not a link.** Editing, renaming or removing the Profile mid-Run doesn't change what the Run's
  Agents follow. Cleanup removes the copy with the workspace. Parallel instances link both files like every other
  top-level entry.
- **The Profile's instructions rank lowest:** an Agent's and a Skill's own instructions win, then a repo's own
  CLAUDE.md or AGENTS.md, then the Profile's. The generated CLAUDE.md says so in one sentence: "These are the user's
  standing instructions for this Run. Where they disagree with your Agent or Skill instructions, or with a repo's own
  CLAUDE.md or AGENTS.md, those win." codex gets no sentence: its AGENTS.md text is a user message and the Agent's
  `developer_instructions` already outrank it.
- **A Run with instructions excludes every CLAUDE.md above the Run workspace.** Its claude launches pass
  `{"disableAllHooks":true,"claudeMdExcludes":[...]}` as `--settings`, with exact absolute paths, for each folder D
  from the Run workspace's parent up to `/`: `D/CLAUDE.md`, `D/CLAUDE.local.md` and `D/.claude/CLAUDE.md`. That covers
  the home `~/.claude/CLAUDE.md`, which reaches a Run through the walk up from the cwd, not as user memory, since
  `CLAUDE_CONFIG_DIR` is ANTS_HOME/claude. Instance launches use the same list; the Run workspace's own CLAUDE.md isn't
  in it (probe 3).
- **The Process Builder is left out.** It runs with `--setting-sources ""` and gets no Profile instructions.

## Probes

claude 2.1.306, through `scripts/claude-probe` (haiku, Ants' flags, `CLAUDE_CONFIG_DIR` set as in Ants), in a
throwaway Run workspace under a folder with its own CLAUDE.md, holding AGENTS.md, CLAUDE.md, `repos/r/CLAUDE.md` and
`instances/k/` linking CLAUDE.md, AGENTS.md and `tickets`. Each asked the model to list every memory file in its context.

1. **The Run workspace's CLAUDE.md loads and its `@AGENTS.md` import is followed**, with the cwd at the workspace root.
   Holds there, but not in an instance (probe 3), so fallback 1 is applied: CLAUDE.md inlines the AGENTS.md text.
2. **`claudeMdExcludes` keeps the CLAUDE.md files above the Run workspace out.** Holds with the exact-path list above,
   passed as a second `--settings`, which replaces the first: the stand-in home CLAUDE.md and `~/.claude/CLAUDE.md`
   didn't load; the workspace CLAUDE.md, its import and `repos/r/CLAUDE.md` (once a file in `repos/r` was read) did.
3. **In an instance (cwd `instances/k/`) the instructions load once.** The CLAUDE.md loads once, under the root's path:
   the link and the ancestor count as one file. But its `@AGENTS.md` import isn't followed there (three runs, with and
   without the excludes), nor when the root CLAUDE.md is excluded and the link loads instead. With the AGENTS.md text
   inlined, it loads once with the instructions, at the root and in the instance. So fallback 1 is applied, and fallback
   3 (excluding the root CLAUDE.md in an instance) is not.
4. **codex loads AGENTS.md from its working folder, in the Run workspace and through the link in an instance.** Read
   from the source of codex `rust-v0.163.0`, not probed: codex wasn't installed. `codex-rs/core/src/agents_md.rs` walks up
   from the cwd to a `project_root_markers` entry (default `.git`); with none found, "only the current working directory
   is considered". It checks each candidate with `get_metadata` and reads it with `read_file`, whose default options in
   `codex-rs/file-system/src/lib.rs` have `follow_symlinks: true`, so a linked AGENTS.md is read. `exec_includes_workspace_agents_md_in_request`
   in `codex-rs/exec/tests/suite/agents_md.rs` checks `codex exec --skip-git-repo-check` sends the cwd's AGENTS.md as a
   user message. The text is skipped only for a project whose `trust_level` is `untrusted` in config, which Ants' own
   `CODEX_HOME` never sets, and is capped at `project_doc_max_bytes` (32 KiB by default).

## Considered Options

- **A path in `settings.yaml` per Profile, like the Vault and Source map**: a path that can point at a missing or
  shared file, and one more field on the Profile row, for a file only Ants reads.
- **A symlink in the Run workspace to the Profile's file**: no copy, but an edit, rename or removal mid-Run would change
  or break what a running Run's Agents follow.
- **The Profile's instructions ranking higher**, above a repo's own CLAUDE.md or AGENTS.md, or above the Agent's and the
  Skill's: personal style would override a repo's conventions inside that repo, or break how a Process step is meant to
  work.
- **CLAUDE.md importing `@AGENTS.md`**, as first planned: one copy of the text, but the import isn't followed in a
  parallel instance (probe 3).

## Consequences

- The Run's CLAUDE.md holds a second copy of the instructions, both written from the Profile's AGENTS.md at Run start;
  the user still edits only AGENTS.md.
- A user CLAUDE.md in ANTS_HOME/claude, which nothing writes, would still load: it is user memory, not a file above the
  Run workspace, and wasn't probed.
- If codex is ever run inside a git repo above the Run workspace, it would walk up to that repo's root and also read
  the AGENTS.md files on the way.
