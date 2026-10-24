---
name: matt-skills
description: Update the shipped Skills that are Matt Pocock's (grilling, to-spec, to-tickets, implement, tdd, code-review, pr, codebase-design) to his latest release with `scripts/matt-skills`, a plain copy, and adjust the Assignments of the shipped Tasks that use them. Use when Matt Pocock releases new skills, or when one of those Skills should match his again.
---

# matt-skills

His Skills ship **word for word** (ADR 0024): each folder in `src/main/resources/shipped/skills/` is his, under his
name, with only a licence comment under the `SKILL.md` frontmatter and his `LICENSE` beside it. An update is copying
his release: `scripts/matt-skills` does it and pins every file's SHA-256 in `src/test/resources/matt-skills.sha256`,
which `MattSkillsTest` checks, since CI has no copy of his release. Never edit his files by hand.

What ties a Skill to a Run (its inputs, where it writes, what its Result holds, who stands in for the user) lives in
the **Assignment** (`ants.assignment`) of each shipped Process and Template Task that uses it, beside its
`ants.skills` and `ants.result-schema`. The hand-off already carries the ranking and the Round protocol, so no
Assignment repeats them.

## Steps

1. **Get his release.** `git clone --depth 1 https://github.com/mattpocock/skills "$(mktemp -d)/skills"`, or use
   the newest cached plugin the script finds by default. Compare its `.claude-plugin/plugin.json` version with the
   first line of `matt-skills.sha256`; read his `CHANGELOG.md` entries in between. Done when you hold a newer
   release, or you have told the user they are current and stopped.
2. **Branch** from `origin/main`, then run `scripts/matt-skills <folder>`. Done when it has printed one line per
   Skill.
3. **Resolve every note** it printed:
   - `text changed`: read `git diff`, paragraph by paragraph, against the Assignments of the Tasks that list that
     Skill (`grep -rn 'ants.skills' src/main/resources/shipped`) and the mapping below. Each new step that assumes
     something a Task lacks gets a line in those Assignments. A Template and the copy `build-and-ship.bpmn` embeds
     change together (`ShippedBpmnTest`).
   - `new file`: it ships as it is; if his text sends the agent there for something a Task lacks, map it in the
     Assignments.
   - `is no longer his`: it is gone already; check nothing of Ants' pointed at it (`grep -r`).
   - `calls "x", which Ants doesn't ship`: add `x`'s path to `SKILLS` in the script, run it again, and list `x` on
     the Agents and Tasks that use the caller.
   - `new`: list it on the shipped Agents (`skills:`) and Tasks (`ants.skills`) that should use it.
   - `is gone from his skills`: find what replaced it in his `CHANGELOG.md`, and fix `SKILLS`; a rename means
     renaming it on every Agent and Task, and in the tests.
   - A Skill whose frontmatter has `disable-model-invocation: true` (to-spec, to-tickets, implement) is one claude
     never offers the model: its Task's Assignment names it ("by following the to-spec Skill"), and the hand-off
     gives its file to read.

   Done when every note is resolved and every changed paragraph of his has a mapping or needs none.
4. **Check the contract.** Each Assignment still ends in its `ants.result-schema`'s answer, and no new text of his
   contradicts an Ants rule the Assignment leaves unaddressed.
5. **Test and ship.** `scripts/affected-tests --run`, then the browser shard on CI as `CLAUDE.md` says. Commit
   (`Matt Pocock's Skills are his v<version>`), push, and open a pull request listing each Assignment line you added
   and why. Done when the tests are green and the pull request is open.

## Mapping: his Skill → a Task

A Task runs headless (`claude -p`, `dontAsk`), handed its Assignment, inputs and allowed Skills, and ends by
answering `done` or `stuck` with its Result. So an Assignment maps:

- **The user** → whatever stands in for them: an input (the prompt, the Rounds), the Task's Assignment, the user's
  later review (approve, send back, which resumes the Task with their comment), or the agent's own judgement. A
  question with no stand-in becomes `stuck` with the question.
- **The chat format** → the Result's fields; a back-and-forth becomes a Round, which the hand-off's Round protocol
  covers for a Task another element names in `ants.asker`.
- **The Skill tool** → works as it is: list every Skill his text calls in the Task's `ants.skills` and on its
  Agent, since `ants.skills` loads exactly what it lists.
- **Sub-agents** → the Agent tool, where the Agent has it (`src/main/resources/shipped/agents/`); else the agent does
  that work itself.
- **The issue tracker** → `docs/agents/issue-tracker.md` in the Run workspace; Ants parses the plain `Status:` and
  `Blocked by:` lines, so its format wins over his template's.
- **`/setup-matt-pocock-skills`**, labels, publishing → the Run workspace's `tickets/` and the Result.
