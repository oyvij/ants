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
One step of a Process that an Agent carries out by running one Skill on the inputs it names, ending
in a result the next step reads.
_Avoid_: job, step, activity

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
The folder a Run works in, holding its clones and intermediate files, deleted when the Run ends.
_Avoid_: sandbox, scratch dir

**Report**:
What a Run leaves behind once its Run workspace is gone: what was done and which repos it changed.
_Avoid_: summary, log

**Notification list**:
The one list where every Run posts a Task that is waiting on the user, each entry leading to that
Task.
_Avoid_: inbox, alerts
