# Skill repository scanner — specification

## 1. Purpose

Provide a command-line tool that inspects a remote Git repository, discovers AI
skills declared by `SKILL.md` files at the head commit of its default branch or
an explicitly requested branch, and persists the findings in a local database.

The initial command is:

```text
scan <repository-url> [--branch <branch-name>]
```

For every discovered skill, the command shows its repository-relative path,
stable local ID, source link, and human-readable description. The heading shows
the branch and commit SHA at which the skills were found.

## 2. Scope

### Included in the first release

- Accept a repository URL and an optional branch override.
- Verify that the repository exists and is accessible to the current user.
- Resolve the repository's default branch, or a requested branch override.
- Read the selected branch's current head commit.
- Locate every file named exactly `SKILL.md` in that commit, at any depth.
- Extract the skill description from each file.
- Persist repository, scan, and skill-finding data locally.
- Print a readable three-line block per discovered skill.
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
- A server, web UI, or cross-machine database synchronization.

## 3. Command-line interface

### Synopsis

```text
skill-atlas scan <repository-url> [--branch <branch-name>]
```

`<repository-url>` must be a Git URL accepted by the installed Git client, for
example `https://github.com/org/project.git` or `git@github.com:org/project.git`.

`--branch <branch-name>` is optional. When provided, the scanner uses that named
remote branch instead of resolving the repository's default branch. It accepts
only a valid Git branch short name (for example, `release/2026.1`), not an
arbitrary ref, tag, or commit SHA. A missing, inaccessible, or non-branch ref
is a branch-resolution failure (exit `4`).

### Success output

Write a heading with the selected branch and full commit SHA, followed by a
three-line block for every discovered `SKILL.md`, in deterministic path order
(bytewise ascending repository-relative path). Separate blocks with a blank
line. The first line contains the repository-relative path and stable skill ID;
the next two contain the source link and description.

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
2. Given `--branch release/2026.1`, `scan` uses that branch even when the
   repository's default branch differs; a missing branch exits `4` with no
   stdout or database changes.
3. Given multiple `SKILL.md` files, stdout contains one three-line block per
   file, ordered by repository-relative path.
4. The heading includes the full scanned SHA. Each block includes a stable ID,
   source path, link, and description (or `(none)`).
5. A second scan at the same head is idempotent and emits the same blocks.
6. After a new selected-branch commit changes a skill, the same logical `id` is
   emitted with the new commit and a new stored version.
7. A missing/private/inaccessible repository exits `3`, prints no stdout, and
   commits no database changes.
8. A repository with no `SKILL.md` files exits `0`, prints an empty-scan message,
   and stores a completed scan.
9. No repository-controlled executable content is invoked.

## 9. First-release choices

- The output is formatted text with three lines per skill.
- URLs are normalized within their transport form. HTTPS and SSH spellings are
  separate repository identities.
- An unreadable or non-UTF-8 `SKILL.md` aborts the scan before persistence.
  Malformed YAML front matter falls back to Markdown description extraction.
- The default limits are 1 MiB per `SKILL.md` and 10 MiB total, configurable via
  `SKILL_SCAN_MAX_FILE_BYTES` and `SKILL_SCAN_MAX_TOTAL_BYTES`.
- Private repositories work through the user's existing Git credentials.
