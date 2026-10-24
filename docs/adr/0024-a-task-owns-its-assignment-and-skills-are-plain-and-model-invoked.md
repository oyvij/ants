# A Task owns its Assignment, and Skills are plain and model-invoked

An Agent Task named one Skill, and that Skill carried the whole job: an `## In Ants` section on top of the actual
how-to, `ants-inputs`, `ants-uses`, `ants-verbosity` and `ants-answer-history` metadata, a `result.schema.json`, and
protocol such as how a Round is answered. So every Task needed its own Skill, a Skill from elsewhere had to be
rewritten before Ants could run it, and the CLI only ever read the Skill as a file (`--disable-slash-commands` turned
real skills off). Now the Task says *what*, the Agent says *who and with what*, a Skill says *how*, and Ants says how
an Agent talks to it.

- **A Task has an Assignment**: what it asks its Agent to do, the job, what to read and what to hand back. It is
  optional when the Task names exactly one Skill: Ants then starts the Agent with that Skill as the job, `/<skill>`
  for claude and `$<skill>` for codex, followed by the inputs, as a user would type it. Only a Skill listed on the
  Task counts, not one inherited from the Agent, so giving an Agent one more Skill can't make a Task ambiguous. A
  Check refuses an Agent Task with no Assignment and not exactly one listed Skill.
- **The Task owns the Run coupling**: its inputs, its Result schema (none means a Result of outcome and summary only)
  and its Answer History and Vault toggles. A Skill declares none of them.
- **An Agent lists the Skills it may use** (`*` for all), beside its tools. **A Task may narrow them** with
  `ants.skills`, which replaces `ants.skill`: no list inherits the Agent's Skills, an empty list means none. Tools stay
  on the Agent only, so a Process's permissions are read in one place; a Task that needs other tools picks another
  Agent. The hand-off doesn't list the Skills: the CLI already shows the Agent exactly the allowed ones.
- **A Skill is a plain `SKILL.md`** that would work in Claude Code as it is, and knows nothing of Runs, Tasks or
  Rounds. Matt Pocock's Skills ship word for word; their `## In Ants` sections become the Assignments of the shipped
  Tasks. `ants-uses` goes: a Skill names another by name, and the Agent or Task allows both or not. The rule for the
  Ants-only Skills: what ties a Skill to a Run (inputs, where it writes, what its Result holds) becomes an Assignment;
  what describes a format or a way of working (the Vault format, a good Ticket) stays a Skill.
- **Ants writes the hand-off, and it is about the Task's features, never its Process**: a core every Agent Task gets
  (the Run workspace, the inputs, how to hand back a Result, verbosity, the ranking below) and parts each switched on
  by a Task property: the Vault and Source map, the Answer History, naming repos, and the Round protocol. The Round
  protocol moves out of the `grill` Skill: a Task that asks Rounds (`ants.asker`) gets how a Round is one Result, the
  question entry format, writing for a phone and being resumed with the answers, and Ants adds `questions` to its
  Result schema. So any Skill that asks questions, Matt's `grilling` unchanged too, can run in a Run.
- **Ranking**: Assignment, then the Agent's instructions, then the Skill's, then a repo's own CLAUDE.md or AGENTS.md,
  then Profile instructions. This amends ADR 0021, which ranked an Agent's and a Skill's instructions together on top.
- **claude loads only the allowed Skills.** Ants drops `--disable-slash-commands`. Per session it makes a folder whose
  `.claude/skills/` holds a symlink to each allowed Skill, and passes it with `--add-dir`, and each allowed Skill's own
  folder with `--add-dir` too, so the Agent can read the files beside its `SKILL.md`. With no Skills allowed, `Skill`
  is left out of `--tools`. A Skill's reference files need the Agent to have Read, and its scripts need Bash.
- **codex loads the whole catalogue and turns off the rest.** codex reads skills only from fixed folders, and its
  `--add-dir` loads none. Ants links its whole catalogue into `$ANTS_HOME/codex/skills`, the same for every session,
  and turns off per session each Skill the Task doesn't allow (`-c skills.config=[{name=…, enabled=false}]`) and
  codex's bundled ones (`skills.bundled.enabled=false`). Because Ants knows its whole catalogue, that deny list works
  as an allow list. This is unprobed: the codex ticket probes it first, and if a turned-off Skill can still run, a
  codex Agent reads its allowed Skills' `SKILL.md` files as files instead, the way every Agent did before. That is a
  fallback, never the default.
