# One Build and ship Process picks its Path on the start form

Ants shipped five Processes that turned a prompt into a pull request: Ship with me, Ship by proxy, Ship as proposed,
Ship overnight and Ship small. Their names said when or how they ran, not what set them apart, and the user couldn't
tell by proxy, as proposed and overnight apart. What sets them apart is who answers the Rounds and where the Run
first waits on the user.

- **One shipped Process, Build and ship**, with a required start-form field **Path**, an engine `enum` shown as a
  dropdown: Human in the loop (was Ship with me, and the stored choice), Answered as me (was Ship by proxy), Take the
  recommendations (was Ship as proposed), Hands-off until review (was Ship overnight), Feeling lucky (was Ship small).
  Start refuses a value that isn't one of them.
- **Gateways on `${path}` choose the route.** A **Feeling lucky?** gateway after Scout and clone; one Grill sub-process
  whose **Who answers?** gateway picks Answer the Round, Accept recommendations or the answerer, and whose
  **Hands-off?** gateway skips Clone new repos; one To-spec sub-process whose **Hands-off?** gateway skips Approve the
  Spec. Each Path keeps the behaviour of the Process it replaces, Round caps included (ADR 0010, ADR 0017).
- **Feeling lucky's Tasks have ids of their own**, `implementproposal`, `pushproposal` and `prproposal`, as a BPMN id
  is unique in a file; Update the Vault reads `spec` and `review` with a JUEL conditional on `${path}`.
- **The four Grill Templates and To-spec and approve stay** as building blocks a user draws with; Build and ship's own
  Grill and To-spec are not copies of them.

## Considered Options

- **Five checkboxes**: only one may be ticked, which a dropdown says by itself.
- **Two fields, who answers and whether the Spec is approved**: more combinations than anyone asked for.
- **A call activity per old Process**: five diagrams to keep in step again, behind one start form.

## Consequences

- An install that ran an older Ants still has the five old Processes deployed; Ants no longer ships them, and a
  start leaves them as they are.
