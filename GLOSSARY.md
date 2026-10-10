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

**Result**:
What an Agent hands back when its Task ends: either done, carrying what the Task promised, or stuck,
saying what it needs from the user.
_Avoid_: response, return value

**Transcript**:
Everything an Agent Task's Agent said and did across all its launches, readable while it works and
after the Run ends.
_Avoid_: log, session, output

**Round**:
One set of questions a Task asks the user at once, answered together on one page, after which the
Task resumes its session with the answers.
_Avoid_: batch, questionnaire

**Report**:
What a Run leaves behind once its Run workspace is gone: what was done and which repos it changed.
_Avoid_: summary, log

**Notification list**:
The one list where every Run posts a Task that is waiting on the user, each entry leading to that
Task.
_Avoid_: inbox, alerts