- **The user's files are migrated once, at startup**, with the old file kept beside the new: `ants.skill=X` becomes
  `ants.skills=X` with no Assignment, which runs the same job, and each user Skill's `result.schema.json` and
  `ants-inputs` move onto every Task that names it. Only the migration reads the old format. Since only one listed
  Skill can be the job, an X that used other Skills is listed with them and an Assignment to follow X; its Vault and
  Answer History opt-ins become the Task's toggles; a Task naming a shipped Skill whose job became a shipped Task's
  Assignment takes that Task's Assignment, Skills, schema and toggles; and the user's own Agents list what their Tasks
  now name. A Run in flight on an older version runs its Task as the latest version has it.
- **Where it is edited**: the modeler's Agent Task panel gets the Assignment, the allowed Skills (picked from the
  Agent's), the inputs and the Result schema; the Skills page edits only `SKILL.md`; the Process Builder's
  `create_skill` and `change_skill` lose `schema`, and its Agent Task tool gains `assignment` and `skills`.

## Probes

claude 2.1.309, through `scripts/claude-probe` (haiku, Ants' flags, on the user's own login, so its own skills and
plugins showed too; Ants' `CLAUDE_CONFIG_DIR` has none), with a probe copy that drops `--disable-slash-commands`.

1. **`--disable-slash-commands` removes the `Skill` tool and every skill.** The model found the codeword by reading
   the `SKILL.md` as a file.
2. **Skills load from `--add-dir` and `--plugin-dir`.** `<dir>/.claude/skills/x/SKILL.md` loads as `x`,
   `<plugin>/skills/x/SKILL.md` as `<plugin>:x`, and the model invoked both on its own from their descriptions.
3. **Permissions can't make an allow list.** Under `dontAsk`, `--allowedTools 'Skill(x)'` limits nothing;
   `--disallowedTools 'Skill(y)'` refuses `y` but still lists it in the init event's `skills`; `--disallowedTools Skill`
   removes the tool even beside an allowed `Skill(x)`. Hence the folder of allowed Skills.
4. **A `/x` prompt runs the skill.** In `-p`, claude expands it into skill `x` itself, with no `Skill` tool call.
5. **A symlinked Skill loads, but its files need their real folder added.** A Read of `references/codeword.md`
   through the symlink was refused until the symlink's target folder was an `--add-dir` too.

codex was read from its source (github.com/openai/codex, commit 970111f, `rust-v0.164.0`), not run: it isn't
installed. Skill roots are `$CODEX_HOME/skills`, `$HOME/.agents/skills`, `/etc/codex/skills` and `.agents/skills`
from the project root down to the cwd (`codex-rs/ext/skills/src/host_roots.rs`); `--add-dir` only adds writable roots;
`skills.config` and `skills.bundled.enabled` are honoured from `-c` (`codex-rs/config/src/skills_config.rs`).

## Considered Options

- **Every Task keeps naming one Skill that carries the job**: the old shape. A Skill per Task, and no Skill from
  elsewhere runs unchanged.
- **The Agent alone picks its Skills, with nothing on the Task**: a general-purpose Agent couldn't be narrowed per
  Task.
- **Tools on the Task too**: permissions spread over every element of a Process.
- **Limiting skills with permission rules and one shared folder**: claude has no allow rule for skills (probe 3), and a
  denied Skill still costs context.
- **Reading allowed Skills as files**, for both CLIs: no model invocation, no skill base directory, and a plain Read in
  the Transcript where a `Skill` call would show which one ran. Kept only as codex's fallback.
- **A shared Assignment file or an Assignment catalogue**, so a fix reaches every copy: it brings back a second kind of
  Skill. Assignments are a few lines about *what*; the shared *how* stays in the Skill, and Templates copy as they
  always have.
- **Reading the old format forever** instead of migrating, or a Check that refuses it: the old shape would live on in
  the launcher, or every user Process would break at once.

## Consequences

- `$HOME/.agents/skills` from the user's real home still reaches a codex Agent, as before; only a changed `HOME` would
  stop it.
- Whether a Skill made for a chat asks well in a Round depends on the Skill; the Round protocol in the hand-off
  bridges it, but each such Skill is worth one run before it ships.
- Agent names that just echo their Skill (`griller` runs `grill`) read oddly once Tasks carry the job; renaming them
  is left for later.
