# Scripts are JavaScript the user writes once, and everything links by a fixed id

Some steps of a Process are plain, repeatable work that needs no AI: calling an API with a token, reshaping a JSON
file, posting a message. A Built-in Task is only what Ants ships, an Agent Task spends tokens and a slot and may do the
work differently each time, and a Protected Command runs only a prefix the Agent assembles. So Ants gets **Scripts**:
code the user writes once in a library, gives vars and Secrets, and has a Process or an Agent run the same way every
time, while no Agent sees the source or the Secrets. And since Agents and Skills were linked by name, so that a rename
broke what named them and needed a confirm, Scripts, Agents and Skills all link by a fixed id instead.

- **A Script is a JavaScript ES module run by the Node Ants already needs, with Node built-ins only.** Ants installs no
  packages, so an import of one fails with Node's own error, and the user needs nothing new. Python and other languages
  are left for later.
- **On disk**, a Script is the folder `ANTS_HOME/scripts/<id>/`: `script.mjs` (the code), `script.yaml` (`id`, `name`,
  `description`, `inputs`, an optional `output`, `vars` and `secrets`) and, when it has Secrets of its own, `secrets.yaml` with their values.
  `inputs` is the input schema, an object JSON Schema (`type: object`, `properties`, `required`), each property an input
  with a `type`, a `description`, and nested `properties` and `required` for an object or `items` for a list. The
  page edits that subset as rows; whatever else the file holds (`enum`, `minimum`, an `integer`) is kept where a row
  still has the same name and type. A file still holding a list of names reads as untyped properties. `output`, the
  output schema, is the payload's object JSON Schema in the same subset, edited the same way; with none, any JSON
  payload is accepted.
  `secrets` in `script.yaml` lists the names of the Secrets it uses, its own and those it links from Settings, and never
  a value; linked Secrets keep their values where Settings keeps them (ADR 0015). So `script.yaml` can be read and
  shared without leaking a token.
- **One runner runs a Script, whoever calls it**: a Script Task, an Agent's Script tool, or a Test run from the user or
  the editor's AI helper. It runs `node script.mjs` with no shell, in a given folder, as Protected Commands run.
  - It checks the inputs against the input schema first, with the JSON Schema validator the Result contract uses, and
    refuses ones that break it, before Node starts: the run fails with each problem in its output.
  - The environment is the CLI's allow-list, the Script's vars and Secrets each as `process.env.<NAME>`, and
    `ANTS_RUN_WORKSPACE` and `ANTS_RUN_ID`.
  - Node starts with an Ants module loaded before the Script (`node --import`), which defines the global `ants`. The
    Script reads its parsed inputs as `ants.inputs` and hands back its payload with `ants.output(value)`; a later call
    replaces an earlier one, and the payload is serialised when the process ends. How the module gets the inputs and
    hands back the payload is the runner's business, not the Script's: no stdin to parse, no file to write. The payload
    never mixes with stdout, so logging never breaks what later Tasks read. The Script is still a plain ES module, so
    top-level `await` works.
  - It answers `{exitCode, output, payload}`: `output` is stdout and stderr together, with every Secret value, the
    Script's own and its linked ones, masked as `••••` by the masking Protected Commands use; `payload` is the parsed
    JSON, or empty when the Script handed none back.
  - Once Node exits 0, it checks the payload against the output schema, if there is one, with the same validator. A
    payload that breaks it, an empty one included, fails the run though the exit code stays 0, each mismatch named: a
    Test run shows them beside the result, and a Script Task's output and a Script tool's answer end with them.
  - After 10 minutes, as for `run`, Ants kills the process tree and the run fails.
  - A value `ants.output` can't serialise to JSON fails the run, and its output says so. The Spec left this open;
    failing is the safe choice, since a later Task would otherwise read a payload that isn't there.
  - A Test run runs the editor's unsaved code with sample inputs, in a new scratch folder deleted afterwards, with the
    Script's real Secrets and vars.
- **A Script Task** is a `bpmn:ServiceTask` on topic `script` with `ants.script=<id>`, the Script's inputs as its input
  parameters, so `${…}` works in them. A Built-in worker runs the Script in the Run workspace and completes the Task
  with `{exitCode, output, payload}` as its Result under the Task's id. A non-zero exit or a timeout fails the Task the
  way a failed Built-in Task fails, `[failed]` with the masked output, and so does a payload that breaks the output schema, offering Retry and Cancel the Run. **Retry runs
  the Script as it is on disk at that moment**, so the user fixes the Script and retries. A problem the Process should
  branch on is an exit 0 with the problem in the payload: an expected problem is a path, not a failure. There are no
  inline Scripts: a Script Task names one in the library, and the modeler's **New Script** and **Edit** open the Script
  editor in a modal that saves there. Checks refuse a Script Task that names a missing Script or passes an input its
  Script doesn't declare.
