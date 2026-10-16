#!/usr/bin/env python3
"""Bounded sequential workflow using the official AO v0.14.2 loopback API."""
import argparse
import fcntl
import json
import os
from pathlib import Path
import subprocess
import sys
import time
from urllib.error import HTTPError
from urllib.request import Request, urlopen
import uuid

ROOT = Path(__file__).resolve().parents[2]
LOCAL = ROOT / ".agent-orchestrator/local"
ROLES = {
    "architect": ("codex", "gpt-6-astra", "high"),
    "builder": ("codex", "gpt-6.1-sol", "medium"),
    "verifier": ("claude-code", "claude-sonnet-5-5", ""),
}
CHECKS = ("duplicate", "capacity", "concurrency_transaction", "idempotency_retry",
          "worker_recovery", "docker_k6")
MAX_REWORKS = 3


def git(path, *args):
    return subprocess.check_output(["git", "-C", str(path), *args], text=True).strip()


def clean(path, sha=None):
    if git(path, "status", "--porcelain"):
        raise RuntimeError(f"Dirty worktree: {path}")
    if sha and git(path, "rev-parse", "HEAD") != sha:
        raise RuntimeError("Worktree SHA differs from the reviewed SHA")
    if git(path, "branch", "--show-current") == "develop":
        raise RuntimeError("Refusing to modify or publish develop")


def validate_verdict(result, expected, smoke):
    if result.get("sha") != expected:
        raise RuntimeError("Stale or missing review SHA")
    if result.get("status") not in ("PASS", "FAIL", "INCOMPLETE"):
        raise RuntimeError("Invalid Verifier verdict")
    if result["status"] == "PASS" and not smoke:
        checks = result.get("checks", {})
        for key in CHECKS:
            item = checks.get(key, {})
            if item.get("status") != "PASS" or not item.get("command") or not item.get("evidence"):
                raise RuntimeError(f"Missing independent verification evidence: {key}")


def validate_evidence(result, directory, sha):
    for key in CHECKS:
        item = result["checks"][key]
        relative = Path(item["evidence"])
        evidence = (directory / relative).resolve()
        if relative.is_absolute() or not evidence.is_relative_to(directory.resolve()):
            raise RuntimeError(f"Evidence must be inside the current verification directory: {key}")
        if item.get("exit_code") != 0 or not evidence.is_file() or sha not in evidence.read_text():
            raise RuntimeError(f"Missing successful evidence bound to SHA: {key}")


def sequence(call, smoke=False, code_sha=None):
    """Four-stage path plus at most three Architect-directed reworks."""
    plan = call("architect", "design", None)
    if plan.get("status") != "READY":
        return {"status": "INCOMPLETE", "last": plan}
    previous_sha = None
    for attempt in range(MAX_REWORKS + 1):
        built = call("builder", f"build-{attempt}", plan)
        sha = "SMOKE" if smoke else code_sha(built, previous_sha)
        if not smoke and sha == previous_sha:
            raise RuntimeError("Rework must produce a new descendant commit")
        previous_sha = sha
        reviewed = call("verifier", f"verify-{attempt}", built)
        validate_verdict(reviewed, sha, smoke)
        if reviewed["status"] == "INCOMPLETE":
            return {"status": "INCOMPLETE", "last": reviewed, "reworks": attempt}
        if reviewed["status"] == "PASS":
            final = call("architect", "final", reviewed)
            if final.get("sha") != sha:
                raise RuntimeError("Architect approved a different SHA")
            if final.get("status") == "APPROVED":
                return {"status": "APPROVED", "sha": sha, "reworks": attempt,
                        "builder": built, "verifier": reviewed, "architect": final}
            if final.get("status") != "FAIL":
                return {"status": "INCOMPLETE", "last": final, "reworks": attempt}
            reviewed = final
        if attempt == MAX_REWORKS:
            return {"status": "REWORK_LIMIT", "last": reviewed, "reworks": attempt}
        plan = call("architect", f"diagnose-{attempt + 1}", reviewed)
        if plan.get("status") != "READY" or not plan.get("summary"):
            return {"status": "INCOMPLETE", "last": plan, "reworks": attempt}
    raise AssertionError("unreachable")


