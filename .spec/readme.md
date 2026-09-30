# Skill repository scanner — specification

## 1. Purpose

Provide a command-line tool that inspects a Git repository or local folder,
discovers AI skills declared by `SKILL.md` files, and persists the findings in a
local database. Git repositories are scanned at the head commit of their
default branch or an explicitly requested branch.

The initial command is:

```text
scan <repository-url-or-folder> [--branch <branch-name>] [--verbose | --json] [--color auto|always|never]
```

For every unique skill, the command shows its name, human-readable description,
and all repository-relative paths. Verbose and JSON modes expose stable local
IDs, full source links, and the full scanned commit SHA.

## 2. Scope

### Included in the first release

- Accept a repository URL or local relative/absolute folder path and an
  optional branch override.
- Verify that the repository exists and is accessible to the current user.
- Resolve the repository's default branch, or a requested branch override.
- Read the selected branch's current head commit.
- Locate `SKILL.md` files inside recognized skill directories in that commit.
- Extract the skill description from each file.
- Persist repository, scan, and skill-finding data locally.
- Print one description per unique skill with all discovered locations.
- Report actionable failures with non-zero exit codes.

### Explicitly out of scope

- Scanning multiple branches in one invocation or refs other than an explicitly
  requested branch.
- Scanning Git history beyond the selected branch's current head.
- Executing repository code or skill instructions.
- Editing the repository or pushing changes.
- Authentication setup or credential storage beyond the Git client's existing
  credential mechanism.
- Semantic validation of the skill contents beyond the minimum extraction rules
  below.
- Remote hosting, multiple users, or cross-machine database synchronization.

## 3. Command-line interface

### Synopsis

```text
skill-atlas scan <repository-url-or-folder> [--branch <branch-name>] [--verbose | --json] [--color auto|always|never]
```

`<repository-url-or-folder>` may be a Git URL accepted by the installed Git
client (for example `https://github.com/org/project.git` or
`git@github.com:org/project.git`) or a relative/absolute local folder path.
GitHub URLs do not require the `.git` suffix. A complete Markdown link
`[label](https://host/owner/repository)`, optionally wrapped in a single pair of
inline-code backticks and surrounding whitespace, is unwrapped before URL
validation. Only HTTP(S) links use this convenience syntax; local folder paths
are otherwise preserved exactly. The original input remains in `requested_url`,
while the extracted URL supplies the canonical repository identity.
For a local Git working tree, Git resolution behaves as it does for a `file://`
repository URL. For a local folder that is not a Git working tree, the scanner
reads the folder directly, reports branch `local` and commit `<NONE>`, and does
not accept `--branch`.

`--branch <branch-name>` is optional. When provided, the scanner uses that named
remote branch instead of resolving the repository's default branch. It accepts
only a valid Git branch short name (for example, `release/2026.1`), not an
arbitrary ref, tag, or commit SHA. A missing, inaccessible, or non-branch ref
is a branch-resolution failure (exit `4`).

Options may precede or follow the single target. Unknown/duplicate options,
missing values/targets, extra targets, invalid color modes, and combining
`--verbose` with `--json` fail with exit 2 before scanning or persistence.
Argument-validation diagnostics are always plain text.
`skill-atlas --help` and `skill-atlas scan --help` (also `-h`) print help and
exit 0 without accessing sources or the database.

### Compact success output (default)

Show unique-skill and total-location counts, then the selected branch and first
12 characters of the commit (`<NONE>` remains unchanged). In deterministic
representative-path order, show numbered bold skill names, a location count for
duplicates, a description once per group, and every indented path. IDs and full
URLs are omitted. Missing descriptions display `(no description)`.

```text
Found 1 skill across 2 locations
on main at 9f0a1b2c3d4e

1. Release notes  [2 locations]
   Guidance for preparing release notes.
    .agents/skills/release/SKILL.md
    skills/release/SKILL.md
```

