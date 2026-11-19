# CI tags

Tags are markers in square brackets that control what CI runs for a given
commit or pull request. This document is the user guide: syntax, tiers and
the full registry. The registry table below is generated from the code
(`tools/ci/tags.py`) and is checked for drift by a test
(`tools/ci/test_tags.py`) and by the `ci-tags-lint` workflow — edit the
source, regenerate, don't hand-edit.

Three principles tags cannot override:

1. **Tags only weaken or refine — they never grant rights.** No tag enables
   publishing, artifact signing or secret access. A release is always a
   master merge + a git tag + environment approval; a tag cannot press that
   button.
2. **Tier C is never skipped.** Secret scanning, license validation,
   dependency review and signing are out of reach of every tag. They are not
   in the registry and never will be.
3. **Tags are never executed.** The parser knows a closed list; an unknown
   tag is a warning with a did-you-mean hint, never a silent skip. A
   hallucinated skip is the worst possible failure mode, so silence is not
   an option.

## Syntax

Tags can appear anywhere in a commit message, PR title or PR body:

```
feat(paper): inventory sync [compat skip] [discord skip]
```

- Case does not matter: `[COMPAT SKIP]` ≡ `[compat skip]`, same for stray
  spaces and tabs inside the brackets.
- Values use `=`: `[compat mc=26.3 only]`; a comma-separated list has no
  spaces: `mc=26.3,1.21.11`.
- Several tags in one string are separate markers.
- Conflicting tags: the last one wins, except `[build full]`, which overrides
  build-scope tags (see the registry).
- Muscle-memory aliases: `[skip compat]` ≡ `[compat skip]`, `[no ci]` ≡
  `[ci skip]` — full list below.

## Tiers

| Tier | Meaning | Where it applies |
|---|---|---|
| A | cosmetic / informational: safe for anyone, including forks | dev, PR, master |
| B | affects the artifact | dev / PR only; **ignored on master** with a "release must be full" warning |
| Emergency | escape hatch for maintainers; audited | push / PR, but never in forks |
| C | security and integrity (secret scan, license, dependency review, signing) | **never skipped**, not controllable by tags |

Practical consequences:

- **On master** the tags [paper only], [velocity only], [common only],
  [no folia], [tests skip], [compat ignore mc=<v> reason=<why>] and
  [discord release] are ignored: you get the full gate plus a WARN in the
  summary. Releases always build in full.
- **[hotfix], [release] and [no release]** are tier B, but their whole point
  is the master-side flow (hotfix process, release candidate, merge without a
  release job), so they work on master too. That is a deliberate exception,
  not a loophole.
- **In fork PRs** only tier A tags apply. Tier B and Emergency tags from a
  fork are silently discarded (fork secrets are read-only anyway; this is the
  extra belt on the suspenders).
- **The native [ci skip]** (`[skip ci]`, `[no ci]`) remains the emergency
  brake. Its use on master merges is tracked by the audit and alerts a
  maintainer.

## Values and validation

Values are accepted only from fixed patterns — no shell interpolation, no
eval; a commit message and a PR body are treated as untrusted input:

- `mc=<v>` — a version (`1.21.11`, `26.3`, `26.3-pre2`), comma-separated
  list for several;
- `java=<v>` — same format, for the toolchain matrix;
- `reason=<why>` — a short slug (`a-z 0-9 . _ -`); required for
  `[compat ignore mc=<v> reason=<why>]` — without a reason the tag does
  nothing;
- `retry=<n>` — an integer from 1 to 10;
- `[changelog: <text>]` — up to 140 characters; the text lands in the
  changelog verbatim. Longer input is rejected with a warning, not
  truncated.

An invalid value means the tag does not apply and a warning is printed.
There is no such thing as a half-applied tag.

## Registry

The source of truth is `tools/ci/tags.py` (`python tools/ci/tags.py --list`).
The "Works on" column reflects scope; tier B tags are additionally warned
about on master.

