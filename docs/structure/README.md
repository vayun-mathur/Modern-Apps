# Structure ratchets

Cheap, build-free checks in `tools/structure_check.py` (Python 3 stdlib
only), wired into `./check` / `check.bat`. They run on every `./check`
invocation; `./check structure` runs only them. Each check passes on the
current tree and names every violation individually.

## Entry points

```
python3 tools/structure_check.py all          # all three checks
python3 tools/structure_check.py wear         # protocol-pair drift only
python3 tools/structure_check.py generated    # generated-source guard only
python3 tools/structure_check.py exceptions   # exception-marker ratchet only
python3 tools/structure_check.py exceptions --regen   # regenerate exceptions.tsv
./check structure                             # same as `all`, via ./check
./check <module> [...]                        # structure checks run first
```

Manifests in this directory (tab-separated, `#` comment lines):

| File | Format |
| :--- | :--- |
| `wear-protocol-pairs.tsv` | `pair_id<TAB>phone_path<TAB>wear_path` |
| `generated-sources.tsv` | `path<TAB>generator command` |
| `exceptions.tsv` | `type<TAB>file<TAB>reason<TAB>count` (generated; see below) |

## 1. Protocol-pair drift (`wear`)

Gradle cannot let one app module depend on another, so each Wear OS
companion carries a copy of the phone's `Wear*Protocol.kt` under its own
package. `wear-protocol-pairs.tsv` lists the 13 duplicated pairs
(`library/wearlink/WearLinkProtocol.kt` is a shared library both sides
depend on, not a copy, and is deliberately not listed).

Normalization per file: strip all comments with a string-aware scanner
(Kotlin `//`, nested `/* */`, `"..."`, `"""..."""`, char literals), drop
`package`/`import` lines, then tokenize (`[A-Za-z_]\w*`, numbers, single
punctuation chars). The two token streams must be identical; a violation
names the pair, both token counts, and the first differing token with
context. Comment-only or package/import-only edits do not trip it; any
code or string-literal change on one side does.

## 2. Generated-source guard (`generated`)

A file counts as generated when, within its first 40 lines, a line matches
one of: `BEGIN GENERATED`, `@generated`, `GENERATED` at the start of a
comment line (e.g. `//! GENERATED from ...`, ` * GENERATED — ...`),
`Code generated ... DO NOT EDIT`, `// Generated constants|constant tables`,
or `Do not hand-edit`. Scan scope: `*.kt`/`*.rs`, excluding the root
AGENTS.md "Never scan" dirs (`build/`, `target/`, `third_party/`,
`analysis/`, `personal/`, ...) plus `lint-rules/`, `tools/`, `docs/`
(which quote header strings as data).

Every such file must be listed in `generated-sources.tsv` with the
command that regenerates it (a recorded generator with no script in the
repo is stated as such in the manifest), and every listed file must
still exist and still carry its header. Presence only: the check never
regenerates anything.

## 3. Exception-marker ratchet (`exceptions`)

`exceptions.tsv` inventories every escape-hatch marker occurrence in
Kotlin sources (`*.kt`/`*.kts`, same exclusions as above): the canonical
types from root AGENTS.md §2 — `PACKAGE_JNI` (the verbatim
`// PACKAGE STRUCTURE EXCEPTION (JNI)` marker; reason column records
that it carries no free-text reason), `RAW_SCAFFOLD`, `TOAST`,
`RAW_ANIMATION`, `SCAFFOLD_LOADING`, `SCAFFOLD_SEARCH`,
`SCREENSHOT_COVERAGE`. The reason column is the verbatim text after the
marker colon in the source.

`./check` fails when the TSV does not match the tree exactly (type,
file, reason, count). Line numbers are deliberately not recorded, so
moving a marker inside its file does not churn the inventory. To add or move a marker intentionally: edit the
source, run `python3 tools/structure_check.py exceptions --regen`,
review the `exceptions.tsv` diff (that review is the reason edit), and
include both in the change. Malformed markers (missing colon/reason)
are still caught by lint (`ExceptionMarkerFormatDetector`); this
ratchet catches growth and drift.
