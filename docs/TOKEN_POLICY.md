# TOKEN POLICY

## Goal

Minimize unnecessary context usage.
Prefer focused reasoning over full repository analysis.

---

## Core Principles

- Read only files directly related to the task.
- Avoid scanning the entire repository.
- Prefer minimal diffs.
- Prefer minimal abstractions.
- Keep responses concise and structured.

---

## Required Reading Order

1. tasks/current-task.md
2. AGENTS.md
3. Suggested files from current-task.md
4. Additional files only if necessary

Do not open unrelated directories first.

---

## File Reading Rules

- Read implementation files before reading large test suites.
- Read only the specific package related to the task.
- Avoid opening generated files, logs, build outputs, or unrelated configs.
- Do not read the whole repository tree.

---

## Refactoring Rules

- Do not refactor unrelated code.
- Do not rename files unless required.
- Do not introduce abstractions for single-use logic.
- Do not rewrite working code for style preferences.

---

## Testing Rules

- Run only relevant tests first.
- Expand test scope only if failures suggest wider impact.
- Avoid full test suite execution unless necessary.

---

## Output Rules

Final output should include:
1. Problem interpretation
2. Files inspected
3. Technical decision
4. Changed files
5. Test result
6. Remaining risk

Keep summaries short and actionable.

---

## Anti-Patterns

Avoid:
- Full repository analysis
- Broad architectural rewrites
- Speculative improvements
- Unrequested optimizations
- Reading unrelated docs
- Large context accumulation

---

## Decision Priority

1. Correctness
2. Simplicity
3. Minimal context
4. Minimal change
5. Performance optimization