class Workflow:
    def __init__(self, args):
        self.args = args
        self.run_id = uuid.uuid4().hex
        self.out = LOCAL / "runs" / self.run_id
        self.out.mkdir(parents=True)
        self.sessions = {}
        self.paths = {}
        self.base = "http://127.0.0.1:3147"
        self.base_sha = None

    def api(self, path, body=None, method=None):
        data = None if body is None else json.dumps(body).encode()
        request = Request(self.base + path, data=data, method=method,
                          headers={"Content-Type": "application/json"})
        try:
            with urlopen(request, timeout=120) as response:
                return json.load(response)
        except HTTPError as error:
            raise RuntimeError(f"AO {error.code}: {error.read().decode()[:1500]}") from error

    def save(self, name, value):
        path = self.out / (name + ".json")
        # Never overwrite an earlier stage or automatically resume an uncertain mutation.
        with path.open("x") as stream:
            json.dump(value, stream, ensure_ascii=False, indent=2)
        return path

    def preflight(self):
        forbidden = ("OPENAI_API_KEY", "CODEX_API_KEY", "OPENAI_BASE_URL", "ANTHROPIC_API_KEY",
                     "ANTHROPIC_AUTH_TOKEN", "ANTHROPIC_BASE_URL", "CLAUDE_CODE_USE_BEDROCK",
                     "CLAUDE_CODE_USE_VERTEX", "CLAUDE_CODE_USE_FOUNDRY")
        if any(os.environ.get(key) for key in forbidden):
            raise RuntimeError("API credentials/provider overrides detected; subscription-only workflow")
        codex = subprocess.run(["codex", "login", "status"], text=True, capture_output=True, check=True)
        if "Logged in using ChatGPT" not in codex.stdout + codex.stderr:
            raise RuntimeError("ChatGPT subscription login required")
        claude = json.loads(subprocess.check_output(["claude", "auth", "status"], text=True))
        if not claude.get("loggedIn") or claude.get("authMethod") != "claude.ai":
            raise RuntimeError("Claude subscription login required; do not fall back to API billing")
        self.api("/readyz")
        for role, (harness, model, effort) in ROLES.items():
            catalog = self.api(f"/api/v1/agents/{harness}/models")
            found = next((m for m in catalog.get("models", []) if m["id"] == model), None)
            if not found or (effort and effort not in found.get("efforts", [])):
                raise RuntimeError(f"Requested model/effort unavailable: {role} {model} {effort}")
            self.save(role + "-catalog", {"id": model, "effort": effort, "model": found,
                                         "source": catalog.get("source")})

    def spawn(self, role):
        harness, model, effort = ROLES[role]
        body = {"harness": harness, "model": model, "mode": "chat", "effort": effort,
                "approvalMode": "default", "displayName": f"{role}-{self.run_id[:8]}",
                "clientRequestId": f"{self.run_id}-{role}"}
        if self.args.command == "run":
            body["projectId"] = self.args.project
            body["branch"] = f"workflow/{self.run_id[:8]}/{role}"
        session = self.api("/api/v1/sessions", body)["session"]
        sid = session["id"]
        self.sessions[role] = sid
        self.save(role + "-session", session)
        # Disable the native feedback loop so only this bounded controller reworks.
        self.api(f"/api/v1/sessions/{sid}/auto-inject-ci", {"autoInjectCI": False}, "PATCH")
        self.api(f"/api/v1/sessions/{sid}/auto-inject-review", {"autoInjectReview": False}, "PATCH")
        self.api(f"/api/v1/sessions/{sid}/auto-review", {"enabled": False}, "PUT")
        if self.args.command == "run":
            location = self.api(f"/api/v1/desktop/sessions/{sid}/workspace")
            self.paths[role] = Path(location["workspacePath"])
            if self.paths[role].resolve() == ROOT:
                raise RuntimeError("AO failed to isolate the workspace")
            clean(self.paths[role])
            if self.base_sha is None:
                self.base_sha = git(self.paths[role], "rev-parse", "HEAD")
            elif git(self.paths[role], "rev-parse", "HEAD") != self.base_sha:
                raise RuntimeError("Base branch changed after Architect design; stop for a new plan")
        return sid

    def prompt(self, role, stage, previous):
        contract = {"status": "READY|PASS|FAIL|INCOMPLETE|APPROVED", "sha": "exact SHA or SMOKE",
                    "summary": "decisions, evidence, risks and next owner's instructions"}
        if self.args.command == "smoke":
            action = ("Verify the builder's SHA is SMOKE and its summary contains WORKFLOW_SMOKE. "
                      "Return PASS only when both hold." if role == "verifier" else
                      "Return APPROVED with sha SMOKE." if stage == "final" else
                      "Return READY with sha SMOKE and summary WORKFLOW_SMOKE.")
            return ("Connectivity/handoff smoke test only. Do not use tools, access files, "
                    "spawn agents, modify anything, or publish. " + action +
                    " Reply ONLY one JSON object with status, sha, summary. Previous handoff: " +
                    json.dumps(previous))
        task = Path(self.args.task).read_text()
        role_doc = (ROOT / "agents" / (role.upper() + ".md")).read_text()
        common = ("Follow AGENTS.md and the orchestration contract below. No GitHub writes, push, merge, "
                  "API keys, provider substitutions, new external connections or global changes. "
                  "Work only in your AO workspace. Do not start more agents or retry loops. "
                  "Read only related files. Report INCOMPLETE for missing permissions/environment. "
                  "Return ONLY a JSON object as your final response. "
                  "Include a handoff in summary with SHA, decisions, tests, risks, next owner and prompt. ")
        if role == "builder":
            action = ("Implement the Architect plan; run relevant tests; commit only your changes. "
                      "Return READY with the full committed SHA. Do not push or open a PR. ")
        elif role == "verifier":
            action = ("Your worktree was detached at the Builder SHA. Independently review and run "
                      "required tests. Do not modify tracked files or create commits. Return "
                      "PASS/FAIL/INCOMPLETE with that exact SHA and checks. Each checks entry needs "
                      "status, command, exit_code, evidence (a bare filename relative to the stage "
                      "directory below, NOT relative to the worktree). Save logs only in "
                      f"build/agent-orchestrator-verification/{self.run_id}/{stage}/; each log "
                      "must contain the target full SHA, command, and actual output. Missing tests "
                      "mean INCOMPLETE. Required check keys: " + ", ".join(CHECKS) + ". ")
        elif stage == "final":
            action = ("Review Builder commit and Verifier evidence via git show; no code edits. "
                      "Approve only the exact reviewed SHA. Return APPROVED, FAIL or INCOMPLETE. ")
        else:
            action = ("Analyze/design or diagnose this failure and give the Builder concrete repair "
                      "instructions. No code edits. Return READY or INCOMPLETE. ")
        return common + action + "\nOrchestration contract:\n" + \
            (ROOT / "agents/ORCHESTRATION.md").read_text() + "\nRole:\n" + role_doc + "\nIssue/task:\n" + task + \
            "\nPrior handoff:\n" + json.dumps(previous, ensure_ascii=False) + \
            "\nResponse shape:\n" + json.dumps(contract)

    def call(self, role, stage, previous):
        sid = self.sessions.get(role) or self.spawn(role)
        readonly_sha = None
        evidence_dir = None
        if self.args.command == "run" and role == "architect":
            clean(self.paths[role])
            readonly_sha = git(self.paths[role], "rev-parse", "HEAD")
        if self.args.command == "run" and role == "verifier":
            clean(self.paths[role])
            sha = self.builder_sha(previous)
            git(self.paths[role], "checkout", "--detach", sha)
            clean(self.paths[role], sha)
            evidence_dir = self.paths[role] / "build/agent-orchestrator-verification" / self.run_id / stage
            evidence_dir.mkdir(parents=True, exist_ok=False)
        path = f"/api/v1/sessions/{sid}/conversation"
        before = self.api(path)
        self.check_model(role, before)
        old_turns = {turn["id"] for turn in before.get("turns", [])}
        sent = self.api(path + "/messages", {"text": self.prompt(role, stage, previous),
                                           "clientMessageId": f"{self.run_id}-{stage}"})
        turn_id = sent.get("turnId")
        deadline = time.monotonic() + self.args.timeout
        print(f"{stage}: {sid} / {ROLES[role][1]}", flush=True)
        while time.monotonic() < deadline:
            snapshot = self.api(path + "?limit=500")
            self.check_model(role, snapshot)
            turns = [t for t in snapshot.get("turns", [])
                     if t["id"] == turn_id or (not turn_id and t["id"] not in old_turns)]
            if any(a.get("status") == "pending" and a.get("activityKind") in ("approval", "user_input")
                   for a in snapshot.get("activities", [])):
                raise RuntimeError(f"Human approval/input required in AO session {sid}; no auto-approval")
            if turns:
                turn = turns[-1]
                if turn["state"] in ("failed", "cancelled", "interrupted", "recovered"):
                    self.save(stage + "-failed", snapshot)
                    raise RuntimeError(f"AO turn did not complete: {turn}")
                if turn["state"] == "completed":
                    messages = [m for m in snapshot.get("messages", [])
                                if m.get("role") == "assistant" and m.get("turnId") == turn["id"]]
                    if not messages:
                        raise RuntimeError("Completed turn has no matching final handoff")
                    text = messages[-1]["text"].strip()
                    if text.startswith("```json\n") and text.endswith("```"):
                        text = text[8:-3].strip()
                    result = json.loads(text)
                    self.save(stage, {"session": sid, "model": ROLES[role][1],
                                      "settings": snapshot.get("settings"), "result": result})
                    if self.args.command == "run" and role == "verifier":
                        clean(self.paths[role], previous["sha"])
                        self.builder_sha(previous)
                        validate_verdict(result, previous["sha"], False)
                        if result["status"] == "PASS":
                            validate_evidence(result, evidence_dir, previous["sha"])
                    if readonly_sha:
                        clean(self.paths[role], readonly_sha)
                    return result
            time.sleep(2)
        raise RuntimeError(f"Timeout in {sid}; inspect AO, do not automatically rerun")

    @staticmethod
    def check_model(role, snapshot):
        settings = snapshot.get("settings", {})
        model, effort = ROLES[role][1:]
        if snapshot.get("modelReroute") or settings.get("model") != model:
            raise RuntimeError(f"Requested model was not honored for {role}: {settings}")
        if effort and settings.get("reasoningEffort") != effort:
            raise RuntimeError(f"Requested reasoning effort was not honored for {role}: {settings}")

    def builder_sha(self, built, previous_sha=None):
        if built.get("status") != "READY":
            raise RuntimeError("Builder did not produce a ready commit")
        sha = built.get("sha", "")
        if len(sha) != 40 or any(c not in "0123456789abcdef" for c in sha):
            raise RuntimeError("Builder must return a full commit SHA")
        clean(self.paths["builder"], sha)
        if sha == self.base_sha:
            raise RuntimeError("Builder produced no new commit")
        git(self.paths["builder"], "merge-base", "--is-ancestor", self.base_sha, sha)
        if previous_sha:
            git(self.paths["builder"], "merge-base", "--is-ancestor", previous_sha, sha)
        return sha

    def finish(self, result):
        if result["status"] == "APPROVED" and self.args.command == "run":
            self.builder_sha(result["builder"])
            body = (f"Issue/task: {Path(self.args.task).name}\n\n"
                    f"Verified SHA: {result['sha']}\n\n" + result["architect"]["summary"] +
                    "\n\nIndependent verification:\n" + json.dumps(result["verifier"], ensure_ascii=False, indent=2))
            (self.out / "pr-body.md").write_text(body)
            if self.args.publish_approved:
                cwd = self.paths["builder"]
                branch = git(cwd, "branch", "--show-current")
                if not branch.startswith(f"workflow/{self.run_id[:8]}/"):
                    raise RuntimeError("Unexpected publication branch")
                subprocess.run(["git", "-C", str(cwd), "push", "origin", f"HEAD:refs/heads/{branch}"], check=True)
                self.builder_sha(result["builder"])
                subprocess.run(["gh", "pr", "create", "--base", "develop", "--head", branch,
                                "--title", self.args.title, "--body-file", str(self.out / "pr-body.md")],
                               cwd=cwd, check=True)
        self.save("result", result)
        return 0 if result["status"] == "APPROVED" else 2


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("smoke", "run", "register"))
    parser.add_argument("--project", default="givemeticon")
    parser.add_argument("--task", help="Authorized coupon issuance issue; all six checks are mandatory")
    parser.add_argument("--title", default="Implement Architect-approved coupon task")
    parser.add_argument("--timeout", type=int, default=600)
    parser.add_argument("--github-approved", action="store_true", help="Explicit prior GitHub connection approval")
    parser.add_argument("--publish-approved", action="store_true", help="Explicit prior push/PR approval")
    args = parser.parse_args()
    if args.command in ("register", "run") and not args.github_approved:
        parser.error("GitHub connection requires explicit prior approval: --github-approved")
    if args.command == "run" and (not args.task or not Path(args.task).is_file()):
        parser.error("run requires --task with the authorized issue and implementation scope")
    if args.publish_approved and args.command != "run":
        parser.error("Publication is only available for an approved real run")
    LOCAL.mkdir(parents=True, exist_ok=True)
    with (LOCAL / "workflow.lock").open("w") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        workflow = Workflow(args)
        print(f"Evidence: {workflow.out}", flush=True)
        try:
            workflow.preflight()
            if args.command == "register":
                existing = workflow.api("/api/v1/projects")["projects"]
                if any(p.get("id") == args.project or p.get("path") == str(ROOT) for p in existing):
                    raise RuntimeError("Project already registered; inspect and preserve config before updates")
                config = json.loads((ROOT / ".agent-orchestrator/project.json").read_text())
                registered = workflow.api("/api/v1/projects", {"path": str(ROOT), "projectId": args.project,
                                                                "name": "givemeticon", "config": config})
                workflow.save("registered", registered)
                return 0
            if args.command == "run":
                project = workflow.api(f"/api/v1/projects/{args.project}")["project"]
                if project.get("path") != str(ROOT):
                    raise RuntimeError("Registered project path differs from this repository")
            result = sequence(workflow.call, smoke=args.command == "smoke", code_sha=workflow.builder_sha)
            code = workflow.finish(result)
            print(json.dumps({"status": result["status"], "reworks": result.get("reworks"),
                              "evidence": str(workflow.out)}, ensure_ascii=False))
            return code
        except (Exception, KeyboardInterrupt) as error:
            workflow.save("interrupted", {"status": "INCOMPLETE", "error": str(error),
                                           "sessions": workflow.sessions,
                                           "next_owner": "Architect", "automatic_resume": False})
            print(f"INCOMPLETE: {error}", file=sys.stderr)
            return 2
        finally:
            # Stop role controllers without deleting their worktrees or history.
            for sid in workflow.sessions.values():
                try:
                    workflow.api(f"/api/v1/sessions/{sid}/exit-agent", {})
                except Exception as error:
                    print(f"Controller cleanup needed for {sid}: {error}", file=sys.stderr)


if __name__ == "__main__":
    sys.exit(main())
