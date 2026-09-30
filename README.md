# skill-atlas

A Kotlin application with a CLI and local web interface that finds `SKILL.md`
files at the head of a Git repository branch, saves them to SQLite, and shows a
readable summary of each skill.

Requires Java 17 or newer, Maven, and Git.

## UI change evidence

Every change to visible web UI behavior must include a current video
demonstration in the pull request description. After implementation and UI
validation are complete, use the repository skill at
`.agents/skills/web-ui-demo/SKILL.md` to record the final behavior immediately
before opening the draft pull request. Replace the video if a later commit
changes visible behavior; changes without visible UI impact should mark the PR
template's `Video demonstration` section as not applicable.

The same skill can be invoked manually as `$web-ui-demo` to record or view a
demo without creating or updating a pull request. All recordings are stored in
`/Users/andrey.mogilev/Projects/Videos/` and remain local unless they are
explicitly uploaded as pull request evidence. Recordings use deliberate typing
and transition pacing plus temporary focus borders or restrained magnification
to direct the viewer to important controls and results.

## Verification

Run `mvn verify` to build the shaded CLI, execute unit tests, and execute the
offline integration tests. The integration tests create temporary Git
repositories and SQLite databases; they do not require network access.

```sh
mvn package
./bin/skill-atlas scan https://github.com/org/repository.git
./bin/skill-atlas scan https://github.com/org/repository.git --branch release/1.0
```

Add this project's `bin` directory to your `PATH` to run the command as
`skill-atlas scan <repository-url-or-folder>` from any directory. A local
folder may be written as a relative or absolute path (for example,
`skill-atlas scan ./example-skills`). Local Git working trees retain the normal
Git branch-and-commit behavior. A local folder that is not a Git working tree
is scanned directly, is labelled `local`, and uses `<NONE>` as its commit.

The default CLI view shows numbered skill names, wrapped descriptions, and all
repository-relative paths. Identical copies share one description and display a
location count. Names come from YAML `name`, then the first Markdown heading,
then the skill directory name. The heading shows the branch and a 12-character
commit prefix. A scan with no skills prints a short message. Errors go to
standard error with a nonzero exit code.

```sh
skill-atlas scan ./example-skills
skill-atlas scan ./example-skills --verbose
skill-atlas scan ./example-skills --json > skills.json
skill-atlas scan ./example-skills --color never
skill-atlas scan --help
```

`--verbose` preserves the detailed plain-text format: full commit, per-location
IDs and source URLs, and “Also found at” paths. `--json` emits a versioned object
with full metadata and every location; it cannot be combined with `--verbose`.
Scan options may precede or follow the target.

Interactive terminals use bold names, cyan accents, muted paths, and red error
labels. `--color auto|always|never` controls styling; automatic mode disables it
for redirected output, `TERM=dumb`, or the presence of `NO_COLOR`. An explicit
`--color always` overrides `NO_COLOR`. JSON and verbose output are always plain.
Known compatible terminals also get clickable paths (iTerm2, WezTerm, VS Code,
Kitty, and Windows Terminal); links are disabled with color or when redirected.
Descriptions wrap using `COLUMNS` (20–500, otherwise 80), measured in Unicode
code points; wide glyphs may occupy extra terminal cells. Paths stay unbroken
for copying. Interactive arrow-key selection is not included in this release.

Discovery is limited to named skill directories under `skills/`,
`.agents/skills/`, `.claude/skills/`, `.codex/skills/`, `.cursor/skills/`,
`.github/skills/`, and `.opencode/skills/` at the repository root. A
`SKILL.md` elsewhere in the repository is ignored.

The default database is in the OS application data directory. Set
`SKILL_SCAN_DB_PATH` to use another SQLite file. File size limits default to
1 MiB per skill and 10 MiB per scan; override them with
`SKILL_SCAN_MAX_FILE_BYTES` and `SKILL_SCAN_MAX_TOTAL_BYTES`.

The scanner accepts HTTPS, HTTP, SSH, Git, and `file://` repository URLs, plus
Git's SSH form (`git@host:owner/repository.git`) and local relative or absolute
folder paths. It uses the installed Git client and its configured credentials
for Git repositories. HTTPS and SSH URLs remain separate repository identities
in the local database.

