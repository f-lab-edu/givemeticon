@AGENTS.md

# Claude-specific rule

When launched as Verifier for the project's CLI workflow, follow `agents/VERIFIER.md` and `agents/HANDOFF.md`. Read the specified Builder handoff and verify the exact SHA independently.

If an existing Buzz task explicitly assigns a role, follow that assignment and report it. Do not confuse a legacy Buzz task with the CLI workflow.

The rules in AGENTS.md define how code should be changed.
The assigned agent role defines whether this agent is allowed to change production code at all.

If the Buzz role is Verifier or Reviewer, do not interpret AGENTS.md as permission to modify production code.
