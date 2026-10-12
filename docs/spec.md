# Spec: Ants v1

## Problem Statement

I hand AI agents real work: grilling me on an idea until it is clear, writing it up as a Spec, cutting it into Tickets, implementing them, reviewing, pushing and opening the pull request. Today I drive each of those steps by hand, one `claude` session at a time. I'm the one copying context between them, and I'm there for every step. Nothing runs while I sleep. I can't see at a glance which job is waiting on me. And every repeatable way of getting a job done lives only in my head and in loose scripts.

I want to draw how a kind of job gets done once, as a Process, then start a Run with a prompt and walk away. Agents should do the steps they can. The Run should wait for me only where I said it must, or where an Agent is truly stuck, and it should come back with a pull request and a Report. All of this has to run on my own Claude subscription, on my own machine, and keep going overnight.

## Solution

Ants is a standalone, single-user service on the user's machine. It runs Processes drawn in BPMN on an embedded Operaton engine. Each Agent Task runs one Skill through the unmodified AI CLI (`claude -p`, or `codex exec`) on Ants' own subscription login, never API tokens. Ants has one web UI and no other: drawing Processes, administering Agents, Skills and Templates, starting and watching Runs and their Transcripts, answering Rounds, approving, reviewing and pushing, the Tickets page, settings, and the Notification list of everything waiting on the user.

Ants ships five default Processes, each turning a prompt into a pull request:

- **Ship with me**, **Ship by proxy** and **Ship as proposed** run scout → grilling loop → to-spec → the user approves the Spec → to-tickets → implement each Ticket → walkthrough → Review changes → Push → Open pull request → Update the Vault. They differ only in who answers the grilling Rounds: the user, an Agent, or the recommendations.
- **Ship overnight** is the same, unattended up to Review changes.
- **Ship small** runs scout → propose a solution → implement → Push → Open pull request → Update the Vault.

Review changes, pushes and Vault writes always wait for the user. The Spec is approved wherever a Process draws an approval. A sixth shipped Process, **Set up Vault**, creates a Vault. The default Processes are built only from building blocks a user can draw.

## User Stories

### Installing, starting and updating

1. As the user, I want to install Ants with one `curl … install.sh | bash`, so that I never install a JVM or a package by hand.
2. As the user, I want the installer to verify the download against published checksums, so that I know the tarball wasn't tampered with.
3. As the user, I want `ants start | stop | restart | status | logs | run | rollback | password` in my terminal, so that I control the service without the UI.
4. As the user, I want Ants to run as a launchd agent that survives closing the terminal, crashes and logging out and in, so that Runs keep going overnight.
5. As the user, I want a second `ants start` to tell me Ants is already running and where, so that two instances never fight over the database.
6. As the user, I want Ants to create `~/.ants` on first start with no wizard, so that the settings page and the pre-Run check tell me what's missing.
7. As the user, I want Ants to detect `claude`, `git`, `codex` and `gh` through my login shell, and to offer a **Detect** button, so that it finds the tools I already use.
8. As the user, I want to install the web UI as an app (a PWA) with its own window, a Dock icon and a badge showing the Notification list count, so that Ants feels like an app without a second program.
9. As the user, I want Ants on `127.0.0.1:7323` behind a single-user login, so that nothing else on my machine or network drives my Agents.
10. As the user, I want Ants to refuse a non-loopback bind while the password is still the default, so that I can't expose it by accident.
11. As the user, I want the nav to show when a newer version is available and to offer **Update**, so that I stay current without leaving the UI.
12. As the user, I want an update to take effect only at the next restart, with the previous version kept for `ants rollback`, so that an update never kills a running Run behind my back and a bad one is easy to undo.
13. As the user, I want a Docker image of Ants, configured from the environment, with one data volume and a required password, so that I can move Ants to a server later.

### Logging in and settings

14. As the user, I want Agents to run on Ants' own Claude config folder and login, not my work login, so that the employer's Team seat isn't spent and my own hooks and plugins don't leak into Tasks.
15. As the user, I want the settings page to show the login status and the exact terminal command to log in, so that Ants never handles my credentials.
16. As the user, I want Ants to strip `ANTHROPIC_API_KEY` from every Agent's environment and check that no API key is in use, so that I'm never billed per token.
17. As the user, I want to set the command prefix and the environment allow-list per CLI (for example running `claude` inside the nono sandbox), so that sandboxing stays my machine's concern.
18. As the user, I want to point Ants at Varde's `config.toml` to reuse its command and env, so that I set up sandboxing once.
19. As the user, I want named lists of Vaults and Source maps with a default each, so that every Process can pick its own.
20. As the user, I want to list extra Agent folders and Skill folders, such as Varde's, so that Tasks can use Agents and Skills I already have.
21. As the user, I want to set how many Agent Tasks run at once (default 3), so that I control the load on my subscription.
22. As the user, I want to see the latest 5-hour and 7-day usage next to the login, so that I know how close I am to the Usage limit.
23. As the user, I want settings kept in one hand-editable `settings.yaml`, so that I can fix or back them up outside the UI.
24. As the user, I want dark mode that follows the system and then remembers my choice, so that the UI is comfortable at night.

### Drawing Processes

25. As the user, I want a full-height modeler page in Ants, built on bpmn-js, so that I draw Processes where they run.
26. As the user, I want the palette trimmed to the elements Ants runs, each with a tooltip, so that I never draw something that can't run.
27. As the user, I want hovering an element to offer "append Agent / User / Built-in Task", so that I can build a Process quickly.
28. As the user, I want selecting an element to explain it, and a plain Task to ask what it should be, so that I learn the building blocks as I draw.
29. As the user, I want an Agent Task to pick an Agent from a live dropdown, then one of the Skills that Agent lists, so that I never type a name by hand.
30. As the user, I want picking a Skill to fill in one input per name it declares, so that I see what it needs.
31. As the user, I want input pickers that offer only start-form fields, Run variables and Results of earlier Tasks, so that my references make sense.
32. As the user, I want a condition builder on exclusive gateways (a Result field, an operator, a value, one default path), with the expression shown, so that I route without writing JUEL.
33. As the user, I want live Checks that list everything **Save and deploy** would refuse, and jump to the element when I click one, so that I fix problems while I draw.
34. As the user, I want **Save and deploy** disabled while any Check fails, so that a broken Process never reaches the engine.
35. As the user, I want imported BPMN files checked the same way, with nothing converted silently, so that I know what won't run.
36. As the user, I want **＋ New Agent** and **＋ New Skill** in a modal, with **Create and use**, so that I can make a missing building block without leaving the diagram or losing my place.
37. As the user, I want **New Process** to start blank or as a copy of any existing Process, so that I can begin from the shipped defaults.
38. As the user, I want to declare start-form fields (Text, Markdown or Repos; required; help text), so that each Run asks for exactly the input it needs.
39. As the user, I want a Repos field to be a multi-select of Source map names, so that I can pick a Run's repos by hand.
40. As the user, I want each Agent Task to have Vault and Answer History toggles that default from its Skill, so that I decide what context it gets.
41. As the user, I want a "resume its session when the flow comes back" setting on any Agent Task, so that loops build on the whole conversation.
42. As the user, I want to override a Task's result schema in the modeler, so that an unmodified Skill can still answer in JSON.
43. As the user, I want to call another Process as one step (a call activity) and fill its start-form fields like Task inputs, so that a fix to the called Process reaches every caller.
44. As the user, I want Checks to catch a call cycle, a required called field left empty, and a renamed Task that a caller reads, so that composition never breaks silently.
45. As the user, I want to run an Agent Task, sub-process or call activity as a parallel or sequential multi-instance over a list from an earlier Result, so that I can fan out work.
46. As the user, I want an **Items are Tickets** tick box on a multi-instance, so that instances honour the Tickets' Blocked by.
47. As the user, I want to drop a Template into my Process as a collapsed sub-process that keeps its ids, so that references between Templates keep working.
48. As the user, I want my own Templates and a Templates page, plus a searchable Templates section in the palette, so that I reuse pieces of Processes.
49. As the user, I want interrupting timer boundaries on User Tasks (for example "no answer by 08:00 → Accept recommendations"), so that a Run doesn't wait on me forever when I don't want it to.
50. As the user, I want intermediate timers, parallel gateways and embedded sub-processes, so that I can wait, fan out and group.