Descriptions collapse tabs/newlines and wrap to `COLUMNS` minus indentation.
Valid widths are 20–500; missing/invalid values use 80. Wrapping counts Unicode
code points (not display cells), preserves surrogate pairs, and splits long
words; wide glyphs may consume extra cells. Paths and titles remain unbroken.
This is a static report, not an interactive selector.

Color defaults to `auto`, enabled only when Java reports an attached console,
`TERM` is not `dumb`, and `NO_COLOR` is absent (even an empty value disables it).
`--color always` overrides terminal detection and `NO_COLOR`; `never` disables
color. Compact output uses bold names, cyan numbers/counts, and dim metadata;
errors use a red label when enabled. Verbose text remains unstyled. Known
OSC-8-compatible terminals (`TERM_PROGRAM` of iTerm.app, WezTerm, or vscode;
`TERM=xterm-kitty`; or `WT_SESSION` present) get clickable compact paths only
when color is enabled and a console is attached. HTTP(S) links use the scanned
commit; local file links point to the working filesystem, which may differ
from a scanned Git commit. Other schemes and credential-bearing links are not
made clickable. Redirected output never contains automatic ANSI/OSC sequences.
Human-readable source fields and errors escape control/format characters to
prevent terminal escape injection; source files and stored metadata are unchanged.

### Verbose success output (`--verbose`)

Write a heading with the selected branch and full commit SHA, followed by a
three-line block for every unique skill, in deterministic path order
(bytewise ascending repository-relative path). Identical contents are grouped
according to section 11. Separate blocks with a blank
line. The first line contains the repository-relative path and stable skill ID;
the next two contain the source link and description.
Additional locations appear in an indented `Also found at:` list, each with its
own path, stable ID, and link, without repeating the description. If copies were
grouped, the heading reports `Found U unique skills across L locations on ...`
(using singular `skill` for U=1). When there are no duplicates, the previous
heading and three-line blocks remain unchanged.

```text
Found 1 skill on main at 9f0a1b2c3d4e...:

skills/release/SKILL.md  [skl_0123456789abcdef0123456789abcdef]
  Link: https://github.com/acme/agent-skills/blob/9f0a1b2c3d4e.../skills/release/SKILL.md
  Description: Guidance for preparing release notes.
```

The link points to the exact scanned file on GitHub, GitLab, and Bitbucket.
For other Git hosts and `file://` repositories, it points to the repository URL
because no portable web file URL exists. An absent description is displayed as
`(none)`; the stored description remains empty.

An empty successful scan prints a `No SKILL.md files found` message and exits
`0`.

Diagnostic messages go to standard error only.

### JSON success output (`--json`)

Emit exactly one JSON object plus a newline, with no ANSI/OSC styling even when
`--color always` is specified. Keys are `schemaVersion` (1), `target` (canonical
repository identity), `branch`, `commit` (full), `skillCount`, `locationCount`,
and `skills`. Each skill has `name`, `description`, `contentHash`, and `locations`;
each location has `id`, `path`, and `link`. Ordering follows the same bytewise
path/group ordering as text. Metadata retains original values with JSON escaping
rather than text sanitization; absent descriptions remain empty strings. Empty
scans have zero counts and an empty `skills` array. Errors retain existing exit
codes and plain stderr diagnostics, with no partial JSON on stdout. JSON errors
are not a separate structured protocol.

### Exit codes

| Code | Meaning |
| --- | --- |
| `0` | Scan completed, including the case where no skills exist. |
| `2` | Invalid command-line arguments or malformed repository URL. |
| `3` | Repository does not exist, is not a Git repository, or is inaccessible. |
| `4` | The selected branch or its head commit could not be resolved. |
| `5` | The repository could be read but scan data could not be persisted. |
| `1` | Any other unexpected operational failure. |

## 4. Repository resolution and scanning behavior

1. Validate the supplied URL before making a network request.
2. If `--branch` was supplied, resolve that exact remote branch. Otherwise, ask
   Git for the remote's advertised `HEAD` symbolic reference and use its target
   as the branch. This deliberately does not assume the default branch is named
   `main` or `master`.
