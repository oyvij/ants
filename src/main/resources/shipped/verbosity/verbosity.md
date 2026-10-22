# Verbosity

How much you write for the user to read. The hand-off line names the Run's level: write what the user reads at it.

## brief

Terse. Leave out background the user can be expected to know, and say each thing once.

## normal

No change: write as your Skill says.

## detailed

Add reasoning and context: why you decided what you did, what you weighed, and what it rests on.

## At every level

- Verbosity changes wording and explanation only, never content.
- Keep the same questions, decisions, User Stories, Testing Decisions and acceptance criteria as at normal.
- Detailed adds no scope and no decisions.
- Verbosity never changes a Result's shape: the Result `summary` stays one sentence. Nor does it change a Round's JSON
  shape, the tracker's file format or the Story set's schema.

## What it covers

Rounds, the Spec, Tickets, proposals, the Story set, Notes proposals, summaries, stuck messages, PR bodies and Reports'
summaries.
