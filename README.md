# skill-atlas

A Kotlin CLI that finds `SKILL.md` files at the head of a Git repository branch,
saves them to SQLite, and prints a readable summary of each skill.

Requires Java 17 or newer, Maven, and Git.

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