3. If the remote cannot be contacted, cannot be read, does not advertise a
   default branch when one is needed, or the selected branch does not resolve to
   a commit, fail without writing a partial scan.
   Exception: an inaccessible standard HTTPS GitHub repository may be retried
   through the same repository's SSH transport as specified below.
4. Obtain the exact full SHA of that branch's head.
5. Fetch the selected commit shallowly, requesting tree and blob objects on
   demand where the remote supports partial clone.
6. Enumerate only the following repository-root skill directories and their
   descendants: `skills/`, `.agents/skills/`, `.claude/skills/`,
   `.codex/skills/`, `.cursor/skills/`, `.github/skills/`, and
   `.opencode/skills/`. A skill must be in a named subdirectory under one of
   these roots, with a file named exactly `SKILL.md`. Ignore every `SKILL.md`
   outside these roots, files directly in a root, symlinks, submodule entries,
   directories, and case variants such as `skill.md`.
7. Read each matching regular file from the resolved commit, not from a mutable
   working tree.
8. Extract its description according to section 5.
9. In one database transaction, store the completed scan and upsert all findings.
10. Commit the transaction, then print records in path order.

For a local non-Git folder, scan the same named skill directories directly from
the filesystem, without following symlinks. The canonical repository identity
is its normalized absolute `file://` URI. It is stored as branch `local` and
commit `<NONE>`; repeated scans are therefore idempotent for unchanged paths.

The scanner must never run hooks, shell scripts, package-install commands, or
any other repository-provided executable content.

For an HTTPS GitHub URL with exactly an owner and repository path and the default
port (implicit or 443), an access failure (category 3) triggers one retry through
`git@github.com:owner/repository.git`. Git uses the existing SSH keys, agent, and
configuration; the scanner does not set up or store credentials or change Git
configuration. The entire scan is retried with the same branch override. Other
hosts, non-HTTPS inputs, custom ports, and URL paths containing extra segments
are not eligible. Validation, branch-resolution, content, and persistence errors
are not retried. If both transports are inaccessible, report category 3 with the
original HTTPS URL and guidance to check repository access and Git credentials.
Successful fallback keeps the original HTTPS canonical identity and source
links; explicitly supplying the SSH URL still creates a separate identity.

## 5. `SKILL.md` description extraction

The scanner is intentionally tolerant of skills that do not follow one exact
format.

1. If the file begins with YAML front matter and it contains a scalar
   `description` key, use that value after trimming surrounding whitespace.
2. Otherwise, use the first non-empty Markdown paragraph after any YAML front
   matter and the first level-one title, if present. Collapse internal line
   breaks to single spaces and trim surrounding whitespace.
3. Otherwise, save and output an empty description.

The full source content should also be stored so that future versions can
re-extract metadata without revisiting the remote. Invalid front matter is not
a scan failure; it falls back to Markdown extraction.

Display names use a nonblank scalar YAML `name`, then the first ATX Markdown
heading outside fenced code blocks after front matter (levels 1–6), then the
parent directory name. BOM and LF/CRLF are supported. Invalid front matter or
non-scalar/blank names fall back to Markdown. Names are derived from source
contents on scan and stored contents on history read; no schema migration is
needed. Names never affect duplicate matching or stable location IDs.

## 6. Local database

Use SQLite. The default database path is platform-specific application data,
with an override through `SKILL_SCAN_DB_PATH`. The tool creates the parent
directory and schema on first run.

Suggested schema:

```text
repositories
  id                 integer primary key
  canonical_url      text unique not null
  created_at         timestamp not null
  updated_at         timestamp not null

scans
  id                 integer primary key
  repository_id      integer not null references repositories(id)
  requested_url      text not null
  scanned_branch     text not null
  commit_sha         text not null
  scanned_at         timestamp not null
  unique(repository_id, scanned_branch, commit_sha)

skills
  id                 text primary key            -- skl_<UUIDv4 without hyphens>
  repository_id      integer not null references repositories(id)
  source_path        text not null
  created_at         timestamp not null
  updated_at         timestamp not null
  unique(repository_id, source_path)

skill_contents
  hash               text primary key            -- SHA-256 of CRLF-normalized UTF-8 content
  description        text not null
  content            text not null               -- CRLF normalized to LF

skill_versions
  id                 integer primary key
  skill_id           text not null references skills(id)
  scan_id            integer not null references scans(id)
  content_hash       text not null references skill_contents(hash)
  unique(skill_id, scan_id)
```

