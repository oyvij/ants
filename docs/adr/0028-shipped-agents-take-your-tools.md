# Shipped Agents take Your tools

This supersedes ADR 0027's "Tools stay Ants'"; the rest of it stands.

ADR 0027 kept a shipped Agent's tools Ants', so a copy was the only way to give `lead` or `proxy` one more tool, and
a copy misses every update Ants ships. The user had to choose between the tool and the updates, as they once did for
Skills.

- **Your tools.** The user adds tools to any shipped claude Agent, with the same tick boxes and Limits as their own
  Agents. They are kept in a list of their own in `ANTS_HOME` (`your-tools/`), by the Agent's id, beside Your Skills
  and outside the shipped folder and every folder Ants runs from, so an update replaces the shipped Agent and keeps
  the list. Your Skills and Your tools share one Draft and one Publish on the Agent's page (ADR 0026); only the
  published lists are used.
- **A shipped Agent's tools are its shipped tools plus Your tools**, counted once. Your tools only add: `lead`'s
  `Bash(git log *)` plus `Bash(cargo *)` allows both, and every deny rule Ants adds (`secrets.yaml`, `settings.yaml`,
  a Protected Command's prefix, `git … --output`, a Subagent type outside an `Agent(…)` Limit) still wins.
  `ProtectedCommand` is a tool like any other here. The Catalogue answers it, so the launch and its Subagents' tools
  get the same answer.
- **The Agent's promise stays visible.** Where Your tools widen a shipped Agent, its description says so ("… Plus your
  tools: Bash(cargo *).") wherever it is picked or shown: the Agents list and the modeler's Task panel, so a Process
  that picks `proxy` for being read-only shows that it no longer is.

## Considered Options

- **Keep Copy as the only way**: the copy misses every update, which is what Your Skills was made to end.
- **Let Your tools narrow too**: a shipped Agent's promise would then depend on the install, and an update adding a
  tool it needs couldn't reach it.
- **A Draft of its own for Your tools**: two Drafts and two Publish buttons on one page for what the user edits as one
  change.

## Consequences

- Instructions stay Ants': Copy remains the way to change them.
- A copy of a shipped Agent carries its Your tools, as it carries its Your Skills.
