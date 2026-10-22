# A Run keeps its Verbosity, a Profile default, and the hand-off line names it

Everything Ants' Agents write for the user to read (Rounds, the Spec, Tickets, proposals, the Story set, Notes
proposals, Result summaries, stuck messages, PR bodies and the summaries in Reports) came at one level of wording.
The only way to change it was to edit Skills or Profile instructions, which changes every Run and can't stop a terse
Skill from dropping content. A Run now has a **Verbosity**: `brief`, `normal` (the behaviour before, and the default)
or `detailed`. It changes wording and explanation only, never content: a brief Round asks the same questions, a brief
Spec holds every decision, User Story and Testing Decision, and detailed adds reasoning and context but no scope or
decisions.

- **Each Profile has a default**, the optional `verbosity` key on a Profile in `settings.yaml`, picked beside the Save
  of the Profile's instructions modal on the Settings page, since both shape how its Runs' Agents write. A missing key, or a
  value that isn't one of the three, reads as normal, as does the implicit Profile of a setup with no Profiles.
- **A Run keeps its own.** Every start form has a Verbosity dropdown under Name, prefilled from the Profile the form
  shows, or normal with no Profiles. At start Ants sets `ants.verbosity` on every new top Run, normal included, and
  refuses a value that isn't one of the three. The Run reads its level once, like the copy of its Profile's
  instructions taken at start (ADR 0021), so changing the Profile's default mid-Run doesn't change it. A start that
  posts none, such as Set up Vault from Settings, gets its Profile's default. A Run without `ants.verbosity` reads as
  normal. A called Process uses its top Run's level.
- **A Schedule keeps the value from its form**, among the values it stores (ADR 0020). One saved before Verbosity
  existed starts each Run with none, so the Run gets its Profile's default at that start.
- **The levels are defined once,** in the shipped, read-only `shipped/verbosity/verbosity.md`, with the rules that hold
  at every level: never a change to content, to a Result's shape, a Round's JSON shape, the tracker's file format or
  the Story set's schema.
- **A Skill opts in** with `metadata.ants-verbosity: true`, and a Task gets Verbosity when its Skill or any Skill in its
  `ants-uses` closure opts in, the rule the Vault uses. Every shipped Skill opts in; for Matt Pocock's the key goes in
  Ants' own frontmatter, so his text stays word for word.
- **The hand-off line names it** only at brief or detailed: ` Write what the user reads at the <level> verbosity
  defined in <path>.`, after the Answer History sentence, and a claude launch gains `--add-dir` on
  `shipped/verbosity/`. codex gets the sentence and no flag, since it reads outside its working folder without one. At
  normal, and for a Skill that doesn't opt in, the hand-off line and the flags are exactly as before.
- **The Process Builder is left out**: no Verbosity, no hand-off sentence and no `--add-dir`.
- The Run page and the Report show the Run's Verbosity next to its Profile, and alone with no Profile; the Schedules
  table shows each Schedule's.

## Considered Options

- **Putting the level in Profile instructions**: free text that ranks below the Skill's own instructions, so it can't
  be relied on, and it can't differ per Run.
- **Writing each Skill three times**: three copies to keep in step, and Matt Pocock's text couldn't stay word for word.
- **Always adding the hand-off sentence, normal included**: a normal Run's prompt would change for nothing.
- **A per-Task toggle**, as the Vault and Answer History have: rejected in the Round, since the Skill's opt-in is
  enough.

## Consequences

- The hand-off line goes only into a session's first prompt, so a resumed session keeps the level from its first
  prompt; that holds because the Run's level never changes.
- Whether the real model writes tersely or in detail isn't tested; the user judges it on real Runs.
- codex reading the verbosity file without an `--add-dir` is worth one real codex check once codex is installed, as
  ADR 0021 notes for AGENTS.md.