Identity rules:

- A skill location is identified by canonical repository URL plus the relative
  `SKILL.md` path.
- Its `id` is generated once, when that logical skill is first observed, and is
  reused on later scans.
- A `skill_versions` row records what was found at each distinct commit.
- Identical normalized contents and their descriptions are stored once in
  `skill_contents`, including when multiple locations/versions reference them.
- Re-scanning an already recorded repository/commit is idempotent: it must not
  create duplicate logical skills, scans, or versions, but it still prints the
  same records for that commit.

## 7. Failure and safety requirements

- Treat authentication failures and permission denials as inaccessible
  repositories; do not expose credentials or transport command output in normal
  messages.
- Include the requested URL and a concise reason in errors, for example:
  `error: repository is inaccessible: https://example.invalid/acme/skills.git`.
- Use bounded network and Git operation timeouts.
- Clean up temporary Git directories on success and failure.
- Enforce configurable limits for individual `SKILL.md` size and total scanned
  skill content to prevent resource exhaustion. Exceeding a limit fails the scan
  before database persistence.
- Do not log skill file contents by default.
- Database writes are atomic; no successful-looking scan record may be left
  behind after a failed scan.

## 8. Acceptance criteria

1. Given an accessible repository whose default branch is `trunk`, `scan` uses
   `trunk` rather than assuming `main`.
2. Given `--branch release/2026.1`, `scan` uses that branch even when the
   repository's default branch differs; a missing branch exits `4` with no
   stdout or database changes.
3. Given multiple `SKILL.md` files, stdout contains one description per unique
   content group, ordered by the representative path, with all locations listed.
   A `SKILL.md` under `docs/` or `.claude/notes/` is not reported.
4. Compact output shows names, paths, descriptions and a commit prefix.
   `--verbose` includes the full SHA, stable IDs and links; JSON retains all
   metadata and locations without terminal styling.
5. A second scan at the same head is idempotent and emits the same blocks.
6. After a new selected-branch commit changes a skill, the same logical `id` is
   emitted with the new commit and a new stored version.
7. A missing/private/inaccessible repository exits `3`, prints no stdout, and
   commits no database changes.
8. A repository with no `SKILL.md` files exits `0`, prints an empty-scan message,
   and stores a completed scan.
9. No repository-controlled executable content is invoked.
10. Given `scan ./plain-folder` where the folder is not a Git working tree,
    matching skills are scanned from the folder and output reports `local` at
    `<NONE>`.

## 9. First-release choices

- Default output is compact text; verbose text and versioned JSON are available.
- URLs are normalized within their transport form. HTTPS and SSH spellings are
  separate repository identities.
- An unreadable or non-UTF-8 `SKILL.md` aborts the scan before persistence.
  Malformed YAML front matter falls back to Markdown description extraction.
- The default limits are 1 MiB per `SKILL.md` and 10 MiB total, configurable via
  `SKILL_SCAN_MAX_FILE_BYTES` and `SKILL_SCAN_MAX_TOTAL_BYTES`.
- Private repositories work through the user's existing Git credentials.

## 10. Local web interface

### Startup and architecture

`skill-atlas serve [--port <0-65535>]` starts the local web interface. The default
port is 8080; 0 asks the OS for an available port. Invalid arguments exit 2;
startup or bind failures exit 1. Startup prints the actual address and working
directory. Users open the address manually and stop the process with Ctrl+C.

The application remains a Java 17+ shaded JAR, using the JDK HTTP server with
bundled HTML, CSS, and JavaScript. There are no external browser resources or
frontend build dependencies. CLI and HTTP adapters call the same `ScanService`,
which validates targets, invokes `GitScanner`, and commits through
`SkillDatabase`. Both adapters use the same grouping; web text exports use the
verbose CLI formatter, independent of terminal settings.
Commands, error codes, and stable location identities are preserved; the storage
schema is migrated as described in section 11. Configuration is read from the server's
environment at startup; a browser cannot change it.

