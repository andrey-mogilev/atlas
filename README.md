# skill-atlas

A Kotlin application with a CLI and local web interface that finds `SKILL.md`
files at the head of a Git repository branch, saves them to SQLite, and shows a
readable summary of each skill.

Requires Java 17 or newer, Maven, and Git.

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

Each skill is shown in three lines: its repository-relative path and ID, a
link, and its description. The heading shows the branch and commit. A scan with
no skills prints a short message. Errors go to standard error with a nonzero
exit code.

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

See [.spec/readme.md](.spec/readme.md) for the command contract.

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

The page shows the branch, full commit, paths, stable IDs, source links, and
descriptions. “View CLI output” provides the same formatted text as `scan`, with
a copy button. Non-web source URLs are displayed as text because browsers may
block local file and SSH links. Scans run in the background, one at a time;
another submission receives a busy message. Reloading the page in the same tab
can resume tracking the active job. The latest ten jobs remain available until
server shutdown; saved results remain in SQLite.

Scan history opens the latest 50 distinct saved scans, including CLI scans,
without contacting the source again. It shows the originally persisted findings,
not a fresh scan. In particular, plain folders use the existing `local` / `<NONE>`
identity: their saved history is not a chronological series of filesystem
snapshots. Re-scanning them can display current content while history retains
previously saved versions for existing paths.

The web interface uses the same database and `SKILL_SCAN_*` settings as the CLI.
Set these before starting the server. Credentials and database paths cannot be
configured from the web page. The UI requires JavaScript and a modern browser.
