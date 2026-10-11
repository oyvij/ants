# The Result envelope splits on `outcome` with if/then/else, and marks file references `"format": "path"`

The spec asked for `oneOf` on `outcome` to enforce the two Result cases, but `claude --json-schema` hands the schema
to the API as a tool's input schema, and the API refuses it: `input_schema does not support oneOf, allOf, or anyOf
at the top level` (claude 2.1.295). So the envelope lists `outcome` (`enum: [done, stuck]`), `summary`, `output` and
`needs` as properties, requires `outcome` and `summary`, and splits with `if` (outcome is `done`) `then` (require
`output`, forbid `needs`) `else` (require `needs`, forbid `output`). It accepts exactly the Results `oneOf` would.
File references in `output` are strings the Skill's `result.schema.json` marks `"format": "path"`, which claude
accepts; Ants rejects one that is absolute, is the Run workspace root, or leads out of it, symlinks followed.

## Considered Options

- **Wrap the envelope one level down:** `oneOf` would be legal there, but `structured_output` would no longer be the
  Result, and every reader would unwrap it.
- **Send claude a loose schema and check the strict one only in Ants:** honest about the API's limit, but claude
  would stop enforcing the cases, and every slip would cost the one retry.
- **Treat every path-shaped string in `output` as a file reference:** no marking needed, but free text and API
  paths such as `/users/{id}` would be rejected.

## Consequences

- A Skill whose output carries file references must mark each one `"format": "path"`, or Ants never checks it.
  The shipped Skills' schemas must; the Skills page and the result-schema override should say so.
- A `claude` update could tighten what the API accepts again. The `live` test (`./gradlew liveTest`) runs the real
  envelope through the real `claude`.
