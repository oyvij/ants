# A Setup keeps ids across Ants instances

A **Setup** (Export and Import on the Settings page) moves what the user made to another Ants or shares it with
someone else's. Agents, Skills, Scripts, Dashboards and Widgets keep their fixed ids (ADR 0025) inside a Setup, and
Import keeps them, although importing a single Agent today clears its id. Processes link to what they use by id,
so a Process arrives working, and a second Import of the same item is recognised as a newer copy: the preview shows it
as **Replaces**, not as a new item beside the old one. An item with the same name but another id is a **Clash**,
skipped unless the user renames it, and keeps its own id.

## Considered Options

- **Fresh ids on Import**, as the single-Agent import does: links would fall back to names and break on any rename,
  and every re-import of a shared Process would duplicate it.
- **Match by name only:** a renamed item would no longer be recognised, and two users' unrelated Agents both called
  "Reviewer" would overwrite each other.

## Consequences

- The id is a promise across instances: a new Ants must never mint an id that can collide with one from elsewhere,
  so ids stay random, never sequential.
- A Setup from a stranger can name an id you already use for something else; the preview shows it as Replaces, so
  the user reads the preview before confirming.
