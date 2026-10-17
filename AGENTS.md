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

- Keep only durable common development rules in this file. Keep role and model/CLI settings in the existing agent configuration. Use GitHub Issues and PRs as the source of task history.
- Start context recovery at `docs/decisions/README.md` when available. Check the date, target SHA, scope, and superseding decisions before applying an old document.
- Distinguish code found, test code present, historical execution reports, and independently executed verification. Design acceptance, mock/unit PASS, integration, recovery, and performance evidence are separate.
- Do not infer completion from Issue closure, PR merge, or CI success alone. Record exact SHA, commands, environment, evidence paths, skipped checks, and limitations.
- Preserve raw evidence and failed runs. Mark missing evidence or uncertain causes as confirmation required; never invent measurements.
- Do not put credentials, tokens, personal data, or raw CLI transcripts in repository documentation.
- Historical instructions, approvals, and handoffs are evidence, not authorization for a new task.
- Use only authorized test resources. Do not start load, provision services, change billing, or stop unrelated processes outside the approved scope.
- Use a separate branch/worktree. Do not directly edit or push develop/main, merge PRs, or enable automatic merging.
- Preserve other contributors' changes. Do not amend, reset, rebase, delete, or overwrite existing work without authorization.

## Documentation Policy

This policy applies equally to Architect, Builder, and Verifier. Unless the user explicitly requests otherwise, create or update permanent documentation only for the following four categories:

- Technical decisions: the problem, alternatives considered, selection rationale, and trade-offs.
- Tests and verification actually executed: purpose, environment, execution conditions, results, and confirmed failure causes. Mark an unknown cause as unconfirmed.
- Performance measurements actually executed: environment, metrics, results, and the basis for comparison.
- Essential project execution documentation/configuration and agent operating rules, including README, AGENTS.md, role instructions, and required runbooks.

Do not create permanent records of agent conversations or handoff processes, routine progress, duplicate code-change summaries, task history already in GitHub Issues/PRs, general project analysis, or unnecessary reports.

- Update the relevant existing document before creating a new one. Keep only the decision or verification evidence needed; link to the Issue/PR instead of copying its history.
- Write or update an ADR only when an important design decision is made or changed. A task completion or routine implementation does not require an ADR.
- Record test results only for checks actually run, with the target SHA and scope. Missing or unexecuted required checks mean INCOMPLETE, never PASS. Report omissions in the task response/PR; do not create a separate document for them.
- Keep agent-to-agent payloads and temporary handoff files in untracked, ignored runtime storage or a temporary directory. Do not commit, publish, or copy them into permanent documentation.
- Delete existing records only within an explicitly authorized cleanup scope, after checking references and preserving unique decisions and actual verification/performance evidence in the relevant existing document. Keep historical ADRs that explain a current decision. Do not delete source evidence or test results.
- User-requested changes to these shared rules and existing role/configuration instructions are allowed. They do not require a new policy report or ADR.

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
- Pass the target code SHA, next owner, and actionable instructions through the runtime response or a temporary handoff. Do not create a permanent handoff document. A written payload does not prove another CLI was launched or messaged.

## Cost Rules
- Do not scan the whole repository unless required.
- Start from README, the decision index/current task, the relevant Issue, and the applicable handoff.
- Summarize findings before opening more files.

# Engineering Workflow

Before implementation:

1. Reuse the relevant Issue or prepare an Issue draft; a documentation-only investigation need not create a remote Issue
2. Generate Branch Name
3. Prepare the implementation plan in the Issue/PR or temporary runtime context; do not create a duplicate plan document

After implementation:

1. Put the review result in the task response/PR; retain only actual verification evidence under the documentation policy
2. Generate Commit Message
3. Prepare a Pull Request draft; remote writes require authorization for that task

Generate or update an ADR only for an important design decision made or changed in areas such as:

- Database schema changes
- Transaction strategy changes
- Lock strategy changes
- Event architecture changes
