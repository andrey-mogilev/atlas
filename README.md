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
`skill-atlas scan <repository-url>` from any directory.

Each skill is shown in three lines: its repository-relative path and ID, a
link, and its description. The heading shows the branch and commit. A scan with
no skills prints a short message. Errors go to standard error with a nonzero
exit code.

The default database is in the OS application data directory. Set
`SKILL_SCAN_DB_PATH` to use another SQLite file. File size limits default to
1 MiB per skill and 10 MiB per scan; override them with
`SKILL_SCAN_MAX_FILE_BYTES` and `SKILL_SCAN_MAX_TOTAL_BYTES`.

The scanner accepts HTTPS, HTTP, SSH, Git, and `file://` repository URLs, plus
Git's SSH form (`git@host:owner/repository.git`). It uses the installed Git
client and its configured credentials. HTTPS and SSH URLs remain separate
repository identities in the local database.

See [.spec/readme.md](.spec/readme.md) for the command contract.
