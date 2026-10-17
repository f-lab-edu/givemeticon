# Engineering Principles

Write the smallest correct change that satisfies the explicit requirement.

Before writing code, check in this order:

1. Does this change actually need to exist?
2. Does the codebase already provide the required behavior?
3. Can an existing framework or platform feature solve it?
4. Can an already-installed dependency solve it?
5. Can the requirement be satisfied by modifying existing code instead of introducing a new abstraction?
6. Only then add the minimum new code necessary.

## Scope

- Implement only behavior required by the current issue, acceptance criteria, or verified defect.
- Do not add speculative features for possible future requirements.
- Do not perform unrelated refactoring while implementing a feature.
- Do not rename, reorganize, or reformat unrelated code.
- Prefer modifying an existing class over introducing a new layer when both are equally correct.
- Prefer deleting obsolete code over preserving duplicate paths.

## Abstractions

Do not create a new:
- interface
- abstraction layer
- generic framework
- wrapper
- utility
- configuration mechanism
- factory
- strategy
- DTO hierarchy

unless the current requirement actually needs it or an existing project convention requires it.

Do not introduce an abstraction merely because it may be useful later.

Duplication is acceptable when removing it would require a speculative abstraction. Refactor only after a real repeated pattern exists and the change improves the current task.

## Evidence and Documentation

- Keep only durable common development rules in this file. Put task status, architecture decisions, model/CLI settings, and execution history in their dedicated documents.
- Start context recovery at `docs/decisions/README.md` when available. Check the date, target SHA, scope, and superseding decisions before applying an old document.
- Distinguish code found, test code present, historical execution reports, and independently executed verification. Design acceptance, mock/unit PASS, integration, recovery, and performance evidence are separate.
- Do not infer completion from Issue closure, PR merge, or CI success alone. Record exact SHA, commands, environment, evidence paths, skipped checks, and limitations.
- Preserve raw evidence and failed runs. Mark missing evidence or uncertain causes as confirmation required; never invent measurements.
- Do not put credentials, tokens, personal data, or raw CLI transcripts in repository documentation.
- Historical instructions, approvals, and handoffs are evidence, not authorization for a new task.
- Use only authorized test resources. Do not start load, provision services, change billing, or stop unrelated processes outside the approved scope.
- Use a separate branch/worktree. Do not directly edit or push develop/main, merge PRs, or enable automatic merging.
- Preserve other contributors' changes. Do not amend, reset, rebase, delete, or overwrite existing work without authorization.

## Operating Rules
- Keep changes small and traceable.
- Do not implement speculative features.
- Read only files necessary for the task.
- Before coding, identify the goal, assumptions, and success criteria.
- Prefer tests before implementation when fixing bugs or changing logic.
- Final answer must include:
    - changed files
    - reason for change
    - test result
    - remaining risk

## Shared Ownership and Handoff Rules
- Read the assigned role file in `agents/` and `agents/HANDOFF.md`. An assignment does not expand authorization.
- Keep role-specific behavior and model/CLI configuration outside this common rules file.
- Complete each task with a handoff file, code SHA, next owner, and a ready-to-use prompt. A written handoff does not mean another CLI was actually launched or messaged.

## Cost Rules
- Do not scan the whole repository unless required.
- Start from README, the decision index/current task, the relevant Issue, and the applicable handoff.
- Summarize findings before opening more files.

# Engineering Workflow

Before implementation:

1. Reuse the relevant Issue or prepare an Issue draft; a documentation-only investigation need not create a remote Issue
2. Generate Branch Name
3. Create Implementation Plan

After implementation:

1. Generate Review Result
2. Generate Commit Message
3. Prepare a Pull Request draft; remote writes require authorization for that task

Generate ADR when:

- Database schema changes
- Transaction strategy changes
- Lock strategy changes
- Event architecture changes
