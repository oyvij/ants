---
name: matt-skills
description: Update the shipped Skills that are Matt Pocock's (grill, to-spec, to-tickets, implement, tdd, code-review, pr, codebase-design) to his latest release with `scripts/matt-skills`, and adapt their In Ants sections. Use when Matt Pocock releases new skills, or when one of those Skills should match his again.
---

# matt-skills

Each of those Skills is two parts split at **the line**, the `---` under its `## In Ants` section. Above it is
Ants' own: frontmatter, the copyright comment with his version, and In Ants, which maps his chat-based Skill onto a
headless Task. Below it is his `SKILL.md` **word for word**. `scripts/matt-skills` rewrites everything below the
line and copies his companion files; `--help` says how. Everything Ants needs goes above the line, so the text below
it stays his.

## Steps

1. **Get his release.** `git clone --depth 1 https://github.com/mattpocock/skills "$(mktemp -d)/skills"`, or use
   the newest cached plugin the script finds by default. Compare its `.claude-plugin/plugin.json` version with the
   `v…` in the Skills' headers; read his `CHANGELOG.md` entries in between. Done when you hold a release newer than
   the headers, or you have told the user they are current and stopped.
2. **Branch** from `origin/main`, then run `scripts/matt-skills <folder>`. Done when it has printed one line per
   Skill.
3. **Resolve every note** it printed:
   - `text changed`: read `git diff` below the line, paragraph by paragraph, against that Skill's In Ants section and
     the mapping below. Each new step that assumes something a Task lacks gets one line in In Ants.
   - `new file`: shipped beside the Skill as it is; if his text sends the agent there for something a Task lacks,
     map it in In Ants.
   - `no longer his`: delete it, unless something of Ants' points at it (`grep -r` the shipped folder).
   - `calls "x", which isn't in its ants-uses`: ship `x` as a new Skill (below), and add it to the caller's
     `ants-uses`.
   - `is gone from his skills`: find what replaced it in his `CHANGELOG.md`, and fix `SKILLS` in the script.

   Done when every note is resolved and every changed paragraph of his has a mapping or needs none.
4. **Check the contract.** Each Skill's In Ants still ends in its `result.schema.json`'s answer, and no new text of
   his contradicts an Ants rule that In Ants leaves unaddressed. Update the list of his Skills in `docs/spec.md`
   (Shipped content) when it changes.
5. **Test and ship.** `scripts/affected-tests --run`, then the browser shard on CI as `CLAUDE.md` says. Commit
   (`Matt Pocock's Skills are his v<version>`), push, and open a pull request listing each In Ants line you added and
   why. Done when the tests are green and the pull request is open.

## Mapping: his Skill → a Task

A Task runs headless (`claude -p`, `dontAsk`), handed its Skill's path, inputs and the used Skills' paths, and ends
by answering `done` or `stuck` with its Result. So In Ants maps:

- **The user** → whatever stands in for them: an input (the prompt, the Rounds), the Skill using this one, the
  user's later review (approve, send back, which resumes the Task with their comment), or the agent's own judgement.
  A question with no stand-in becomes `stuck` with the question.
- **The chat format** → the Result's fields; a back-and-forth becomes a Round of the Result, resumed with answers.
- **The Skill tool** → read the `SKILL.md` whose path the hand-off line gives; the called Skill is in `ants-uses`.
- **Sub-agents** → the Agent tool, where the Agent has it (`src/main/resources/shipped/agents/`); else the agent does
  that work itself.
- **The issue tracker** → `docs/agents/issue-tracker.md` in the Run workspace; Ants parses the plain `Status:` and
  `Blocked by:` lines, so its format wins over his template's.
- **`/setup-matt-pocock-skills`**, labels, publishing → the Run workspace's `tickets/` and the Result.

## A new Skill of his

Make `src/main/resources/shipped/skills/<name>/SKILL.md` with only the part above the line, `codebase-design`'s as
the model: frontmatter (`name`, a description ending "Used by other Skills, never picked by a Task." when only
`ants-uses` reaches it), the copyright comment, `## In Ants`, the line, a blank line. Copy `LICENSE` from `tdd`, add
it to `SKILLS` in the script and run the script again.
