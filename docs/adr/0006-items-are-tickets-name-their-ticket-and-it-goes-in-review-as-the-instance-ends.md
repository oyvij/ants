# Items are Tickets: an item names its Ticket, the Ticket goes in-review as its instance ends, and the user's Ticket holds its instance

The spec says an **Items are Tickets** multi-instance honours the Tickets' Blocked by, that Ants sets a Ticket
`in-review` when its instance lands, and that a `ready-for-human` Ticket skips the implementers. It doesn't say how an
item names its Ticket, when the Ticket of a sub-process or sequential instance goes in review, or what the
`ready-for-human` Ticket's own instance does.

- **An item names its Ticket** by its path, file name or number: `tickets/issues/01-hello.md`, `01-hello` or `01`.
  Ants reads the number at the start of the item's last path segment. An item that names no Ticket is `[failed]`.
- **The Ticket goes `in-review` as its instance ends**, not at the landing itself. For a multi-instance Agent Task the
  two are the same moment. A sub-process instance lands each of its Agent Tasks on its own (ADR 0005), so its Ticket
  waits for the last of them, and its dependents never see half of it. A sequential instance never lands, and its
  Ticket goes in review all the same. A Ticket already `done` or `wontfix` is left as it is.
- **The `ready-for-human` Ticket's instance is held too**, saying "Ready for human", until the user marks the Ticket
  done. It then completes without launching anything, its Result `{"outcome": "done", "summary": "Done by the user.",
  "output": {}}`. So the multi-instance ends only once the user's part is done, and Review changes never comes before
  it. Any instance whose Ticket is already `done` completes the same way.

## Considered Options

- **Complete the `ready-for-human` instance at once.** Its dependents would still wait, but with no dependents the
  multi-instance could end, and the Run go on to Review changes, while the user's part is still undone.
- **Set `in-review` from the worker right after each landing.** Simpler for a single Task, but a sub-process instance
  would release its dependents after its first Agent Task.

## Consequences

- A held instance keeps its external task locked without a slot, and Ants checks its Ticket again every poll. A
  restart unlocks it with the other long locks, and its next fetch holds it again.
- The Run page lists each Agent Task instance by its key (`implement-2`), so a held one reads `Blocked by 01`.