### Schedules

51. As the user, I want to give a Process a Schedule in words (every hour, day, weekday, week or month at a time, cron under Advanced), so that it starts Runs on its own.
52. As the user, I want a Schedule only when every required start-form field has a stored value, so that a scheduled Run never needs me to start.
53. As the user, I want a time missed while Ants was off to run once when it's back, so that I neither lose a Run nor get a burst of them.
54. As the user, I want a scheduled time skipped while the last Run from that Schedule is still going, with a note on the Process page, so that Runs never pile up.
55. As the user, I want a scheduled Run that couldn't start to appear on the Notification list with **Start now** and **Dismiss**, so that I notice.
56. As the user, I want to pause and remove a Schedule, and to see what's coming up on Home, so that I control what runs while I'm away.

### Starting Runs

57. As the user, I want the Process page to show **Ready** or **Can't start** with every reason, and **Start** disabled until it's ready, so that a Run never fails on something checkable.
58. As the user, I want the pre-Run check to cover names, schemas, references, `ants-uses`, CLI commands, Source map names, Vault and Source map paths, the Claude login, `gh auth status`, `git ls-remote` and custom model IDs, so that the things I can fix up front get fixed.
59. As the user, I want a missing Vault to show only **Configure a Vault**, linking to settings, so that the fix is one click away.
60. As the user, I want many Runs of one Process live at once, starting freely while only their Agent Task launches queue, so that starting is never blocked.
61. As the user, I want every Run to work in its own Run workspace with fresh clones of its repos on a work branch, so that my checkouts are never touched.
62. As the user, I want the work branch from the start form, else the scout's suggestion, else `ants/<run-id>`, with a counter suffix when a live Run holds it, so that Runs never collide.

### Watching Runs

