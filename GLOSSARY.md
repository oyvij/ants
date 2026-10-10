# Ants

Runs Processes drawn in BPMN, where each Task is an AI agent doing one job, while the user is away.

This file is the glossary and nothing else.

## Language

### Defining work

**Process**:
A BPMN diagram defining how agents and the user get one kind of job done, from a starting input to a
report.
_Avoid_: workflow (Varde's word for a folder of Skills run in order), pipeline, flow

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

**Agent**:
A reusable definition of who does a Task: which AI CLI it runs, which tools it may use and its
standing instructions.
_Avoid_: worker, bot, ant

**Skill**:
A `SKILL.md` saying how to do one job; the instructions a Task hands its Agent.
_Avoid_: prompt, task instructions

**Schedule**:
When a Process starts Runs on its own, such as every night at midnight; it says only when, so a Process that needs
input from the user can't have one.
_Avoid_: cron, timer, trigger

### Running work

**Run**:
One execution of a Process, from its starting input to its end; many Runs of one Process may be
live at once.
_Avoid_: instance, job, execution

**Run workspace**:
The folder a Run works in, holding its clones and intermediate files, deleted when the Run ends, or kept
until the user dismisses it if the Run failed.
_Avoid_: sandbox, scratch dir

**Source map**:
A list, configured in Ants, giving each repo a short name and saying where it lives, so Processes
and Tasks can name repos by that name.
_Avoid_: repo list, registry

**Vault**:
The user's store of knowledge Notes, kept across Runs and shared with Varde; only a Skill that opts in
is told where it is.
_Avoid_: knowledge base, wiki, memory

**Result**:
What an Agent hands back when its Task ends: either done, carrying what the Task promised, or stuck,
saying what it needs from the user.
_Avoid_: response, return value

**Transcript**:
Everything an Agent Task's Agent said and did across all its launches, readable while it works and
after the Run ends.
_Avoid_: log, session, output

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

**Report**:
What a Run leaves behind once its Run workspace is gone: what was done and which repos it changed.
_Avoid_: summary, log

**Notification list**:
The one list where every Run posts a Task that is waiting on the user, each entry leading to that
Task.
_Avoid_: inbox, alerts
