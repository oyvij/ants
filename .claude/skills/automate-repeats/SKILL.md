---
name: automate-repeats
description: Find repeated work in past Claude Code sessions worth turning into a script plus a skill, to save time and tokens next time. Use when the user asks what to automate, which tasks keep repeating, or how to make sessions cheaper.
---

# automate-repeats

A **repeat** is one job that took several tool calls and shows up more than once: twice in one session, or across
sessions. The cure is a script that does the job in one call with compact output, plus a pointer so a future
session reaches for it.

## Steps

1. **Digest the sessions.** Run `scripts/session-tools --last 5` (or the session files the user names). It groups
   Bash commands by signature with call counts, the number of sessions they appear in, and output size, and lists
   the largest results and the failed calls. Use `--calls --grep <regex>` to see one job's calls in order. Read a
   raw transcript only for the reasoning around a call the digest can't explain.

2. **List the repeats.** A candidate is any of:
   - a signature that recurs across sessions, or a run of 3+ calls doing one job (fetch, filter, re-fetch);
   - a large result the agent then filtered by hand (a spec dump grepped, JSON or XML parsed inline);
   - trial and error against the same tool (wrong flags, missing commands, output too noisy to read);
   - lookups of the same fact from several places (issues, docs, jars).

   Before proposing new tooling, check `scripts/` and `.claude/skills/`: a repeat that an existing script
   covers is a pointer problem; sharpen the pointer instead.

3. **Weigh each one.** For every candidate, write down: the calls and output characters it cost, how many
   sessions it appeared in, and whether upcoming work hits it again (open tickets, the next wave in
   `docs/agents/implementation-waves.md`). Drop any candidate that won't recur. A build-config fix or a one-line
   doc pointer beats a script whenever it removes the repeat.

4. **Present them ranked** by expected savings: for each, the evidence (counts and sizes), the proposed tool, and
   how a future session will find it.

5. **Build what the user approves.**
   - The script goes in `scripts/<name>`, written in Python 3 with only the standard library and made executable.
     Usage goes in its docstring, printed by `--help`. It prints one compact line per item and saves anything
     long to a file.
   - The pointer: a skill in `.claude/skills/<name>/SKILL.md` when the agent must decide on its own when to use
     the script. When an existing workflow already reaches a doc, add one line to that doc instead
     (`docs/agents/issue-tracker.md` for ticket work).
   - Run each script once on real input and show the before and after: calls and characters then, one call and
     its size now.

Done when every candidate has its evidence and a decision (script and skill, script and pointer, config fix, or
skip), and every approved script has been run once on real input.