### Inputs, execution, and outputs

- The form accepts a repository URL or typed local folder path and an optional
  branch. An empty branch field means no override. Relative folder paths resolve
  from the server's startup working directory, which the page displays.
- Bare GitHub URLs without `.git` and complete copied Markdown repository links
  use the same shared target resolution and HTTPS-to-SSH fallback as the CLI.
- Uploads and browser folder pickers are not used: scanning needs filesystem
  paths and the user's existing Git credentials. Local Git repositories still
  scan committed files; plain folders still reject a branch override.
- A scan starts asynchronously and returns a job ID. One scan runs at a time
  per server; additional submissions fail with HTTP 409 instead of queuing.
  History and status requests remain available during scanning.
- The page polls a job until it completes or fails. Running status is
  indeterminate, not a fabricated percentage. A same-tab reload can resume
  polling using session storage; scanning still works when storage is disabled.
- Successful results show branch, full commit, names, stable IDs, paths, source
  links, and descriptions in the scanner's deterministic order. Missing
  descriptions display `(none)`. Empty scans have an explicit successful empty
  state. A client-side text filter matches a case-insensitive substring of the
  skill name or description, updates visible skill and location counts, and
  shows an explicit no-match state. It resets when a different result opens.
- Expandable, copyable text uses the exact `scan --verbose` formatter. HTTP(S) source links
  are clickable; other source URLs are copyable text. Descriptions and source
  paths are rendered as text, never interpreted as HTML or skill instructions.
- Failed jobs carry the same numeric error categories as the CLI (1–5) and a
  concise message. No raw Git output, credentials, source content, or stack trace
  is logged by HTTP handlers. Unexpected server failures use a generic message.
- Completed jobs are retained in memory up to ten jobs per server, with the
  oldest removed when a new job is submitted. No full source contents are kept
  in job results. Jobs do not survive a server restart; saved scans do.

### Saved scan history

History shows the newest 50 distinct saved scans by original scan time and ID,
including CLI-created entries. Selecting an entry loads its persisted findings
and formatted output without scanning again. Stable skill IDs allow findings
at different commits to be compared. Full raw skill contents are not exposed.
Reads of a missing database return empty history and do not create a database.
Read failures are displayed with error category 5.

Existing persistence semantics apply: rescans of a repository, branch, and
commit reuse the scan record and its original timestamp. Non-Git folders use
`local` and `<NONE>` for every invocation, so their saved entry is not a sequence
of filesystem snapshots. Existing skill versions remain as first persisted;
new paths can add versions to that record. Live scan results still reflect the
files just read. History is explicitly a view of stored data.

### HTTP contract and local access

The server listens exclusively on IPv4 `127.0.0.1`. Host headers must match
`127.0.0.1:<port>` or `localhost:<port>`. Cross-origin and browser cross-site
requests are refused. All `/api/` routes require the per-process random
`X-Atlas-Token` supplied in the local HTML page; no permissive CORS is enabled.
This prevents third-party pages from submitting scans or reading local history.
It is not an authentication system against other processes on the same machine.

| Route | Contract |
| --- | --- |
| `GET /` | Form, results, and history UI. |
| `POST /api/scans` | URL-encoded `target` and optional `branch`; HTTP 202 with `{id,status:"running"}`. |
| `GET /api/jobs/<id>` | `{id,status}`; completed jobs add `result`, failed jobs add `code` and `message`. |
| `GET /api/history` | Up to 50 entries with `id`, `target`, `branch`, `commit`, `scannedAt`, unique `skillCount`, and `locationCount`. |
| `GET /api/history/<id>` | Persisted result with `target`, `branch`, `commit`, `findings`, and formatted `text`. |

