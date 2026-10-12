# The scout reads the Source map as a Run variable, and writes only its brief

The spec gives the scout the Source map as an input, `sourceMap: [{name, remote}]`, but no Run variable or Result
holds it, and a Task's inputs are expressions. It gives the scout Read, Grep and Glob, with no Bash, yet has it write
`scout/brief.md` and search the Vault through the `knowledge-searcher` Subagent. And it has the pr Agent write the pull
request's body "from the commits and the review" while its inputs are only `push` and the Spec or proposal.

- **`sourceMap` is a Run variable.** At Run start Ants sets `sourceMap` to the default Source map's repos,
  `[{name, remote}]`, beside `runs` and `clones`, so the Scout and clone Template fills the scout's input with
  `${sourceMap}`, and the Checks and input pickers know the name. The scout never needs the Source map's path, so its
  `--add-dir` stays the Vault's alone.
- **The scout's tools are `Read, Grep, Glob, Write(scout/**), Agent(knowledge-searcher)`.** It writes only in
  `scout/`, and its Agent tool starts only `knowledge-searcher`, which Ants enforces by denying every other Subagent
  type. Still no Bash.
- **The architect's tools are `Read, Grep, Glob, Write(proposal/**), Edit(proposal/**)`**, and the pr Agent's are
  `Read` and Bash limited to `gh pr create`, `gh pr view`, `git log` and `git diff`.
- **pr has a `review` input**, the implementers' `output.review`, besides `push` and the Spec or proposal.

## Considered Options

- **The scout reads the Source map file** named in its hand-off line: it would need the file's folder as an
  `--add-dir`, and the file is the user's, in Varde's markdown, not the JSON the spec promises.
- **Ants adds `sourceMap` to the scout's inputs itself**: a Skill-specific rule in the launcher, and an input the user
  can't see on the Task.

## Consequences

- A Process can read the Source map anywhere as `${sourceMap}`, read once at Run start: a Source map edited during a
  Run reaches the next Run.
- The path rules (`Write(scout/**)` and the like) rely on claude matching them relative to the Run workspace, the
  working directory; they have not been probed against a real claude.
