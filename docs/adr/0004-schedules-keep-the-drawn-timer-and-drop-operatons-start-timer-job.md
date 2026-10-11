# A Schedule stays a timer start in the deployed BPMN; Ants stops Operaton from creating its start-timer job

The spec says that on deploy Ants strips the timer definition, so that Operaton sees a none start event and Ants'
own loop runs the Schedule. But the deployed XML is the only copy of the Process: the modeler reopens it and Ants'
loop reads the Schedule's cron from it. If Ants stripped the timer, the Schedule would be lost. So Ants deploys the
timer start as drawn, and its parse listener (`RunLifecycle`) clears the process definition's `timerStart`
property. That property is the only place `BpmnDeployer` reads start-timer declarations from, so no timer job is
ever created. Operaton still starts the Process from its single start event when asked (`startProcessInstanceById`),
so **Start** works the same as for a none start.

## Considered Options

- **Strip the `timerEventDefinition` on deploy, as the spec says, and keep the cron somewhere else** (an
  `ants.schedule` property on the start event, or the drawn file as an extra deployment resource). This takes more
  code, puts the Schedule in two places, and the modeler would have to put the timer back on open.

## Consequences

- An Operaton upgrade that reads start timers from anywhere else would bring back engine-run Schedules.
  `ScheduleTest` checks that a deployed Schedule leaves no job.
- **Remove** on the Process page deploys the next version of the Process with the timer taken off its start event.
