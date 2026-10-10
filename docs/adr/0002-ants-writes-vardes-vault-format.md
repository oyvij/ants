# Ants writes Varde's Vault format

Ants has knowledge Skills of its own (search, update, sweep and init-vault), written for Ants rather
than run from Varde's folder, because Varde's Skills converse, read "the session" and touch the user's
checkouts, none of which fits a Task. But the Vault they read and write keeps Varde's format exactly:
the same Note frontmatter, Topics, Kinds, index Notes and Bases, the same Vault rules at
`Vault/Vault.md`, the same staging and leak check. The user points Ants at the Vault Varde uses, so
both tools must write Notes the other can read; a Vault full of Notes is costly to migrate.

## Considered Options

- **Run Varde's Skills in place:** no copy to drift, but every Skill would need an unattended mode
  in Varde, and Ants would depend on Varde being installed.
- **A Vault format of Ants' own:** free to change, but it splits the user's knowledge in two.

## Consequences

- Ants' `vault-format.md` is its own file, kept in step with Varde's by hand. A change to the format
  is made in both.
- Ants keeps Varde's ADR 0025 promise: only a Skill whose frontmatter has `metadata.varde-vault: true`
  is told the Vault's and the Source map's paths and given access to the Vault folder; the Vault's
  path never goes into an environment variable or the Run workspace.
