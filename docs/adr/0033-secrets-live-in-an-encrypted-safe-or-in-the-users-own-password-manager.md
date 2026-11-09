# Secrets live in an encrypted Safe, or in the user's own password manager

ADR 0015 kept Secret values in plain `secrets.yaml` files, mode 0600, and rejected encryption at rest because the key
would sit with Ants' OS user. But plain text leaks through every backup, sync and stray copy of ANTS_HOME, and an
Agent's Bash reads it with `cat`. So every value is now encrypted in one Safe, whose key Ants holds only in memory, or
is a Lookup into a password manager the user already trusts, such as 1Password, with no tie to one provider.

- **One Safe** holds the value of every Secret, those in Settings and each Script's own. It is encrypted with a random
  Safe key, shown to the user once, to keep in their password manager. Ants holds the key in memory only while the Safe
  is unlocked.
- **Your login unlocks it.** The Safe key is stored wrapped by the Ants login password, as 1Password wraps its keys, so
  the login a restart already asks for unlocks the Safe, and no key is on disk in plain text. A password changed on the
  Settings page rewraps it; one reset by `ants password`, ANTS_PASSWORD or ANTS_PASSWORD_FILE asks for the Safe key
  once. A password from the environment unlocks the Safe at boot.
- **Keep open** is a toggle, for a server nobody logs in to after a restart: it writes the Safe key to a file outside
  ANTS_HOME. Settings warns, for it and for a password from the environment, that the Safe then guards against leaks
  of the disk, not against an Agent.
- **A Secret is Kept or a Lookup.** Each Secret row has a toggle: a value, kept in the Safe, or a Lookup such as
  `op://Dev/npm/token`. A Secret Provider in Settings resolves each scheme with a command, such as `op read {ref}`,
  `bws` or `vault kv get`, run with the Secrets linked to it, such as `OP_SERVICE_ACCOUNT_TOKEN`, which are in the Safe
  like any other. Ants resolves a Lookup at each use, so a rotated value works at once, and fails the Task, naming the
  Secret, when the Provider can't.
- **A locked Safe makes a Task wait.** A Task that needs a Secret while the Safe is locked waits, as a Permission
  request does, and an Alert asks the user to unlock it. Other Tasks run.
- **Masking** covers every Kept value and every Lookup value Ants has resolved since the Safe was unlocked, held in
  memory only.
- **Migration is forced.** On the first start after the update, Ants creates the Safe, moves every plain `secrets.yaml`
  into it, deletes them, and shows the Safe key in a banner on every page until the user says it is saved.
- **Reset Safe** is the answer to a lost Safe key: it wipes the Kept values and keeps the labels, Lookups and Secret
  Providers. There is no recovery code; the user's password manager is the recovery.

## Considered Options

- **The OS keychain**: macOS only, and Ants runs in Docker too.
- **Lookups alone**: the Provider's login token must be kept somewhere, and in plain text it is the same leak.
- **Unlock with the Safe key at every restart**: safe, and too irritating on a desktop.

## Consequences

- ADR 0015's ceiling stands: a Protected Command runs with its Secrets, so code an Agent edited before asking for it,
  a `postinstall` script, can still send them anywhere. The credential-injecting proxy is still the upgrade path.
- A Safe unlocked by Keep open, or by a password from the environment, is only as safe as ADR 0015's files were
  against an Agent; it still keeps plain text off backups.