GitHub repository URLs work with or without a `.git` suffix, for example
`https://github.com/andrey-mogilev/atlas-test`. You can also paste a complete
Markdown link such as `[Atlas test](https://github.com/andrey-mogilev/atlas-test)`
into the web input (or pass it as one quoted CLI argument). The link resolves to
the same repository identity as its URL.

If a standard `https://github.com/owner/repository` URL is inaccessible over HTTPS,
the scanner retries the same repository through `git@github.com:owner/repository.git`
using your existing SSH configuration. This is useful for private repositories
when SSH access is configured but HTTPS credentials are not. Source links and
the database identity retain the supplied HTTPS URL. No credentials are stored
or Git settings changed. Other hosts and explicit custom ports are not retried;
branch, content, and validation failures do not trigger a fallback.

See [.spec/readme.md](.spec/readme.md) for the command contract.

## Duplicate skills

Within a scan, identical `SKILL.md` contents are presented once, with the
description shown once and all locations retained. Matching normalizes only
Windows CRLF line endings to Unix LF; different instructions, whitespace,
front matter, or trailing newlines remain distinct even if names/descriptions
match. The smallest path in bytewise UTF-8 order represents each group; every
location retains its own stable ID. Groups do not merge across scans or repositories.

For example, three identical copies produce `Found 1 skill across 3 locations`
in compact output, with all three paths beneath a single description. Verbose
output uses `Found 1 unique skill across 3 locations` and retains the original
three-line blocks and additional location list. In the web UI, each
group has one card and a locations list (expanded for up to four locations).
Results and history show unique-skill and location counts separately.

SQLite stores normalized contents and descriptions once, referenced by the
individual location/version rows. Existing databases migrate automatically in
one transaction when saving a scan or reading history; IDs, original scan times,
and historical associations are preserved. A failed migration rolls back and
reports a database error. The migration does not edit source repositories.
Stop older running versions before upgrading: older binaries cannot read the
new content-reference schema. For a rollback, keep a pre-upgrade database copy.

The [atlas-test repository](https://github.com/andrey-mogilev/atlas-test) includes
LF/CRLF duplicate copies and a same-description variant. Its default branch is
expected to produce **4 unique skills across 7 locations**.

## Local web interface

```sh
mvn package
./bin/skill-atlas serve
# Or choose another port (0 selects an available port):
./bin/skill-atlas serve --port 8090
```

Open the address printed in the terminal (normally `http://127.0.0.1:8080/`).
The server binds only to this computer. Stop it with Ctrl+C. It uses Java's
built-in HTTP server; no Node.js, frontend build, or additional runtime is needed.

Enter the same repository URL or folder path accepted by `scan`, plus an optional
branch. Relative folder paths resolve from the directory where the server was
started, shown below the input. Use a typed folder path, not a browser upload:
the server needs the real local Git repository and the installed Git credentials.
Git scans read committed files; non-Git folders read their current files and
require an empty branch field.

The page shows the branch, full commit, skill names, paths, stable IDs, source links,
and descriptions. Results can be filtered as you type by a case-insensitive
name or description substring; the visible skill and location counts update to
match. The filter resets when another scan or history entry is opened. “View CLI
output” provides the same formatted text as `scan --verbose`, with
a copy button. Non-web source URLs are displayed as text because browsers may
block local file and SSH links. Scans run in the background, one at a time;
another submission receives a busy message. Reloading the page in the same tab
can resume tracking the active job. The latest ten jobs remain available until
server shutdown; saved results remain in SQLite.

Select a skill card to open a separate related-skills panel. It ranks every
other unique skill in that scan by a 0–100% lexical similarity score. The score
uses smoothed TF-IDF cosine similarity over normalized names and descriptions,
with name terms included twice and common English filler words omitted. It is a
local, deterministic relevance hint rather than a semantic-equivalence claim;
skills with different vocabulary can score low even when their purposes overlap.

Scan history opens the latest 50 distinct saved scans, including CLI scans,
without contacting the source again. It shows the originally persisted findings,
not a fresh scan. In particular, plain folders use the existing `local` / `<NONE>`
identity: their saved history is not a chronological series of filesystem
snapshots. Re-scanning them can display current content while history retains
previously saved versions for existing paths.

The web interface uses the same database and `SKILL_SCAN_*` settings as the CLI.
Set these before starting the server. Credentials and database paths cannot be
configured from the web page. The UI requires JavaScript and a modern browser.
