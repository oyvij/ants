# Issue tracker: GitHub

Issues live on `oyvij/ants`, a **private** repo on the personal account `oyvij`. Never create it,
transfer it or mirror it under an organisation. Use `gh`; it infers the repo from `git remote -v`.

- **Create**: `gh issue create --title "..." --body "..."` (heredoc for multi-line bodies)
- **Read**: `gh issue view <n> --comments`
- **Start a ticket**: `scripts/ticket-context <n>` prints, in one call and unfiltered, the ticket, every ticket it
  is blocked by or blocks, each with its comments, and the whole spec (#27).
- **Comment / close**: `gh issue comment <n> --body "..."`, `gh issue close <n> --comment "..."`
- **Resolve a ticket**: comment what was built, then close. Where the code departs from the spec (#27), record an
  ADR in `docs/adr/` and edit the spec's text to match, in the same pass. Where the spec is silent (a gap), give a
  suggested spec sentence in the resolve comment or hand-back; the orchestrator applies spec edits before the code
  review, so the reviewer reads the current spec.
- **Labels**: `gh issue edit <n> --add-label "..."`; a missing label is created with `gh label create`

## Wayfinding operations

Used by `/wayfinder`.

- **Map**: one issue labelled `wayfinder:map`.
- **Child ticket**: a GitHub sub-issue of the map, labelled `wayfinder:<research|prototype|grilling|task>`.
  Link with `gh api --method POST repos/oyvij/ants/issues/<map>/sub_issues -F sub_issue_id=<child-db-id>`.
- **Blocking**: native issue dependencies,
  `gh api --method POST repos/oyvij/ants/issues/<child>/dependencies/blocked_by -F issue_id=<blocker-db-id>`.
  A db id is `gh api repos/oyvij/ants/issues/<n> --jq .id`, not the `#number`.
- **Frontier**: open sub-issues of the map with no open blocker and no assignee; first in map order wins.
- **Claim**: `gh issue edit <n> --add-assignee @me`, the session's first write.
- **Resolve**: comment the answer, close, append a gist and link to the map's Decisions so far.
