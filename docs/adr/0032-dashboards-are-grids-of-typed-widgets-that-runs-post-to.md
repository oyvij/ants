# Dashboards are grids of typed Widgets that Runs Post to

A Run's results end in its Report, so the user can't see what Processes find out over time, such as a daily news
digest or a chart of disk use. Ants gets **Dashboards**: grids of **Widgets** the user arranges, which Runs **Post**
to. The prototype in `docs/prototypes/dashboards/` is the guide for the UI and UX.

- **Widgets have fixed types.** Each type fixes the data it accepts: number, progress, status, list, text, log, chart
  (line, area, bar, stacked bar, scatter, pie, donut, all taking the same points) and custom chart (Vega-Lite with its
  data inside). The user never writes a schema, and a Post with the wrong shape is refused with a message the sender
  can fix. Ants never fails a Task because of a refused Post; the sender decides.
- **Everything links by a fixed id** (ADR 0025). A Dashboard and a Widget each get an id when they are made that
  renaming never changes and that is never reused. A Script lists the Widgets it Posts to in `script.yaml`, as it
  lists its Secrets; Publish refuses a list naming a deleted Widget, and each Widget shows what uses it.
- **The lead Posts, with a Skill.** No Agent is shipped for Posting, since shipped Agents are split by what they may
  touch, never by job (ADR 0027). The lead gets `PostToWidget` among its shipped tools and Ants ships a `dashboards`
  Skill; any other Agent gets a Permission request. Numbers a rule can compute belong in a Script Task.
- **Helpers suggest, then wait.** The Process Builder, the Template Builder and the script helper may create, rename
  and delete Widgets and Dashboards, but each change is one sentence the user answers in the chat. Ants refuses the
  change until the user has replied after the suggestion. Only Dashboard changes work this way.
- **No Draft or Publish.** A Dashboard keeps every change as it is made, since no Run uses its layout.
- **Kept in `ANTS_HOME/dashboards/<id>/`**: its title and layout in `dashboard.yaml`, each Widget's data beside it.
  Every Post remembers the Run and Task that sent it. Charts and logs keep 90 days by default; lists drop their
  oldest items past 100; a Post is refused above 256 KB. Test runs of a Script check a Post but never store it.

## Considered Options

- **An existing tool such as Grafana:** they read databases, take no Posts from agents, and add a second app to run.
- **Dashboards an agent writes as HTML:** flexible, but every one breaks in its own way when its data changes, and it
  runs model-written code in Ants' pages. Left for later, as one sandboxed Widget type.
- **A shipped Agent whose job is Posting:** against ADR 0027.
- **Draft Widgets, published with the Process:** a Widget is not something a Run uses, so it gets no Draft.
