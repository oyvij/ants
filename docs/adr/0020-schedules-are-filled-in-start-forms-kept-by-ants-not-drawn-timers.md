# Schedules are filled-in start forms kept by Ants, not drawn timers

A Schedule was a timer start event drawn in the modeler, one per Process, starting every Run with the start-form
fields' stored values and the Process's Profile (ADR 0004, ADR 0012). So a Process could run on a schedule with one
set of values only: "Ship from issues" couldn't run hourly on `app` and hourly on `api` without a copy per repo. #154
makes a Schedule what the start form says, at given times instead of now: **Schedule** beside **Start** saves
everything filled in (the fields, Repos picked from the Source map, the Profile, the Name) with when to run, in
`schedules.yaml` in ANTS_HOME. A Process has any number of them and its diagram says nothing about them.

- **A Schedule keeps its own Profile**, as a Run does, so ADR 0012's "a Schedule uses its Process's Profile" no
  longer holds. A Profile removed since is a failed start, never a fallback.
- **It runs the latest version of its Process.** Each time goes through Start's checks again; values the Process no
  longer accepts (a new required field, a repo gone from the Source map) fail like a Run that can't start.
- **Drawn timers are migrated once** at startup: each becomes a Schedule with the stored values, the Process's
  Profile and its paused state, and the next version of the Process is deployed with the timer taken off. The
  modeler loses its Schedule UI, and a timer start arriving later in a file is a Check that refuses it. ADR 0004's
  parse listener stays as a guard, so Operaton still never creates a start-timer job.

## Considered Options

- **Keep reading a drawn timer as one more Schedule**, as #154 suggested: no migration, but two kinds of Schedule for
  good, and a timer in the modeler that the Process page can't change.
- **A saved start (a "preset") that may have a Schedule**: a second concept, page and list for values that never run
  on their own, which **Start** already covers.