63. As the user, I want a Runs list and a Home dashboard (stats, Waiting on you, Working now, Coming up, Start a Run), so that I see everything at a glance.
64. As the user, I want the Run page to show its live diagram, with Tasks coloured by state, badges and Round numbers, so that I see where the Run is.
65. As the user, I want clicking a call activity to open the called Process's live diagram in place, with a breadcrumb back, so that I can follow a composed Run.
66. As the user, I want clicking a Task to open a drawer with a **So far** paragraph, the Agent's task list, the latest output, a comment box and the action a waiting Task needs, so that I understand it without reading everything.
67. As the user, I want every running Agent Task shown with a spinner, an activity verb, elapsed time and rising tokens, so that I can tell it's alive.
68. As the user, I want live updates that patch only what changed and never redraw the page I'm in, so that I never lose a form, an open modal or the modeler.
69. As the user, I want a Transcript per Agent Task, rendered close to Claude Code (prompt collapsed, streamed markdown, one line per tool call, thinking collapsed, Ants' events as system lines, the final Result), so that I can follow the Agent as I would in Claude Code.
70. As the user, I want one Transcript across all of a Task's launches, with a divider for each launch, so that I see the whole history.
71. As the user, I want token usage per launch, per Task and per Run, so that I know what a Run cost my subscription.
72. As the user, I want the Transcript to survive reloads, second tabs and the end of the Run, so that I can read it later from the Report.
73. As the user, I want to **Send** a message into a running Agent's live session, so that I can steer it mid-task.
74. As the user, I want **Stop** to interrupt an Agent and leave the Task on the Notification list as `[stopped]`, with **Continue the session** or **Start fresh**, so that I can take over.
75. As the user, I want Subagents shown as a `⑂ n` count, live chips with type, description and time, and nested blocks in the Transcript, so that I see the parallel work inside a Task.
76. As the user, I want an **Offer as a Subagent** tick box on an Agent, off by default, so that I decide which of my Agents other Agents may start as Subagents.
77. As the user, I want queued Tasks to say "Queued: 3 of 3 Agent Tasks running", and Tickets held by blockers to say "Blocked by 01", so that I know why nothing is happening.

### The Notification list

78. As the user, I want one Notification list of everything waiting on me, one entry per waiting Task, oldest first, with tag, Process, Run, Task, summary and how long it has waited, so that I clear the longest block first.
79. As the user, I want a live count in the nav, the tab title and the app badge, so that I notice new entries.
80. As the user, I want an entry to disappear the moment I act on it, with no read state and no manual dismiss, so that the list is always true.
81. As the user, I want to land on the next entry from the same Run right after I act, so that chains like Review changes → Push flow on.
82. As the user, I want a `[stuck]` page showing what the Agent needs, a link to the Transcript and a text area, with **Continue the session** (instruction required) and **Start fresh** (instruction optional), so that I unblock it the way that fits.
83. As the user, I want a `[failed]` page showing the reason and the last stderr lines or the invalid Result, with the same two choices plus **Cancel the Run**, so that I can recover or give up.
84. As the user, I want a lost login to fail as "Ants' Claude login is missing or expired. Log in again (Settings)", so that I know exactly what to fix.
85. As the user, I want a cancelled or failed Run to keep its Run workspace until I dismiss it, so that no commits are lost.

### Rounds and answers

86. As the user, I want a Round to be answered on one page, one question at a time under a tab bar marking untouched, accepted or edited, with a **Review and send** tab, so that I answer carefully without losing track.
87. As the user, I want each question to show what it asks, what it settles, the recommendation and why, with the recommendation prefilled, so that accepting is quick and changing it is easy.
88. As the user, I want the page to behave like a normal web form, with the buttons fixed at the bottom and never moving, so that I don't mis-click.
89. As the user, I want the asking Task to resume its session with my answers, so that the next Round builds on the whole conversation.
90. As the user, I want to draw who answers a Round: me, an answer-round Agent that reads my Answer History and then the Vault, or Accept recommendations, so that I choose how much I'm involved.
91. As the user, I want every answer stamped `accepted`, `written`, `agent` or `recommended`, and the Report to list the answers I didn't give separately, so that I see what was guessed.
92. As the user, I want my own answers kept in an Answer History across Runs, never mixed with Agent or recommended ones, so that an answering Agent learns how I answer and never learns from itself.
93. As the user, I want a loop that nobody paces to hand itself back to me after a cap (Round 10 in the defaults), or to end there in Ship overnight, so that unattended grilling can't run forever.
94. As the user, I want every Round, instruction and message logged to the Run workspace, so that to-spec and the Report see the whole conversation.

### Approving

95. As the user, I want an Approve User Task that shows a Title and a file or Result rendered as markdown, with **Approve** and **Send back** (comment required), so that I gate any step.
96. As the user, I want Send back to resume the named Task's session with my comment, so that it revises rather than restarts.

### Specs and Tickets

97. As the user, I want to-spec to write the Run's Spec and to-tickets to cut it into Tickets as files in the Run workspace, so that the Spec and its Tickets are plain markdown I own.
98. As the user, I want a Tickets page listing every Spec across Runs, open first, with status, `n/m done` and the Run, so that I can find past work.
99. As the user, I want Spec and Ticket pages rendered GitHub-style, with ticking checkboxes, Blocked by as links, Comments and **Edit**, so that I can read and change them during the Run.
100. As the user, I want Save to refuse when an Agent changed the file since I opened it, and show the new version, so that I never overwrite an Agent's edit.
101. As the user, I want Specs and Tickets kept after the Run ends, beside the Report, so that they outlive the Run.
102. As the user, I want a Ticket marked `ready-for-human` to skip the implementers and put an entry on my Notification list, its dependents waiting until I mark it done, so that I can take the parts I want.
103. As the user, I want implementers to move their own Tickets (`in-progress`, `needs-info`) and Ants to set `in-review` on landing and `done` on Review changes Approve, so that a Ticket's status is always true.

### Implementing

104. As the user, I want each Ticket implemented by its own parallel instance in its own worktree, branched from the work branch head when it launches, so that parallel implementers don't collide.
105. As the user, I want each finished instance landed on the work branch one at a time, all repos or none, with linear history, so that dependents see their blockers' commits and Push can fast-forward.
106. As the user, I want a conflicting landing to resume the instance's session to resolve it (two rounds, then `[failed]`), so that conflicts are fixed by the Agent that knows the change.
107. As the user, I want every implementer to build with tdd and review its own commits with Standards and Spec Subagents before it's done, summarising the findings under `review`, so that I review better work.
108. As the user, I want a stuck instance to keep its worktree, so that Continue resumes where it was, while Start fresh branches anew.

### Reviewing, pushing and opening pull requests

109. As the user, I want Review changes to show a Story set (guided stories that follow the code across repos) and a By repo view of commits and diffs, so that I can review a multi-repo change.
110. As the user, I want to comment on story steps and diff lines with ISSUE, NOTE, SUGGESTION or COMMENT, so that the fixer gets precise feedback.
111. As the user, I want Send back to resume one fix Task with my comments, then rewrite the Story set whole against the new heads, so that what I review is never stale.
112. As the user, I want Ants to check a Story set mechanically and have the walkthrough fix any failing steps, so that every story points at real code.
113. As the user, I want the fixer to comment on each Ticket it touched, so that each Ticket records its fixes.
114. As the user, I want Push to wait on its own page showing each repo's remote, branch, base and commits, with **Push** or **Cancel the Run**, so that nothing leaves my machine without my say-so.
115. As the user, I want Push to dry-run every repo first, then fast-forward the work branch only, never force and never to the base, so that a push is safe.
116. As the user, I want a rejected push to name which repos pushed and which didn't, with git's message, and let me push by hand from the kept clone and Continue, so that a partial push is recoverable.
117. As the user, I want Agents never able to push, so that Push is the only way out.
118. As the user, I want a pr Agent Task to open the pull request on GitHub with a body written from the commits and the review, so that my reviewers get context.

### Knowledge

119. As the user, I want Ants to read and write my Vault in Varde's format, so that one Vault serves both tools.
120. As the user, I want only Tasks whose Skill opts in (or whose toggle is on) told where the Vault is, so that knowledge stays opt-in.
121. As the user, I want every Vault write proposed first and written only after I approve it, even in Ship overnight and Ship small, so that nothing enters my Vault without me.
122. As the user, I want Update the Vault to run after the pull request opens and to learn from the Round log, the Spec and the review, so that the pull request is never delayed and the Vault learns from the whole Run.
123. As the user, I want a **Set up Vault** Process that runs init-vault's decisions as a Round and then approves the rule Notes, so that setting up a Vault is itself a Run.

### Agents, Skills and Templates

124. As the user, I want Agents and Skills kept in Varde's formats (Claude Code agent files, `SKILL.md` folders), so that a later Varde can list them.
125. As the user, I want an Agents page with a form that maps one-to-one to the agent file: name, description, cli, the Skills it runs, tools as tick boxes with **Limit…**, model, **Offer as a Subagent**, standing instructions, so that I don't edit YAML by hand.
126. As the user, I want the model dropdown to list current Claude models from Anthropic's public status table, plus aliases and **Other model ID…**, so that I pick a model that exists.
127. As the user, I want a Skills page that edits `SKILL.md` and `result.schema.json`, with the schema checked as I type, so that a Skill's promise is always valid.
128. As the user, I want shipped and outside Agents, Skills and Templates read-only, with **Copy** to make my own, so that updates never overwrite my changes.
129. As the user, I want to import one `SKILL.md` or Agent file into the create form, with unparseable or unsupported files refused with a reason, so that adding one is quick and safe.
130. As the user, I want **Used by** on every Agent, Skill and Template, and a warning listing those users before a rename or delete, so that I know what I'd break.
131. As the user, I want edits to Agents and Skills to reach live Runs at their next Task launch, so that I can fix a Skill mid-Run and Continue.
132. As the user, I want a Skill to use other Skills by name (`ants-uses`), resolved at any depth and checked for cycles, so that tdd and code-review are shared, not copied.
133. As the user, I want a clone's own Skills, hooks and MCP servers kept out of Task sessions while its `CLAUDE.md` still loads, so that a repo can't change what my Agents do.

### Usage limit and concurrency

134. As the user, I want an Agent Task that hits the Usage limit to pause by itself and resume its session after the reset, without bothering me, so that overnight Runs survive limits.
135. As the user, I want one paused Task to hold every ready Task on that CLI, so that Tasks don't each burn a request finding the limit.
136. As the user, I want a quiet nav pill like "claude paused until 15:45", with **Try now**, so that I can retry early.
137. As the user, I want ready Agent Tasks launched oldest first across all Runs, so that no Run starves.

### Reports

138. As the user, I want Ants to write a Report when every Run ends (the start form, each Task's summary, the Rounds and their sources, the Tickets, the review summaries, the Push and pull request Results, tokens and links to the Transcripts), so that I know what happened without reading Transcripts.
139. As the user, I want the Run workspace's clones deleted when the Run ends, but Specs, Tickets, Transcripts and the Report kept, so that disk stays bounded while the record stays.

## Implementation Decisions

### Platform (ADR 0001)

- One Kotlin process on Spring Boot 4 (Java 25, Gradle with Kotlin scripts), embedding Operaton through its Spring Boot starter, with the REST and webapp starters so Cockpit is there for debugging. Engine state is in file-backed H2 locally and Postgres on a server.
- The Task worker runs in-process. It fetches and locks external tasks through Operaton's Java `ExternalTaskService`; there is no REST between engine and worker. Lock, retries and incidents come from the engine.
- The web UI is rendered on the server with JTE, as normal forms. Vanilla JS appears only where a page needs it, and live updates use SSE. There is no SPA and no bundler. `camunda-bpmn-js` and CodeMirror 6 come from pnpm at build time, with Node used only by the build; their `dist/` goes into the static resources.
- Ships as an unsigned (ad-hoc) jlink tarball for macOS arm64, installed by `install.sh` into versioned folders with a linked `ants` launcher, plus a multi-arch GHCR image. CI releases on a major or minor version bump. There is no jpackage installer, no `.deb` and no systemd unit.
- `ANTS_HOME` (default `~/.ants`) holds everything Ants owns: `settings.yaml`, the lock file, logs, `shipped/`, the user's `agents/`, `skills/` and `templates/`, `claude/` and `codex/` config folders, `answer-history/`, and `runs/<run>/` (Report, Transcripts, kept `tickets/`, and the Run workspace while it lives). Nothing hard-codes `~/.varde`.
- Single-user login on every address. The password defaults to admin/admin, changed in settings or with `ants password`. A non-loopback bind is refused while the password is the default. Docker requires `ANTS_PASSWORD` or `ANTS_PASSWORD_FILE`, and its UI shows an available update without an **Update** button.
- The PWA has a manifest and an icon. The badge carries the Notification list count where the browser supports it.

### Process model and deploy (the Checks)

- Allowed elements: one start event (none or timer) per Process and per sub-process; none end; intermediate timer; exclusive and parallel gateways; Agent, User and Built-in Tasks; an interrupting timer boundary on User Tasks only; embedded sub-process; call activity; multi-instance (parallel or sequential) on Agent Tasks, sub-processes and call activities; text annotation and group. Everything else is a Check error naming the element, DMN and error events included, as is a multi-instance completion condition.
- An Agent Task is an external Service Task, topic `agent`, carrying extension properties `ants.agent`, `ants.skill` and `ants.resume`, plus the Vault and Answer History toggles (`ants.vault`, `ants.answer-history`; with neither set, the Skill's opt-in applies) and an optional result-schema override (`ants.result-schema`). Agents and Skills are referenced by name, never by path.
- A Built-in Task is an external Service Task whose topic names it (`clone-repos`, `accept-recommendations`, `push`).
- **Create and use** on a new Skill also adds it to the Task's Agent's `skills` when that Agent is the user's own; otherwise the Checks report that the Agent doesn't list it.
- Ants writes `operaton:` only, and rewrites any `camunda:` on the way in.
- **Save and deploy** goes through Ants. It refuses while any Check fails:
  - unknown or ambiguous Agent, Skill or `ants-uses` names, and `ants-uses` cycles;
  - a Skill the Agent doesn't list;
  - a missing or invalid result schema (a `${task.output.field}` read from a Task that promises none);
  - a `${task.field}` reference naming a Task or field that doesn't exist (a loop's element variable, `loopCounter` and `nrOf*` exist only on or inside that multi-instance);
  - a refused element;
  - an exclusive gateway with more than one path out and no default path;
  - a timer without a time;
  - a required call-activity field left empty, or a call activity calling a Process that isn't deployed;
  - a call cycle, or a Task a caller reads being renamed or removed (Saving a called Process runs its callers' Checks);
  - a Review changes **Send back resumes** naming a multi-instance Task;
  - a Schedule with a required start-form field that has no stored value, or whose times aren't six cron fields (seconds first) that Ants can read.
  
  An empty declared input is a warning. More complex JUEL fails at runtime as an incident.
- Results: every Task's Result is one process variable named after its BPMN id, a JSON map. At Run start Ants sets every Task-id variable to `null`, so forward references read `null`. A loop overwrites it, so the latest wins.
- Run variables Ants keeps: `runs` (each Task's run count in this Run, for caps such as `${runs.grill >= 10}`) `clones` (every repo cloned so far, `[{name, path, commit}]`) and `sourceMap` (the default Source map's repos, `[{name, remote}]`, read at Run start; ADR 0008).
- Call activity: the called Process runs inside the caller's Run and Run workspace, with one Report, the top Process's work branch, Vault and Source map, and Notification entries named after the top Run. Its start-form fields are its inputs, written as one `operaton:in` each. Its Result is every Task Result in the called Process, keyed by Task id. It is bound `latest`. A called Task's Transcript and Notification key is `<call-id>.<task-id>` (the call's key carries its `-<n>` when it is a multi-instance). The pre-Run check covers the Processes a Process calls.
- Multi-instance: the collection is an array field of an upstream Result, written as an expression that yields a Java Collection; each instance reads `${item}` (or its named element variable). Ants collects the instance Results itself, retrying on optimistic-locking clashes, into a list in instance order; an empty collection gives `[]`. A Task gives a list of its Results; a sub-process or call activity gives a list of `{taskId: Result}`, which in a sub-process includes its call activities' and inner multi-instances' Results. An instance's key is `<task-id>-<n>`, 1-based in item order, with one `-<n>` per multi-instance it runs in, outermost first (`draft-2-1`).
- Templates are small `.bpmn` diagrams in `~/.ants/templates` (the user's) and `~/.ants/shipped/templates` (read-only). Dropping one inserts a collapsed embedded sub-process that keeps the Template's ids, suffixed `_2`, `_3`, … only on a clash (an underscore, since `-` reads as subtraction in JUEL), with internal references rewritten, including the Task an **Asker**, **Send back resumes** or **Walkthrough** names (ADR 0009). Nothing links back after the drop.
- Schedules are run by Ants, not Operaton. On deploy Ants keeps the drawn timer start but stops Operaton creating its start-timer job (ADR 0004), and Ants' own cron loop starts Runs through the same path as **Start**, pre-Run check included. A missed time runs once. An overlapping time is always skipped and noted. A Run that can't start becomes a `[failed]` Notification entry, whose page has **Start now** and **Dismiss**. Times that pass while a Schedule is paused are dropped on Resume. **Remove** deploys the next version of the Process with the timer taken off its start event.
- Start-form field types: Text, Markdown, Repos. Each can be required and can have a stored value, used by Schedules and to prefill the start form. The variable name is derived from the label and can be changed. The default Processes ask for `prompt` (required) and a work branch the user may leave for the scout to name.

### Agent launcher (the worker)

- **Fetching:** one global cap on live CLI processes (`settings.yaml`, default 3), across all Runs and CLIs. The worker fetches only free slots, oldest ready external task first. Every launch takes a slot: first run, Continue, Start fresh, resume after a Round, a Send back or the reset, and **Try now**. Subagents run inside their Task's slot.
- **Holds before launch:** a paused CLI gate (see Usage limit) and, for **Items are Tickets** instances, a Ticket whose `Blocked by` isn't yet `in-review` or `done`. With **Items are Tickets**, each item names its Ticket by path or number (`tickets/issues/01-hello.md` or `01`). A `ready-for-human` Ticket's instance is held until the user marks it done, then completes without a launch.
- **Agent files** are Claude Code's subagent format: `name`, `description`, `cli` (claude or codex, default claude), `skills` (the Skills a Task may pick; an Agent without `skills` lists none), `tools`, `model`, and the standing instructions as the body. The body goes to `--append-system-prompt`, `model` to `--model`, and `tools` to an explicit `--tools` / `--allowedTools` allow-list. A file without frontmatter is an Agent with defaults, named after the file. Codex gets a sandbox setting instead of tools: the agent file's `sandbox` (`read-only`, `workspace-write` or `danger-full-access`, default `workspace-write`) goes to `--sandbox`; `model` goes to `-m`, and the body to `-c developer_instructions=…`.
- **Agents as Subagents:** an Agent with `ants-subagent: true` (the **Offer as a Subagent** tick box, off by default) is passed to every claude launch through `--agents`, so it's available as a Subagent type. The Agent tool's **Limit…** (by Subagent type) narrows which ones a given Agent may start; Ants enforces it by denying every other built-in or offered type (`--disallowedTools Agent(<type>)`), since claude starts any Subagent type that isn't denied. `--agents` is passed only to a Task whose tools include the Agent tool. Only `cli: claude` Agents can be offered; ticking it on a codex Agent is a Check error. A Subagent runs inside its Task's process, so the Task's permission mode, working directory and `--add-dir`s still bound it.
- **The command** is the CLI's command prefix from settings with Ants' headless flags appended. The environment is the settings allow-list, which Ants may narrow but never widen. Ants always strips `ANTHROPIC_API_KEY`, `OPENAI_API_KEY`, `CODEX_API_KEY` and `CLAUDE_CODE_RETRY_WATCHDOG`, and always sets `CLAUDE_CONFIG_DIR` (`CODEX_HOME` for codex), `CLAUDE_CODE_DISABLE_BACKGROUND_TASKS=1` and `CLAUDE_CODE_MAX_SUBAGENT_SPAWN_DEPTH=1` (the `subagent-depth` setting raises the depth).
- **Flags for claude:** `-p`, `--output-format stream-json --verbose --include-partial-messages`, `--input-format stream-json --replay-user-messages`, `--json-schema`, `--session-id <uuid>` set up front (`--resume` later), `--permission-mode dontAsk`, `--setting-sources user,project`, `--settings` with `disableAllHooks`, `--strict-mcp-config`, `--disable-slash-commands`, `--forward-subagent-text`, `--add-dir` for the Vault (when on) and for each used Skill's folder, and the Run workspace as working directory. `Skill` is never in the allow-list. Guard against `--bare` becoming the `-p` default, since it would never read the login.
- **Flags for codex:** `exec --json --skip-git-repo-check`, `--output-schema` with the Result schema written to a file beside the Transcript, `--sandbox`, `-m` and `-c developer_instructions=…` from the Agent, `--add-dir` for the Run workspace from an instance's folder and for the Vault (when on), then `resume <session>` when resuming, and `-` so codex reads the prompt from stdin. codex names a new session itself (`thread.started`'s `thread_id`), which Ants keeps for resuming. The Result is the turn's last `agent_message`, JSON under `--output-schema`, judged when `turn.completed` arrives.
- **The prompt** has the same shape for every Skill:
  1. The hand-off line: `Follow the Skill in <path> for the workspace at <Run workspace>.`, plus `It uses the Skills <name> (<path>) …` for the flattened `ants-uses` closure, plus ` The Vault is … and the Source map is ….` when the Vault toggle is on, plus `The Answer History is …` when that toggle is on.
  2. The Task's declared inputs as one JSON object.
  
  Start fresh appends the user's instruction under "From the user:".
- **Result contract** (from the result-contract ticket): Ants builds the schema from the envelope plus the Task's promised `output` (`result.schema.json` beside `SKILL.md`, the Task's override, or `{}`), and enforces the two cases with `if`/`then`/`else` on `outcome`, since the API refuses a top-level `oneOf` (ADR 0003):
  ```json
  { "outcome": "done",  "summary": "<one sentence>", "output": { ... } }
  { "outcome": "stuck", "summary": "<one sentence>", "needs": "<free text to the user>" }
  ```
  Ants reads the **last** `result` line's `structured_output` and validates it again. If it's invalid, Ants sends the error into the same live session once, and a second failure is `[failed]`; a `result` line with no `structured_output` is a failure, not an invalid Result. File references in `output` are strings the Skill's schema marks `"format": "path"`, relative to the Run workspace root; one that is absolute or leads out of it, symlinks followed, is rejected. `stuck` never reaches the diagram: only `done` completes the external task.
- **Waiting:** `stuck`, a failure and **Stop** each become an Operaton incident (`handleFailure`, retries 0), holding the tag and the reason. When the user acts, Ants stores the choice and instruction as a local variable and sets retries to 1, and the worker resumes (`--resume`) or starts fresh (a new session id).
- **Failures are Ants' own:** a crash, an invalid Result after one retry, a lost login (`authentication_failed` or any `apiKeySource` other than `none` in `system/init`), a missing Skill, or a failed resume.
- **Stepping in:** stdin stays open. **Send** writes a user message, which reaches the live session at the next tool boundary. **Stop** sends an `interrupt` control request, and the `error_during_execution` that follows becomes a `[stopped]` incident, not a failure. Ants closes stdin once it holds a valid Result and none of its messages is in flight (a message is in flight until claude replays it, which happens at the next tool boundary or in a turn of its own after a `result`; the last valid Result counts). After that, Send is refused. `codex exec` takes no message mid-run: it reads its prompt from stdin to the end before its turn starts. So for codex, Send interrupts the turn (SIGINT, as Ctrl-C does) and resumes the session with the message once the process has ended, and the page says so; **Stop** sends the same SIGINT, and the turn it ends is `[stopped]`. A message Sent after a valid Result but before the turn's process has ended gets a turn of its own, and the last valid Result counts.
- **Transcript:** the raw stream-json lines are appended to `~/.ants/runs/<run>/tasks/<task-id>/transcript.jsonl` as they arrive. Ants writes a divider per launch and its own system lines. The page replays the file, then follows it over SSE. Tokens come from `modelUsage`, so Subagents are counted. While a launch runs, its tokens come from the messages' `usage`; the `result` line's `modelUsage` replaces them, and it is cumulative for the process, so the last `result` of a launch holds the launch's total. Subagent spawns match tool name `Agent` or `Task`, are grouped by `agent_id` (falling back to `parent_tool_use_id`), and get their lifecycle from the `task_*` system events. **So far** is a haiku rewrite of new Transcript lines every couple of minutes, counted on the Task and switchable off in settings; it is written into the Transcript as an Ants line with its tokens, and `so-far` in settings is `false` or how often it is rewritten (default 2m). The checklist comes from the task-list tools (`TaskCreate`, `TaskGet`, `TaskList`, `TaskUpdate` in claude 2.1.295; there is no `TodoWrite`). A codex Transcript holds `codex exec --json`'s lines, plus a user line Ants writes for each prompt, since codex prints none: `agent_message` items are text (the Result when it is one), reasoning is thinking, commands, file changes, searches and MCP calls are tool lines, and a launch's tokens are its latest `turn.completed` usage (input plus output).
- **Usage limit:** a `rate_limit_event` with `status: rejected` gives `resetsAt`. One without `resetsAt`, a 429 `result` with no event, or a Subagent's `subagent_retry` with `rate_limit` means 30 minutes. On any of these Ants kills the process. Codex's "Try again at …" text (an `error` or `turn.failed` message saying "usage limit": "3:45 PM" today, else "Oct 13th, 2026 3:45 PM") is parsed as local time, with a 30-minute fallback ("Try again later."). Ants calls `handleFailure` with retries left and `retryTimeout` set to the reset plus one minute; this is not an incident and not on the Notification list. One in-memory pause gate per CLI makes fetched Tasks for that CLI re-pause straight away without launching. After the reset Ants always resumes with "The usage limit has reset. Carry on where you stopped." **Try now** opens the gate and unlocks the held tasks. There is no early throttling.

### Built-in Tasks

- **Clone repos:** input `repos` (Source map names or git URLs) and an optional `branch`. Each repo is cloned into `<Run workspace>/repos/<name>/` with `git clone --reference <local> --dissociate <remote>` at the remote's default branch. The work branch is checked out if it exists on the remote, otherwise created. The work branch is the one Clone repos recorded on the Run's clones (git config `ants.workBranch`), else `branch` (the start form's value, else the scout's), else `ants/<run-id>`. A branch held by another live Run (one whose clones were put on it) gets a counter suffix, `-2`, `-3` …. Landing and Push always use the recorded work branch, never the branch a clone happens to be on. The push URL is set to a dead value. Already-cloned repos and an empty list do nothing. A repo given by git URL is named after its last path part and cloned without `--reference`, as is a Source map repo whose local checkout is missing. The Result is `[{name, path, commit}]` for the repos this Task cloned (so running it again gives `[]`), and the Run's `clones` variable is updated.
- **Accept recommendations:** completes a Round with every `recommendation`, `source: recommended`, and names the asker it resumes.
- **Push:** waits on its own page. **Push** runs one `git push --dry-run` across all repos, then fast-forwards the work branch only, never force and never to the base. The Result has one entry per repo: `{repo, remote, branch, base, isNew, commits, pushed, error, compareUrl}`. A failed dry run pushes nothing, and a clone that isn't on the work branch fails its dry run; a rejection is `[failed]`, naming which repos pushed and which didn't, with git's message, and the user can push by hand from the kept clone (to the remote's URL, since the push URL is dead) and Continue. Continue, offered only on a `[failed]` Push, completes Push with what the remotes now hold, so a repo still unpushed has `pushed: false`. `commits` are the work branch's commits since the base, `[{sha, subject}]`; `isNew` means the remote didn't have the work branch when it was cloned; `compareUrl` is the forge's compare page for an https or ssh remote (GitLab's `/-/compare/`, else `/compare/`), null for a local one. **Cancel the Run** keeps the Run workspace. There is no Skip.
- **Landing** (implicit, not drawn): every Agent Task in an instance of a parallel multi-instance works in `instances/<key>/`, named by its Task key (`implement-2`, `draft-2-1`, `build-2.draft`). That folder holds a `git worktree` of each clone on `<work-branch>--<key>`, branched from the work branch head at launch, with symlinks for every other top-level entry and `--add-dir` on the Run workspace. On `done`, Ants rebases onto the current work branch in every repo, fast-forwards the shared clones, and removes the worktree and branch. Before any shared clone moves, every one must be clean, on the work branch, and an ancestor of the rebased instance branch; if one isn't, the landing is `[failed]` at once, naming the repo, nothing moves, and the instance keeps its worktree. Each Agent Task lands when it is done, so a later Task in the same sub-process instance branches from a head that holds the earlier one's commits. An instance's hand-off line names its folder as the workspace; file references in its Result stay relative to the Run workspace root. It lands one instance at a time per Run, all repos or none. With **Items are Tickets**, the instance's Ticket goes `in-review` as the instance ends, after its last landing. A conflict aborts the rebase and resumes the instance's session to resolve it, retried after each fix; after two rounds it is `[failed]`. Each round tells the session which repos conflict and that the rebase was aborted; the Agent rebases onto the work branch in its worktree itself, resolves the conflicts and answers again, and Continue after `[failed]` gets two new rounds. A rebase that fails for any reason other than a conflict is `[failed]` at once, and the instance keeps its worktree. A stopped instance keeps its worktree for Continue, and Start fresh discards it. Single Tasks and sequential multi-instances use the shared clones.