- **Only claude Agents get Script tools.** An Agent lists the Scripts it may call under `scripts:`, and an Agent Task may
  narrow them with `ants.scripts`, as with Skills (ADR 0024). At launch, a claude Agent Task's MCP session serves, beside
  `run`, one tool per allowed Script, `script_<name>`, with the Script's description and its inputs as the input schema,
  each in `--allowedTools`. With an output schema, the description goes on to give it, as what the payload holds. It runs the Script through the runner in the Task's Run workspace and answers the exit code,
  the masked output and the payload, as an error when the exit is non-zero or the payload breaks the output schema. The tool name follows the Script's current
  name, sanitised to what a tool name may hold and kept unique within a launch, so a rename shows at the next launch.
  codex Agents get none: codex has no MCP yet (ADR 0015).
- **The source stays hidden.** Every claude Agent's `--disallowedTools` denies `Read` and `Edit` of
  `ANTS_HOME/scripts/**`, as it denies `secrets.yaml` and `settings.yaml`, which keeps the source and each Script's
  `secrets.yaml` from the Read tool. A Script's source never goes into a hand-off, a tool result or a Transcript, and
  the Process Builder's catalogue lists a Script's name, description and inputs only; it can draw a Script Task naming
  an existing Script but never writes or changes one. Bash can still read the folder: this accepts the same-OS-user
  ceiling ADR 0015 accepts.
- **Scripts, Agents and Skills have fixed ids in one pattern**: a prefix and 6 random characters, `s-xxxxxx`,
  `a-xxxxxx` and `k-xxxxxx`, given on create, import or copy (a copy gets a new one) and never changed. Shipped Agents
  and Skills have fixed, readable ids kept in the repo, such as `a-griller`, so they are the same on every install. A
  Check refuses two items with the same id.
  - A Script's id is in its `script.yaml` and names its folder; an Agent's is an `id:` key in its agent file; the user's
    own and the shipped Skills keep theirs in a sidecar `ants.yaml` next to `SKILL.md`, which `scripts/matt-skills`
    keeps when it copies Matt Pocock's Skills over.
  - Ants never writes into outside folders (Settings' extra Skill and Agent folders), so their ids are kept in
    `ANTS_HOME/skill-ids.yaml` and `ANTS_HOME/agent-ids.yaml`, mapping id to folder path. The Spec named only Skills;
    Agents follow the same rule.
- **Links hold ids, and names stay beside them.** `ants.agent`, `ants.skills`, `ants.script`, `ants.scripts` and an
  Agent's `skills:` and `scripts:` hold ids, and a Process keeps each linked item's name beside its id: an id is an id
  and a name is a name, and neither is lost. The Process Builder's tools and hand-edited files keep taking names, which
  Ants resolves to ids on save. An imported Process or Template whose id is unknown is resolved by the name kept beside
  it, and a Check refuses it when neither matches. Renaming a Script, an Agent or a Skill changes only its name, with no
  confirm; deleting one still confirms with what uses it. Pages, panels and the modeler show names only: an id is never
  visible in Ants' UI.
- **The user's files are migrated once, at startup**, before anything deploys or launches, as the existing Migration
  runs: every Agent and Skill without an id gets one, and the names in the user's latest Processes, Templates and Agents
  are rewritten to ids with each name kept beside its id. Each changed file keeps its old version beside it as
  `<file>.old`, and a Process keeps its older version. What is already in the new shape is left as it is, so a second
  start changes nothing. This reverses the rule that Ants never rewrites a Process, once, as ADR 0024's migration
  already redeploys Processes.
- **This amends ADR 0024**, whose `ants.skills` and an Agent's `skills:` named Skills by name: they now hold ids, with
  the names kept beside them.

## Considered Options

- **A Built-in Task per job, shipped by Ants**: only what Ants ships, never the user's own small step.
- **An Agent Task for plain work**: tokens and a slot for work that needs no AI, done differently each time.
- **A Protected Command the Agent assembles**: still an Agent, and a prefix, not code the user wrote.
- **npm packages with an install step**, or **Python**: something more to install and keep working on every machine.
  Left for later.
- **The payload on stdout**: any log line would break it. Hence `ants.output`. Before it, a file named
  by `ANTS_OUTPUT` and the inputs on stdin, which made every Script write that plumbing itself.
- **Inline Scripts on a Task**: code nobody else can reuse, and a second place to edit and hide it.
- **Script tools for codex**: they need MCP in codex first.
- **Keeping names as links, with a rename confirm**: every rename stays a risk and a question. A fixed id makes it
  cheap.
- **Ids alone, without the names**: an import onto another install couldn't find what an unknown id meant.
- **Writing ids into outside folders**: those folders aren't Ants' own. Hence `skill-ids.yaml` and `agent-ids.yaml`.

## Consequences

- An Agent can still read a Script's source or its `secrets.yaml` with Bash, as it can Ants' `secrets.yaml` (ADR 0015).
- A Script tool's name changes when its Script is renamed, at the next launch.
