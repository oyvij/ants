# A landing instance is named by its Task key, and each Agent Task in it lands on its own

The spec puts an instance in `instances/<task-id>-<n>/` on the branch `<work-branch>--<n>`. A bare `<n>` clashes as
soon as a Run has two parallel multi-instances live at once: two Tasks on parallel paths, or one nested in another.
So Ants names both after the instance's Task key, the key its Transcript and Notification entry already use
(`implement-2`, `draft-2-1`, `build-2.draft`): the folder is `instances/<key>/` and the branch `<work-branch>--<key>`.
For a single multi-instance Task, `<key>` is `<task-id>-<n>`, so the folder is the one the spec names.

The spec doesn't say what lands when the multi-instance is a sub-process or a call activity, which holds more than
one Task. Ants gives each Agent Task in the instance its own folder and lands it when that Task is `done`. A later Task
in the same instance launches after the landing, so it branches from a work branch head that holds the earlier
Task's commits.

## Considered Options

- **`<work-branch>--<n>` as the spec says.** This is simpler to read, but two multi-instances in one Run would share
  branches and folders.
- **One folder per sub-process instance, landed when the instance ends.** This needs a landing hook on the
  sub-process's end and a worktree that outlives its Tasks, which is more machinery for the same commits.

## Consequences

- An instance branch reads `ants/<run>--implement-2`.
- A Built-in Task inside a parallel multi-instance still works on the shared clones.
