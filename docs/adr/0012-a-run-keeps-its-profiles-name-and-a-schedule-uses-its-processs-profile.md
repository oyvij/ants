# A Run keeps its Profile's name, and a Schedule uses its Process's Profile

Story 19 asked for named Vaults and Source maps that every Process can pick, but every Run used the defaults. #90
pairs one Vault with one Source map as a named **Profile** (`work`, `private`), picked per Process and per Run.

- **The Run keeps the Profile's name** as the Run variable `ants.profile`, set at start, and looks up its Vault and
  Source map in `settings.yaml` by that name whenever it needs them (`Scope.kt`, the one place that decides). A
  Profile renamed or removed while its Run lives gives that Run no Vault and no Source map, never the default
  Profile's, so work and private never mix by accident.
- **No Profiles configured means one implicit Profile**: the default Vault and Source map, and no `ants.profile`. A
  user who never sets up Profiles sees nothing new.
- **A Process names its Profile as the extension property `ants.profile` on the process**, set in the modeler's
  Process properties. The start form prefills it, else the default.
- **A Schedule stores no Profile of its own**: its Run uses the Process's, which lives in the same BPMN as the
  stored values. The ticket asked a Schedule to keep its Profile with its stored values; the Process's property is
  that, with one place to set it.
- **A Profile the settings don't have is a pre-Run reason** ("The Profile x isn't in the settings."), never a
  fallback to the default.
- **Switching the Profile on the start form reloads the page** (`?ants.profile=`), so the Repos field's choices and
  the pre-Run check are the picked Profile's. Typed values are lost on the switch.
- A Run with picked repos keeps its Profile too (ADR 0014; ADR 0011 had it keep none).

## Considered Options

- **Keeping the Vault's and the Source map's names on the Run** instead of the Profile's: survives a Profile rename,
  but a Run no longer says which Profile it ran in, and a renamed Vault breaks it the same way.
- **A Profile field in the Schedule's stored values**: a second place to set what the Process property already says.

## Consequences

- The Answer History stays one, shared by every Profile.
- A Process naming a Profile that a copy of Ants doesn't have can't start there until the Process or the settings
  change.