Each finding represents a group and contains representative `id`, `name`, `path`,
`link`, one `description`, and `locations` (each with `id`, `path`, and `link`). Results
also include `locationCount`; the number of findings is the unique count. API responses use
JSON. Unsupported methods return 405 with `Allow`; unknown jobs/scans return
404; access checks return 403; malformed/unknown form fields return 400;
unsupported content types return 415. Request bodies are capped at 8 KiB (413).
Existing file, total content, and Git operation limits apply to scans. HTTP
workers and their pending request queue are bounded. Server shutdown stops
HTTP handling and interrupts the scan worker, allowing up to five seconds for
the worker's Git and temporary-file cleanup before shutdown completes.

Responses disable caching and content sniffing. A restrictive content security
policy permits only bundled scripts/styles and same-origin connections, blocks
framing, and prevents external resource loading. Browser rendering uses text
nodes for repository-controlled content.

### Web acceptance criteria

1. A packaged server serves its assets and scans a local folder through HTTP.
2. A CLI scan and web scan of the same source and database return identical
   formatted output and IDs; Git branch overrides and errors remain compatible.
3. Empty scans, malformed targets, missing folders/branches, size limits, and
   database failures are represented accurately; failed scans persist no data.
4. History includes earlier CLI scans and opens saved results after restart.
5. Concurrent scan submissions receive 409 while status/history remain readable.
6. Missing tokens, foreign origins, and unrecognized hosts cannot read history
   or submit scans; repository descriptions cannot execute browser code.
7. The form, results, and history remain usable at desktop and mobile widths,
   with labelled inputs, visible focus, and announced scan/error status.

## 11. Duplicate skills and content storage

Within one repository scan, group `SKILL.md` files by their full contents after
replacing CRLF with LF. No other normalization applies: trailing newlines,
spaces, front matter, and instruction differences remain significant. Names
and descriptions alone must never merge distinct content. File/total-byte size
limits still count every source file before grouping.

Sort locations in bytewise UTF-8 path order. The first location represents a
group; groups are ordered by their representative paths. There is no new stable
group ID: the representative ID is that path's existing ID and can change when
the set of locations changes. All member IDs remain attached to their original
paths. When one copy changes at a later commit, it forms a separate group with
the same location ID; previous scans retain their original grouping.

The CLI prints one description per group and all location paths; verbose and
JSON modes also expose every location ID and link.
The web UI shows one card per group, with a locations list expanded by default
for up to four locations and collapsed for larger groups. It reports, for
example, `4 unique skills across 7 locations`, including in history. Source
links for each location point to the exact scanned commit where supported.

Storage uses SHA-256 of the normalized UTF-8 text as the `skill_contents` key.
Normalized text and its description are stored once across the database;
`skill_versions` retains a separate association for every skill location/scan.
Hash reuse verifies actual content equality and fails persistence on a collision.
Grouping remains scoped to the selected repository/scan, not globally to all
references to a content row. Original CRLF encoding is not retained in storage;
the complete textual content is otherwise preserved.

On the first save or history read of a legacy database, migrate the old
`skill_versions(description, content)` rows to content references in one SQLite
transaction. Preserve repository/skill/scan/version IDs, timestamps, empty scans,
and every historical association. Failure rolls back the entire migration and
reports category 5. Repeated startup/read/save is idempotent. History reads may
therefore perform a schema upgrade; a nonexistent database still remains absent
on read. Stop older server/CLI binaries before upgrade. Downgrading requires a
pre-upgrade database copy; older binaries cannot use the new schema.

Acceptance tests cover LF/CRLF copies, same-description different instructions,
unchanged repeated scans, a changed copy at a later Git commit, preserved IDs
and links, history reload, successful and failed legacy migration, shared
content storage, and identical packaged verbose CLI/web formatted output.
CLI formatting tests cover names, wrapping, color policy, terminal controls,
hyperlinks, option validation, JSON/empty output, and packaged command modes. The
`andrey-mogilev/atlas-test` default branch contains four unique contents across
seven locations (groups of 3, 2, 1, and 1) plus excluded-file fixtures. Its own
CI validates those counts and preserves an actual CRLF fixture.
