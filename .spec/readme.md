# Skill repository scanner — specification

## 1. Purpose

Provide a command-line tool that inspects a remote Git repository, discovers AI
skills declared by `SKILL.md` files at the head commit of its main branch, and
persists the findings in a local database.

The initial command is:

```text
scan <repository-url>
```

For every discovered skill, the command emits a stable local skill ID, the
repository URL, a human-readable description, and the commit SHA at which the
skill was found.

## 2. Scope

### Included in the first release

- Accept a repository URL as the sole `scan` argument.
- Verify that the repository exists and is accessible to the current user.
- Resolve the repository's main/default branch.
- Read the branch's current head commit.
- Locate every file named exactly `SKILL.md` in that commit, at any depth.
- Extract the skill description from each file.
- Persist repository, scan, and skill-finding data locally.
- Print one machine-readable record per discovered skill.
- Report actionable failures with non-zero exit codes.

### Explicitly out of scope

- Scanning branches other than the main branch.
- Scanning Git history beyond the main branch's current head.
- Executing repository code or skill instructions.
- Editing the repository or pushing changes.
- Authentication setup or credential storage beyond the Git client's existing
  credential mechanism.
- Semantic validation of the skill contents beyond the minimum extraction rules
  below.
- A server, web UI, or cross-machine database synchronization.

## 3. Command-line interface

### Synopsis

```text
skill-scan scan <repository-url>
```

`<repository-url>` must be a Git URL accepted by the installed Git client, for
example `https://github.com/org/project.git` or `git@github.com:org/project.git`.

### Success output

Write newline-delimited JSON (NDJSON) to standard output: exactly one object
for every discovered `SKILL.md`, in deterministic path order (bytewise ascending
repository-relative path).

```json
{"id":"skl_01J...","repository_url":"https://github.com/acme/agent-skills.git","path":"skills/release/SKILL.md","description":"Guidance for preparing release notes.","commit":"9f0a1b2c3d4e..."}
```

Field meanings:

| Field | Meaning |
| --- | --- |
| `id` | Stable, locally generated identifier for this logical skill. |
| `repository_url` | Canonical repository URL resolved for storage and display. |
| `path` | Path of the source `SKILL.md`, relative to repository root. |
| `description` | Extracted skill description; may be an empty string when absent. |
| `commit` | Full 40-hex Git commit SHA scanned. |

An empty successful scan prints no records and exits `0`.

Diagnostic messages go to standard error only; they never contaminate NDJSON
standard output.

### Exit codes

| Code | Meaning |
| --- | --- |
| `0` | Scan completed, including the case where no skills exist. |
| `2` | Invalid command-line arguments or malformed repository URL. |
| `3` | Repository does not exist, is not a Git repository, or is inaccessible. |
| `4` | A default branch or its head commit could not be resolved. |
| `5` | The repository could be read but scan data could not be persisted. |
| `1` | Any other unexpected operational failure. |

## 4. Repository resolution and scanning behavior

1. Validate the supplied URL before making a network request.
2. Ask Git for the remote's advertised `HEAD` symbolic reference. Its target is
   the main branch. This deliberately does not assume the branch is named
   `main` or `master`.
3. If the remote cannot be contacted, cannot be read, does not advertise a
   `HEAD`, or the target does not resolve to a commit, fail without writing a
   partial scan.
4. Obtain the exact full SHA of that branch's head.
5. Fetch only the metadata and blob/tree objects needed to inspect that commit
   (a temporary bare/shallow clone or equivalent Git plumbing is acceptable).
6. Enumerate files in the commit tree whose basename is exactly `SKILL.md`.
   Ignore symlinks, submodule entries, directories, and case variants such as
   `skill.md`.
7. Read each matching regular file from the resolved commit, not from a mutable
   working tree.
8. Extract its description according to section 5.
9. In one database transaction, store the completed scan and upsert all findings.
10. Commit the transaction, then print records in path order.

The scanner must never run hooks, shell scripts, package-install commands, or
any other repository-provided executable content.

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
  main_branch        text not null
  commit_sha         text not null
  scanned_at         timestamp not null
  unique(repository_id, commit_sha)

skills
  id                 text primary key            -- e.g. skl_<ULID>
  repository_id      integer not null references repositories(id)
  source_path        text not null
  created_at         timestamp not null
  updated_at         timestamp not null
  unique(repository_id, source_path)

skill_versions
  id                 integer primary key
  skill_id           text not null references skills(id)
  scan_id            integer not null references scans(id)
  description        text not null
  content            text not null
  unique(skill_id, scan_id)
```

Identity rules:

- A logical skill is identified by canonical repository URL plus the relative
  `SKILL.md` path.
- Its `id` is generated once, when that logical skill is first observed, and is
  reused on later scans.
- A `skill_versions` row records what was found at each distinct commit.
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
2. Given multiple `SKILL.md` files, stdout contains one valid NDJSON object per
   file, ordered by repository-relative path.
3. Each output object includes a non-empty stable `id`, canonical URL, source
   path, description (possibly empty), and the exact scanned SHA.
4. A second scan at the same head is idempotent and emits the same records.
5. After a new main-branch commit changes a skill, the same logical `id` is
   emitted with the new commit and a new stored version.
6. A missing/private/inaccessible repository exits `3`, prints no stdout, and
   commits no database changes.
7. A repository with no `SKILL.md` files exits `0`, prints no stdout, and stores
   a completed scan.
8. No repository-controlled executable content is invoked.

## 9. Decisions to confirm before implementation

- Is NDJSON the desired public output, or should the default instead be a
  human-readable table with a `--json` flag?
- Should both HTTPS and SSH spellings of the same repository be canonicalized to
  one identity, and if so, what hosting providers are in scope?
- Should a malformed/unreadable individual `SKILL.md` abort the full scan or be
  reported as a per-file warning while storing the other skills?
- What default and maximum size limits are appropriate for skill files?
- Is the first release limited to public repositories, or must it support
  private repositories via the user's preconfigured Git credentials?

