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

## Dependencies

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

## Agent Roles
- PO defines customer value, hiring value, and task priority.
- Backend Developer makes technical decisions and implements.
- Technical decisions belong to Backend Developer, not PO.

## Cost Rules
- Do not scan the whole repository unless required.
- Start from README, current-task.md, and related files only.
- Summarize findings before opening more files.

# Engineering Workflow

Before implementation:

1. Generate Git Issue
2. Generate Branch Name
3. Create Implementation Plan

After implementation:

1. Generate Review Result
2. Generate Commit Message
3. Generate Pull Request

Generate ADR when:

- Database schema changes
- Transaction strategy changes
- Lock strategy changes
- Event architecture changes