### User Tasks

- **Answer a Round:** the page reads only `output.questions` of the named asker. On complete, Ants resumes the asker's session with the answers to its latest Round, whoever gave them, so the asker has no answers input. Round shape (from the answer-screen prototype):
  ```json
  { "questions": [ { "id": "…", "title": "…", "ask": "…", "context": "…", "recommendation": "…", "why": "…" } ] }
  { "answers":   [ { "id": "…", "answer": "…", "source": "accepted" | "written" | "agent" | "recommended" } ] }
  ```
  Ants stamps `source`, never the Agent. Every question gets an answer. An empty Round ends the loop, through a gateway. The page handles a task removed by a timer boundary. An Answer a Round is a User Task with `ants.user-task` set to `answer-round`; a User Task without `ants.user-task` is an Approve. Every Round answerer (Answer a Round, an answer-round Agent Task, Accept recommendations) names the Agent Task whose questions it answers with `ants.asker`; an Agent Task with `ants.asker` is an answer-round Agent, and Ants stamps its answers `agent`, a question it leaves unanswered getting its recommendation, stamped `recommended`. The asker is resumed with the Round's `{answers}` JSON as its message. A Round's number is its asker's count in `runs`, whoever answered the earlier Rounds.
- **Approve:** fields Title, Show (a Run workspace path or `${…}`, rendered as markdown) and Send back resumes (a Task id). The Result is `{approved, comment}`, and Send back needs a comment. Title and Show are input parameters `title` and `show`, so `${…}` works in them and the Checks read them; Send back resumes is the extension property `ants.send-back`. Show names a file in the Run workspace, or else its text is rendered itself. Send back resumes the named Task's last session with the comment alone; a Task with no session yet starts fresh with the comment under "From the user:".
- **Review changes:** fields Walkthrough (a Task whose Story set it shows; without it, By repo only) and Send back resumes (one Agent Task, resumed if it has a session, else started). Tabs are Story and By repo. The Result is `{approved, comment, comments[]}`, each comment Varde's shape `{story, step, repo, file, side, from, to, type, body}` with `type` one of ISSUE, NOTE, SUGGESTION or COMMENT. Approve sets every `in-review` Ticket to `done`. It is a User Task with `ants.user-task` set to `review-changes`; Walkthrough is the extension property `ants.walkthrough`, and its Story set is always `walkthrough/stories.json`. By repo shows each clone's commits on the work branch since the remote's default branch, newest first, and their diff (`origin/<base>...HEAD`). A line comment's `file` is relative to the Run workspace root, as Story set paths are, and its lines are on the side the user picked (`old` for a removed line, else `new`). Send back needs a comment or at least one line comment. It resumes the named Task with the comment followed by one line per comment, `- TYPE file:from-to (side): body` (with `, <story> step <n>` before the colon for a comment on a story step), which also goes to the Run log. The Story set is checked when the walkthrough gives a `done` Result: a failure is sent into the same live session, as an invalid Result is. Two rounds means one fix request (Varde's `ATTEMPTS = 2`); a set still failing after it is `[failed]`, naming each step and fault. A Story set that is missing, isn't JSON, or breaks the format is `[failed]` at once, with no fix round. A range's `base` is the commit it starts from, given as Varde gives it (the merge-base). Ants diffs `base` to `head` and reads old Sites at `base`, new Sites and citations at `head`.
- **Notification list:** a query over open User Tasks and incidents, plus failed scheduled starts and `ready-for-human` Tickets. Ants keeps no table that could drift. Tags are derived by Ants: ✕ `[failed]`, ⚠ `[stuck]`, ■ `[stopped]`, ? `[questions]`, ✓ `[approve]`, ± `[review]`, ↑ `[push]` and `[ready-for-human]`. Push's failures are ✕ `[failed]` with Continue and Cancel the Run. A `ready-for-human` Ticket of a live Run leads to the Ticket page, whose **Mark done** sets it `done`. **Next up** jumps to a new entry from the same Run within a few seconds of acting.

### Runs, Run workspace and Report

- The Run workspace is `~/.ants/runs/<run>/` (minus what's kept), holding `repos/`, `instances/`, `tickets/`, `scout/`, `proposal/`, `walkthrough/`, `vault/` (staged Notes and their proposal, ADR 0007) and the Run log (`run-log.md`): every Round, `[stuck]` instruction and user message, appended as they happen.
- When a Run ends, Ants writes the Report mechanically, before cleanup: the start form, each Task's summary, the Rounds with sources (non-user answers listed separately), the Tickets, the `review` summaries, the Push and pull request Results, tokens, and links to the Transcripts. Cleanup then deletes the Run workspace except `tickets/`, the Report and the Transcripts. A failed or cancelled Run keeps the whole workspace until dismissed, with **Dismiss** on the Run page of a cancelled Run. Pauses aren't in the Report. The Report is `runs/<run>/report.md`, written as markdown and shown from a **Report** link on the Run page. Each Agent Task's line gives its last Result's outcome and summary, its tokens and a link to its Transcript; Built-in Tasks add their own sections, the `review` section lists each Task's latest `output.review`, and the Pull requests section each Task's latest `output.pullRequests`. A cancelled Run's Report is written when it is cancelled; Dismiss only cleans up.
- The Answer History lives under `~/.ants/answer-history/`, one markdown file per Round of the user's own answers (the whole question, the answer, the source, Process, Run, Task and date), kept forever. Ants appends one line per question to `INDEX.md` (the title linking to its anchor, a short context, the Process and the date) when the Round completes.

### Tickets

- Matt Pocock's local-markdown tracker in the Run workspace: `tickets/spec.md` is the Spec, and `tickets/issues/NN-<slug>.md` are its Tickets (What to build, checkbox Acceptance criteria, a `Blocked by:` line, a `Status:` line, `## Comments`). The files are the truth. Ants ships an `issue-tracker.md` telling Skills where the tracker lives, and writes it into each Run workspace at `docs/agents/issue-tracker.md`, where the Skills look for it.
- Statuses: `ready-for-agent` and `ready-for-human` (to-tickets or the user), `in-progress` and `needs-info` (the implementer), `in-review` (Ants, on landing), `done` (Ants, on Review changes Approve), `wontfix` (the user). The Spec closes when the Run ends, or becomes `wontfix` if the Run is cancelled: Ants writes its `Status:` line (`closed` or `wontfix`), and a Spec without one reads as open.
- Web edits refuse to save over a file changed since it was opened; a tick saves only over the version the page showed, like Save. A refused Save shows the new version above the user's text, and saving again overwrites it. Pages follow file changes over SSE.

### Catalogue: Agents, Skills, Templates

- Settings list Agent folders and Skill folders. `~/.ants/agents` and `~/.ants/skills` always come first, and `~/.ants/shipped/` is always loaded. A Skill is any folder holding a `SKILL.md`, at any depth. An Agent folder contributes only the `*.md` files directly inside it. Names must be unique across all folders, except that a shipped Skill wins its name and the hidden one is shown greyed out. Shipped Processes are `shipped/processes/*.bpmn`, deployed through the Checks on every start, each as its own deployment, so a start deploys one again only when Ants ships a change (ADR 0007).
- `shipped/` is owned by Ants and replaced whole on start when any file differs.
- Skill frontmatter keys Ants reads: `name`, `description`, `metadata.ants-inputs` (space-separated input names), `metadata.ants-uses` (space-separated Skill names), `metadata.varde-vault: true` and `metadata.ants-answer-history: true`. A Task gets the Vault if its Skill or any used Skill opts in, and the Task's toggle has the final say (ADR 0002). A used Skill's result schema is ignored.
- The admin pages write only to the user's folders. They support create, edit, copy, rename and delete, with **Used by** (deployed Processes, and the Skills that use a Skill); a rename or delete warns, and Ants never rewrites a Process. Import takes one markdown file into the create form, dropping unknown Agent keys with a notice and refusing broken YAML, a Skill without name or description, and an Agent whose `cli` is unknown. Edits reach live Runs at the next launch; nothing is snapshotted.
- The model list comes from Anthropic's public model status table, refreshed with the update check. Aliases show what they resolve to, deprecated models warn, and a custom ID is tried once by the pre-Run check.

### Shipped content

- **Skills** (picked by a Task; each written for Tasks and adapted from Varde's or Matt Pocock's where noted, MIT notices kept):

  | Skill | Agent | Inputs | `output` |
  |---|---|---|---|
  | `scout` | scout | prompt (and the Source map as `sourceMap: [{name, remote}]`) | `{repos, branch, brief}` |
  | `grill` | griller | prompt, brief, clones | `{questions[], repos[]}` |
  | `answer-round` | answerer | questions | `{answers: [{id, answer, why}]}` |
  | `to-spec` | planner | prompt, brief, rounds, clones | `{spec}` |
  | `to-tickets` | planner | spec, clones | `{tickets[]}` |
  | `implement` | implementer | ticket, spec, *or* prompt, proposal | `{review}` |
  | `walkthrough` | storyteller | spec, clones | `{stories}` |
  | `fix-review` | implementer | review, spec, stories | `{review}` |
  | `propose-solution` | architect | prompt, brief, clones | `{proposal}` |
  | `pr` | pr | push, spec or proposal, review | `{pullRequests: [{repo, url}]}` |
  | `update-knowledge` | knowledge | rounds, spec, clones, approval | `{proposal, written}` |
  | `init-vault` | knowledge | approval | `{questions[], proposal, written}`: a Round, then a proposal, then written |

- **Used-only Skills** (reached through `ants-uses`): `tdd` (seams from the Ticket's criteria and the Spec; `stuck` only when there are none), `code-review` (two parallel Subagents, Standards and Spec, over the instance's commits; fixes are new commits) and `search-knowledge`. Shared knowledge files keep Varde's Vault format, leak check and staging (ADR 0002); `vault-format.md`, `leak-check.sh` and `topic.base` live in search-knowledge's folder, which update-knowledge and init-vault use, so it is an `--add-dir` of theirs. A knowledge Task stages Notes in `vault/staging/`, writes its proposal to `vault/proposal.md` and answers `written: false`; its `approval` input is the Approve that follows it, and it writes the Vault only on the launch where `approval.approved` is true (ADR 0007).
- **Agents:** `scout` (Read, Grep, Glob, Write only `scout/`, the Agent tool limited to `knowledge-searcher`, no Bash; `--add-dir` for the Vault only, so it can't wander; ADR 0008), `griller` (Read, Grep, Glob), `answerer`, `planner` (Read, Grep, Glob, Write and Edit only `tickets/`), `implementer` (lists `implement` and `fix-review`; has the Agent tool), `storyteller` (Read, Grep, Glob, Write and Edit only `walkthrough/`, and Bash limited to `git log`, `git diff`, `git show` and `git merge-base`; ADR 0009), `pr` (Read, and Bash limited to `gh pr create`, `gh pr view`, `git log`, `git diff`; GitHub only, on the user's `gh` login; a non-GitHub remote is `stuck` with the compare URL, after the GitHub ones are opened), `architect` (Read, Grep, Glob; Write and Edit only `proposal/`) and `knowledge`. `knowledge-searcher` is a shipped Agent with **Offer as a Subagent** ticked; the scout searches the Vault through it as a Subagent, within the scout's own limits.
- **Scout rules:** the scout reads the prompt, the Vault and the Source map JSON (its `sourceMap` input, `${sourceMap}`), and never code. `repos` contains only Source map names (matched loosely) or git URLs that appear word for word in the prompt. An unknown or ambiguous named repo is `stuck`. No repo at all gives `repos: []`. The brief is `scout/brief.md`: the prompt as understood, each repo and why, relevant Notes as `[[Name]]`, and what the Vault doesn't answer.
- **Story set:** Varde's story format widened to a Run. `ranges: [{repo, base, head}]` replaces `range`, and every file path is relative to the Run workspace root, written to `walkthrough/stories.json`. Its schema, `story-set.schema.json`, lives in the walkthrough Skill's folder, where the storyteller reads it and Ants checks against it. When the walkthrough is `done`, Ants runs Varde's four mechanical checks (file missing, range out of bounds, a changed Site touching no hunk, citation absent). A failure resumes the session naming the failing steps; after two rounds it is `[failed]`. An unparseable file is refused whole.
- **Templates (six kinds):** Scout and clone (scout, reading `${prompt}` and `${sourceMap}` → Clone repos with `${scout.output.repos}` on the start form's `branch`, else the scout's); Grill until no questions (one per answerer; only the "you answer" one has **Clone new repos** in the loop, and the other two carry the Round cap; the asking Task's id is `grill`, so the cap reads `${runs.grill}`; the "you answer" one is grill → Clone new repos with `${grill.output.repos}` → **Asked?** → Answer the Round → grill, ADR 0009); To-spec and approve (to-spec, `rounds` the Run log `run-log.md` → Approve the Spec, Send back resumes to-spec, looping back unless approved); Cut and implement (to-tickets → a parallel multi-instance over `${totickets.output.tickets}`, element variable `ticket`, **Items are Tickets** ticked); Review and push (walkthrough, resume on → Review changes ─Send back→ fix → walkthrough …, then Push → Open pull request; fix's `review` is the Review changes Result, and pr's `review` is the implementers' Results, `${implement}`, ADR 0009); Update the Vault (update-knowledge, resume on, `approval: ${approvenotes}` → **Written?** → if no, Approve the Notes and loop back, whether approved or sent back).
- **Processes:**

  | Process | Shape | Waits on the user |
  |---|---|---|
  | Ship with me | Scout and clone → Grill (you answer; Clone new repos) → To-spec and approve → Cut and implement → Review and push → Update the Vault | every Round, the Spec, Review changes, Push, the Notes |
  | Ship by proxy | the same; answer-round answers, Round 10+ goes to you | the Spec, Review changes, Push, the Notes |
  | Ship as proposed | the same; Accept recommendations, same cap | as Ship by proxy |
  | Ship overnight | answer-round answers every Round, the cap ends the loop; to-spec with no Approve | Review changes, Push, the Notes |
  | Ship small | Scout and clone → propose-solution → implement (single Task, shared clones) → Push → Open pull request → Update the Vault (its `spec` the proposal) | Push, the Notes |
  | Set up Vault | init-vault → its Round → Approve the rule Notes | the Round, the Notes |

  The unattended Processes never widen the Run's repos. to-spec lists the uncloned repos in the Spec instead.

## Testing Decisions

- **One seam: the running Ants service.** Tests start Ants with a temporary `ANTS_HOME` and an in-memory or temporary H2. They drive it the way the user does, through its HTTP pages, form posts and SSE, and assert only on what the user can see: the Notification list, Run and Task state, Results, Ticket files, the Report, the Transcript, and refs on git remotes. Tests never reach into Operaton tables or worker internals.
- **The AI CLI is faked through an existing seam:** the per-CLI command prefix in settings, the same knob the user uses for nono. A small fake `claude` executable reads its flags and stdin and plays a scripted stream-json session: `system/init` (with `apiKeySource`), assistant text, tool calls, `rate_limit_event`, an `interrupt` response, and a final `result` with `structured_output`. The script is picked per Task by Skill name, so one test can play stuck → Continue → done, an invalid Result → retry, a rejection → pause → resume, or a lost login. The fake also records the argv and environment it was launched with, so tests check the flags and environment hygiene (`ANTHROPIC_API_KEY` stripped, `CLAUDE_CONFIG_DIR` set, `--disable-slash-commands`, the hand-off line, `ants-uses` paths) from the outside. This follows Varde's "never test who is running" principle (varde-editor ADR 0004).
- **Git is real:** remotes are local bare repos, and the Source map points at them. Clone repos, worktrees, landing, conflicts and Push (including a rejected fast-forward) are asserted on the bare repos' refs.
- **Browser behaviour is tested in a real browser:** Playwright drives headless Chromium against the running service, in tests tagged `browser`. Locally, a Chromium that can't launch skips with the nono Seatbelt rules and `--no-sandbox` it needs; in CI (`CI` set), it fails instead.
- **Time is injectable** at the one place Ants reads it (the Schedule loop and pause-gate deadlines), so missed times, overlaps and resets are tested without waiting.
- **What makes a good test:** a whole user-visible behaviour through the seam. Examples: "a stuck Agent Task appears on the Notification list, and Continue resumes the same session id", "Save and deploy refuses a terminate end event and names it", "Ship small with a fake implementer ends with one pushed branch and a Report". Each test runs a small drawn Process, or a shipped one, end to end. Tests don't mock Ants' own modules, and don't assert on how a module is built.
- **What is covered:** the Checks (every refused element, every reference rule), the result contract, the incident and Notification list lifecycle, Rounds and the answerers, Approve and Review changes loops, call activities and multi-instance Result collection, the Blocked-by hold, landing and conflicts, Push, Schedules, the usage-limit pause, the concurrency cap, the catalogue (folders, name clashes, `ants-uses` resolution and cycles), Ticket status moves, the Report and cleanup, plus the five default Processes and Set up Vault run end to end against fake Agents.
- **Not covered by automated tests:** the shipped Skills' prose quality (the real model), visual layout of pages, and the PWA install. These are judged by the user on real Runs.
- **Prior art:** none in this repo yet; this is the first code. `scripts/burn.sh` in `oyvij/varde-editor` (a fresh `claude -p` per issue in a clone) is the closest shape. The throwaway prototype branches (`prototype/answer-screen`, `prototype/web-ui`, `prototype/default-processes`) are references for pages and diagrams, not tests.

## Out of Scope

- A plugin system in Varde.
- **Varde integration:** starting Ants from Varde, handing it Varde's Vault, Source map and workspace folder, or a Varde TUI or hook controlling Ants. Ants only prepares for it, through configurable paths and Varde's formats.
- An Ants TUI: the web UI is the only UI.
- A shared server for a team.
- Phone push notifications: the Notification list is enough.
- Paying per API token.
- Importing outside Skills and Agents, adapting them to Ants with an Agent, or syncing them with their source. The result-schema override already makes an unmodified Skill answer in JSON.
- DMN (a Business rule task): v1 routes on exclusive gateways.
- Knowledge without a Vault: v1 requires one, through **Configure a Vault** and **Set up Vault**.
- MCP servers for Agents: Agents start with MCP off. An `mcp__` tool can still be ticked by name.
- Moving to a server beyond the Docker image (a Helm chart and the rest).
- Going public: Developer ID signing and notarizing, Intel Mac, a Linux desktop package.
- Starting a Run from a hand-written Spec: every v1 Run starts from a prompt.
- Reading forge issues (scout, to-tickets) or starting a Run from one: v1 takes them pasted into the prompt.
- `sweep-knowledge` and a Sweep the Vault Process.
- A **Run a command** Built-in Task: an Agent with Bash runs the tests and reports.
- A generic **Form** User Task: a Round asks, and Approve covers a yes or no.
- A repo's own Skills (`.claude/skills` in a clone) in a Task's session.

## Further Notes

- **Source:** this Spec collapses the wayfinder map oyvij/ants#1 and its 25 resolved tickets (#2–#26). Where a ticket was revised, the later one is used: Operaton is embedded (#4 over #2); the modeler is Ants' own bpmn-js page (#12, revised by the user); the install is a jlink tarball, unsigned (#16 over #4); an Agent lists its Skills (#20 over #5); there is no Choose repos, replaced by the Repos field (#26 over #8 and #19); Templates keep their ids (#25 over #21); there are no New Process patterns (#21 over #20); Ants sets `in-review` (#24 over #23); there are five default Processes (#25 over #15 and #22); the implementer lists two Skills (#26 over #25); `sweep-knowledge` is out (#26, ADR 0002); the pre-Run check covers the login (#13 over #11); the field types are Text, Markdown and Repos (#26 over #20); pr's output is `pullRequests[]` (#26 over #22); to-tickets' output is `{tickets[]}` (#26 over #23).
- **Subagents:** #18 says Ants' own Agents are never exposed as Subagents, while #19 has the scout search through `knowledge-searcher` as a Subagent. Settled after the map: a per-Agent **Offer as a Subagent** tick box, off by default and ticked on the shipped `knowledge-searcher`.
- **To verify early in the build** (flagged in the tickets):
  - Operaton's `fetchAndLock` ordering by create time;
  - whether a rejection on the very first request leaves a resumable session;
  - whether a top-level `rate_limit_event` arrives before a Subagent's rate-limit retry;
  - whether `codex exec` accepts mid-run messages (else Send falls back to stop-and-resume, and the page says so). Settled from codex-rs's source (#73): it doesn't, so Send stops and resumes.
- **Accepted ceilings, to mark with `ponytail:` notes:** a clone's `permissions.allow` still widens the allow-list (fine while Source map repos are the user's own); nothing is snapshotted at Run start; the pause gate is per CLI, not per model; a single global cap with no per-CLI caps or priorities; the Answer History is never pruned.
- **Glossary:** use `GLOSSARY.md`'s terms throughout. Note "scout", never "sweep"; "Round answerer", never YOLO; and never "workflow".







