# Protected Commands run with Secrets Agents never see

An Agent's environment is the settings allow-list, which Ants may narrow but never widen, and Ants under launchd has
none of the user's shell exports. So a repo whose `.yarnrc.yml` reads `${YARN_NPM_AUTH_TOKEN}` for a private feed
can't `yarn install`, and the only workaround, the token in the plist and the allow-list, hands it to the Agent and the
model.

- **The threat is an accident, not a hostile Agent.** A Secret must never enter the session, the model, the
  Transcript, the Run log or the Report. Agents run as Ants' OS user, so a determined one can still leak a Secret, for
  example through a `postinstall` script it edited before asking for `yarn install`. That is the ceiling.
- **The user keeps Secrets and Protected Commands in Settings.** A Secret is a label, a value and one or more variable
  names (`[A-Z_][A-Z0-9_]*`). `settings.yaml` holds the labels, names and Protected Commands (prefix to Secret labels);
  the values live in `secrets.yaml` in ANTS_HOME, written mode 0600 before a value is in it, never in `settings.yaml`.
  The page never sends a value back; a value left blank keeps the saved one. Ants reads both on every request.
- **One MCP tool, `run(command, folder)`.** Every claude Agent Task gets Ants' MCP server over HTTP on localhost
  through `--mcp-config` (under `--strict-mcp-config`, kept) with a bearer token of its own in `headers`, and
  `mcp__ants__run` in `--allowedTools`. The endpoint (`/mcp`, JSON-RPC: `initialize`, `tools/list`, `tools/call`) is a
  handful of handlers, no new dependency. It refuses any request without the token of a live Task, outside the login.
- **Running:** the command is split into words and run with no shell, so `yarn install; env` is arguments to yarn. The
  Protected Command whose prefix words start it, the longest, gives its Secrets. The folder is relative to the Task's
  Run workspace, symlinks followed, and refused outside it. The environment is claude's allow-list plus each linked
  Secret's value under each of its names. After 10 minutes Ants kills the process tree. The Agent gets the exit code and
  the output, stdout and stderr together, with every Secret's value, linked or not, as `••••`. A command no Protected
  Command starts is refused with an error that tells the Agent to end stuck with it in `needs`; the user adds the row
  and presses **Continue the session**.
- **Hygiene:** no Secret is ever in an Agent's own environment. Every claude Agent is denied Read and Edit of Ants'
  `secrets.yaml` and `settings.yaml`, not of all of ANTS_HOME as the ticket first said: the Run workspaces, the Skills
  and the Answer History live there, and a deny rule beats every allow, the working directory's included. The deny keeps the Read tool away;
  Bash, as in `cat ~/.ants/secrets.yaml`, still reads the file, which the same-OS-user ceiling covers.

## Considered Options

- **A credential-injecting proxy** with TLS interception, which adds the token to the registry's requests so no process
  of the Agent's ever holds it. It closes the same-OS-user ceiling, at the price of a CA in every tool's trust store. It
  is the upgrade path if a hostile Agent ever matters.
- **Encryption at rest or the OS keychain**: the key would sit with Ants' OS user too, so it guards against nothing an
  Agent can't also reach.
- **A shell for the command**: pipes and `&&` would be handy, and would run whatever follows a `;` with the Secrets.

## Consequences

- codex Agents get no `run` tool yet; whether codex reaches Ants' MCP server from its sandbox is a follow-up.
- Secrets reach no git or gh call of Ants' own (Clone repos, Push).
- The argv of a claude launch carries its token, which `ps` shows to Ants' OS user; the token dies with the session.
