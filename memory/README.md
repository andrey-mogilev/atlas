# Project memory

This directory stores durable, repository-specific context that helps agents
continue work consistently across tasks. It complements, but never replaces,
the specifications in `.spec/`, the project documentation, source code, tests,
or Git history.

## Reading memory

Start with `project.md`. Read `current.md` for active concerns, then consult
only the decision records and handoffs relevant to the task.

Treat memory as context rather than proof. When memory conflicts with source
code, tests, specifications, or a newer decision, verify the current behavior
and correct or retire the stale memory in the same change.

## Writing memory

Store only information that is likely to help a future task, such as:

- verified architectural or operational knowledge that is not obvious from
  the code;
- rationale for decisions and rejected alternatives;
- active risks, constraints, and follow-up work;
- concise handoff context for unfinished work.

Do not store chat transcripts, routine progress reports, command output,
credentials, secrets, personal data, or claims that have not been verified.
Link to authoritative specifications and documentation instead of copying
their requirements into memory.

Use `YYYY-MM-DD-short-title.md` for decision records and
`YYYY-MM-DD-task-or-branch.md` for handoffs. Include the date, status, evidence
or source links, and a review condition when the information can expire.
Prefer creating a uniquely named file to having several agents edit one shared
file concurrently.

## Maintenance

Maintenance should compare memory with the latest `main`, `.spec/`, project
documentation, source code, tests, and relevant Git history.

- Remove duplicated or transient material.
- Consolidate fragmented entries when doing so improves discovery.
- Mark superseded decisions and link to their replacement, retaining a short
  rationale when it remains historically useful.
- Delete obsolete handoffs after their work is completed or captured in
  authoritative documentation.
- Move durable discoveries out of handoffs and into `project.md`, a decision
  record, a specification, or normal documentation as appropriate.
- If evidence is ambiguous, add a dated review note instead of silently
  deleting or rewriting the claim.

Memory maintenance must follow the repository's branch, validation, pull
request, and CI requirements in `AGENTS.md`. A maintenance pass that finds no
meaningful changes should leave the repository untouched.

## Layout

- `project.md`: stable, verified project context.
- `current.md`: short-lived risks, priorities, and review notes.
- `decisions/`: dated architectural and operational decision records.
- `handoffs/`: dated context for unfinished or transferred work.
