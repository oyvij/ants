# Ants

Runs Processes drawn in BPMN, where each Task is an AI agent doing one job, while the user is away.

This file is the glossary and nothing else.

## Language

### Defining work

**Process**:
A BPMN diagram defining how agents and the user get one kind of job done, from a starting input to a
report.
_Avoid_: workflow (Varde's word for a folder of Skills run in order), pipeline, flow

**Path**:
One route through a Process, picked from a dropdown on its start form: Build and ship's Human in the loop,
Answered as me, Take the recommendations, Hands-off until review and Feeling lucky. A Path differs in who answers
the Rounds and where the Run first waits on the user.
_Avoid_: mode, variant, flavour

**Task**:
One step of a Process, reading the inputs it names and ending in a result the next steps read.
_Avoid_: job, step, activity

**Agent Task**:
A Task an Agent carries out by running one Skill.
_Avoid_: AI task, service task

**User Task**:
A Task the user carries out on a page, such as answering a Round or approving.
_Avoid_: human task, manual step

**Built-in Task**:
A Task Ants carries out itself, with no Agent and no user, such as cloning repos.
_Avoid_: system task, service task, Ants task

**Check**:
One thing **Save and deploy** refuses in a Process, such as an element Ants doesn't run, naming the
element; a warning is a Check that doesn't refuse.
_Avoid_: lint, validation error, rule

**Agent**:
A reusable definition of who does a Task: which AI CLI it runs, which tools it may use and its
standing instructions.
_Avoid_: worker, bot, ant

**Skill**:
A `SKILL.md` saying how to do one job; the instructions a Task hands its Agent.
_Avoid_: prompt, task instructions

**Template**:
A named piece of a Process, kept in a library, that is copied into another Process and changed there;
editing a Template never changes the Processes it was copied into.
_Avoid_: pattern, snippet, fragment

**Schedule**:
When a Process starts Runs on its own, such as every night at midnight; it says only when, so a Process can have
one only when every required start-form field has a stored value.
_Avoid_: cron, timer, trigger

### Running work

**Run**:
One execution of a Process, including any Processes it calls, from its starting input to its end;
many Runs of one Process may be live at once. Every Run has a name, given on the start form or else its
Process and start time ("Build and ship, 12 Oct 21:40"), which the user can change; Ants shows it wherever the
Run shows, and its id, the engine's UUID, only small on the Run page.
_Avoid_: instance, job, execution

**Pre-Run check**:
What the Process page runs before **Start**: the Checks again, plus the settings, logins, CLIs and
remotes a Run needs. It shows **Ready** or **Can't start** with every reason, and a Run never starts
while it has one.
_Avoid_: preflight, validation

**Run workspace**:
The folder a Run works in, holding its clones and intermediate files, deleted when the Run ends, or kept
until the user dismisses it if the Run failed. Named after the Run as it started, `runs/<slug>-<uuid>/`.
_Avoid_: sandbox, scratch dir

**Run log**:
What the user and the answerers told a Run's Tasks, in order: every Round, every instruction to a stuck Task and
every message sent into a session; kept in the Run workspace.
_Avoid_: history, audit log, journal

**Source map**:
A list, configured in Ants, giving each repo a short name and saying where it lives, so Processes
and Tasks can name repos by that name.
_Avoid_: repo list, registry

**Vault**:
The user's store of knowledge Notes, kept across Runs and shared with Varde; only a Skill that opts in
is told where it is.
_Avoid_: knowledge base, wiki, memory

**Profile**:
A named pair of one Vault and one Source map, such as `work` and `private`, one of them the default. A Process
can name its Profile and the start form picks the Run's; the Run then uses that Vault and Source map alone,
whether or not its repos are picked.
_Avoid_: group, context, account

**Secret**:
A value the user gives Ants, such as a registry token, under one or more variable names, that Protected Commands use
and Agents never see.
_Avoid_: credential, env var, key

**Protected Command**:
A command prefix, such as `yarn` or `mvn deploy`, that Agents may ask Ants to run with any arguments, in their Run
workspace, with the Secrets linked to it; the Agent gets its output, never the Secrets.

**Process Builder**:
The AI in the modeler that builds and changes the open Process from what the user writes to it, in a
continuous dialog; it does no Task, so it is not an Agent, and it never deploys or starts a Run.
_Avoid_: ProcessBuilderAgent, modeler chat, assistant

**Build log**:
What the Process Builder keeps of a Process it has worked on: what the Process is for, the decisions
taken and what is left, read by the next dialog about that Process to carry on.
_Avoid_: chat history, session log, Transcript
_Avoid_: build, command, script

**Result**:
What an Agent hands back when its Task ends: either done, carrying what the Task promised, or stuck,
saying what it needs from the user.
_Avoid_: response, return value

**Transcript**:
Everything an Agent Task's Agent said and did across all its launches, readable while it works and
after the Run ends.
_Avoid_: log, session, output

**Checklist**:
The steps an Agent sets itself while it works a Task, each ticked off as it goes.
_Avoid_: task list, tasks, plan

**Subagent**:
A helper an Agent's AI CLI starts inside its own session to do part of a Task; not an Agent, and
never drawn in a Process.
_Avoid_: agent, child agent, sub-task

**Usage limit**:
How much the AI CLI's login may use in a window; an Agent Task that reaches it pauses, without the
user, and resumes after the reset.
_Avoid_: rate limit, quota

**Round**:
One set of questions a Task asks at once, answered together by the user, an Agent or the
recommendations, after which the Task resumes its session with the answers.
_Avoid_: batch, questionnaire, YOLO

**Answer History**:
Every question the user answered themselves, with their answer, kept across Runs and Processes so an
Agent can answer a Round the way the user would.
_Avoid_: answer log, memory

**Spec**:
What a Run is to build, written by an Agent and approved by the user where the Process draws an
approval; it holds the Run's Tickets and outlives the Run.
_Avoid_: PRD, plan, epic

**Ticket**:
One slice of a Spec, implemented by one Agent Task, with its status, its acceptance criteria and the
Tickets that block it; Agents move it along as they work, and the user can read and edit it.
_Avoid_: issue, story, card, task

**Story set**:
A guided reading of what a Run changed across its repos: stories that follow the code in the order it
runs, written for one review and never updated.
_Avoid_: tour, walkthrough

**Report**:
What a Run leaves behind once its Run workspace is gone: what was done and which repos it changed.
_Avoid_: summary, log

**Notification list**:
The one list of everything waiting on the user: a Task of any Run, or a scheduled Run that couldn't
start; each entry leads to it.
_Avoid_: inbox, alerts