| Tag | Aliases | Tier | Works on | Effect |
|---|---|---|---|---|
| `[ci skip]` | `[no ci]`, `[skip ci]` | Emergency | push / PR | nothing builds, nothing runs (native GitHub skip) |
| `[docs only]` | `[only docs]` | A | dev / PR | markdown/docs lint only; compilation skipped |
| `[build none]` | - | A | dev / PR | lint and parse only, no compilation |
| `[hotfix]` | - | B | everywhere | forces the hotfix flow (hotfix/* branch, faster release checklist) |
| `[paper only]` | - | B | dev / PR | build the paper jar only |
| `[velocity only]` | - | B | dev / PR | build the velocity jar only |
| `[common only]` | - | B | dev / PR | build common/api without the shadowed jars |
| `[no folia]` | - | B | dev / PR | skip folia compilation and smoke tests |
| `[build full]` | - | A | everywhere | force a full build; overrides conflicting build-scope tier B tags |
| `[compat skip]` | `[skip compat]` | A | dev / PR | compat matrix not run (smoke on latest only) |
| `[compat full]` | - | A | everywhere | full compat matrix, even on dev pushes |
| `[compat mc=<versions> only]` | - | A | dev / PR | run only the listed MC versions, comma-separated |
| `[compat ignore mc=<v> reason=<why>]` | - | B | dev / PR | red matrix cell counts as non-blocking (reason required) |
| `[compat java=<v>]` | - | A | dev / PR | limit the toolchain matrix |
| `[compat nightly]` | - | A | dev | results marked as nightly; the release table stays untouched |
| `[discord skip]` | `[skip discord]` | A | dev | no dev build notices in Discord (release announcements unaffected) |
| `[discord release]` | - | B | dev | also publish this dev build to the release channel |
| `[no ping]` | - | A | everywhere | post without @-mentioning the role |
| `[changelog skip]` | `[skip changelog]` | A | everywhere | commit does not appear in the changelog |
| `[changelog: <text>]` | - | A | everywhere | custom changelog line instead of the commit subject |
| `[release notes]` | - | A | master | long post-release Discord announcement (master merges) |
| `[release]` | - | B | PR to master | mark this PR as a release candidate (highlighting, full checks) |
| `[no release]` | - | B | master | merge to master without a tag/release job (docs-only zero changes) |
| `[changelog breaking]` | - | A | everywhere | highlight the entry in the Breaking section |
| `[commits count-reset]` | - | Emergency | maintainer | recalculate build-<N> (manual, maintainer-only) |
| `[tests skip]` | `[skip tests]` | B | dev / PR | skip unit tests (smoke remains) |
| `[lint skip]` | `[skip lint]` | A | dev / PR | skip lint |
| `[flaky retry=<n>]` | - | A | everywhere | re-run failed test jobs up to n times |
| `[ci debug]` | - | A | everywhere | step debug logging on this push |
| `[tags audit]` | - | A | everywhere | force the tags-audit job (usage statistics) |

## Unknown tags and typos

Any bracket group missing from the registry is a warning, not an error:

```
[kompat skip] unknown tag — not honored. did you mean '[compat skip]'?
```

Did-you-mean computes the Levenshtein distance to every known form and
suggests the closest one (threshold ~3 characters). If nothing is close
enough, the warning has no suggestion. No unknown tag is ever executed and
none of them fails the build: the unknown-tag lint comments on the PR but
does not block the pipeline — forks should not suffer for someone else's CI.

## Conflicts

- `[build full]` + a build-scope tag (`[paper only]` and friends) → full
  build; the summary gains a "[build full] overrides …" line. Works in either
  order.
- `[compat skip]` + `[compat full]` → the last one wins.
- `[release]` + `[no release]` → both land in the summary; the maintainer
  decides.
- `[changelog skip]` + `[changelog: <text>]` → the skip wins.

## Examples

```
docs: fix typo in README            [docs only]              # docs lint only
feat(paper): inventory sync         [compat skip]            # matrix not run
feat: red flatblock cell on 1.21.11 [compat ignore mc=1.21.11 reason=broken-flatblock]
chore: reformat                     [lint skip] [discord skip]
feat: drop flatblock support        [changelog: drop flatblock support] [changelog breaking]
```

## Adding or changing a tag

The single source of truth is the registry in `tools/ci/tags.py`. The order:

1. Edit `REGISTRY` (and the parser, if the tag carries parameters).
2. `python tools/ci/tags.py --list` — regenerate the table above.
3. `python -m pytest tools/ci/test_tags.py` — the suite must be green;
   doc ↔ registry sync is enforced by
   `test_docs_list_every_registered_tag_and_alias` and by the `ci-tags-lint`
   workflow via `python tools/ci/tags.py --check-docs docs/ci-tags.md`.

A tag without tests covering every registered case is not accepted.
