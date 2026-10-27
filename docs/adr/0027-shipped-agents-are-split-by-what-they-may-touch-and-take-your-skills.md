# Shipped Agents are split by what they may touch, and take Your Skills

Ants shipped ten Agents, each cut to one job: answerer, architect, griller, implementer, knowledge,
knowledge-searcher, planner, pr, scout and storyteller. Since ADR 0024 the Task says *what* to do, so most of the ten
differed only in the job, which is the Task's. A user building a Process couldn't tell which one to pick, and a Skill
they imported couldn't reach a shipped Agent: shipped Agents are read-only, and a Task's `ants.skills` only narrows
its Agent's Skills, so the only way was a copy, which then missed every update Ants shipped. Now Ants ships four
Agents, split by what they may touch and whose voice they speak in, and the user adds Skills to them.

- **Four shipped Agents, by role, never by job**: **lead** reads code and writes documents in the Run workspace
  (`scout/`, `tickets/`, `proposal/`, `walkthrough/`) and runs read-only git; **engineer** changes the repos and
  opens pull requests; **proxy** answers a Round as the user would; **librarian** keeps the Vault. Their ids are
  fixed (`a-lead`, `a-engineer`, `a-proxy`, `a-librarian`, ADR 0025). knowledge-searcher stays, as a Subagent.
- **The job lives on the Task.** An Agent's standing instructions keep only what is about its tools and ways of
  working (no comments, only the Run workspace, never push). A line describing one job ("no code, no clones", "the
  smallest change, in the code's own terms", "change nothing in the repos") moves into the Assignment of each Task
  that does that job.
- **Every shipped Task names its Skills.** Each shipped Agent Task carries `ants.skills`, an empty list where it uses
  none, so what it gets never depends on how broad its Agent is: adding a Skill to an Agent changes no shipped Task.
- **Your Skills.** The user can add Skills, never tools, to any shipped Agent. They are kept in a list of their own
  in `ANTS_HOME`, by the Agent's id, outside the shipped folder and every folder Ants runs from, so an update replaces
  the shipped Agent and keeps the list. The list has a Draft and is published like any Agent (ADR 0026); only the
  published list is used.
- **A shipped Agent's Skills are its shipped list plus Your Skills**, counted once by id. The Catalogue answers what
  an Agent may use, and everything that asks (the launch, the codex hand-off, the modeler, the Checks, Used by) asks
  it.
- **Tools stay Ants'.** Adding a Skill grants no permission: a Skill whose scripts need Bash still can't run them on
  lead. Copy remains the way to change a shipped Agent's tools or instructions, and a copy misses updates by design.

## Considered Options

- **Keep the ten and add Your Skills to each**: the list still reads like a list of jobs, and the same Skill would be
  added to five Agents that may touch the same things.
- **One shipped Agent with every tool**: a Task that only reads would run with Bash and write access to the repos;
  what an Agent may touch is the one thing a Task can't narrow.
- **Let a Task's `ants.skills` add Skills its Agent lacks**: the Agent would no longer say what may run under it, and
  a Process copied from someone else could pull in any Skill.
- **Editable shipped Agents**: an update would have to merge the user's file with Ants', or drop one of them.

## Consequences

- The user's Processes, Templates and Runs in flight that name an old shipped Agent are moved to its new one at
  startup, with the Skills the old one had, and its level where it differs from the new one's, beside the ADR 0024
  migration; the user's own Agents are left alone. A Run in flight on an older version moves its Task the same way as
  it launches. The old nine are no longer shipped.
- A Task of the user's that lists no Skills gets its Agent's, Your Skills included, as before (ADR 0024).
