import importlib.util
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("workflow", Path(__file__).with_name("workflow.py"))
w = importlib.util.module_from_spec(spec)
spec.loader.exec_module(w)


class WorkflowTests(unittest.TestCase):
    def simulate(self, verdicts, final="APPROVED"):
        calls = []
        verdicts = iter(verdicts)

        def call(role, stage, previous):
            calls.append((role, stage))
            status = next(verdicts) if role == "verifier" else final if stage == "final" else "READY"
            return {"status": status, "sha": "SMOKE", "summary": "WORKFLOW_SMOKE"}

        return w.sequence(call, smoke=True), calls

    def test_pass_order(self):
        result, calls = self.simulate(["PASS"])
        self.assertEqual(result["status"], "APPROVED")
        self.assertEqual(calls, [("architect", "design"), ("builder", "build-0"),
                                 ("verifier", "verify-0"), ("architect", "final")])

    def test_failure_requires_architect_diagnosis(self):
        result, calls = self.simulate(["FAIL", "PASS"])
        self.assertEqual(result["reworks"], 1)
        self.assertEqual(calls[3:6], [("architect", "diagnose-1"), ("builder", "build-1"),
                                     ("verifier", "verify-1")])

    def test_four_failures_stop_after_three_reworks(self):
        result, calls = self.simulate(["FAIL"] * 4)
        self.assertEqual(result["status"], "REWORK_LIMIT")
        self.assertEqual(sum(role == "builder" for role, _ in calls), 4)
        self.assertEqual(sum(stage.startswith("diagnose") for _, stage in calls), 3)
        self.assertFalse(any(stage == "final" for _, stage in calls))

    def test_incomplete_does_not_retry_or_approve(self):
        result, calls = self.simulate(["INCOMPLETE"])
        self.assertEqual(result["status"], "INCOMPLETE")
        self.assertEqual(len(calls), 3)

    def test_architect_final_failure_is_bounded(self):
        result, calls = self.simulate(["PASS"] * 4, final="FAIL")
        self.assertEqual(result["status"], "REWORK_LIMIT")
        self.assertEqual(sum(role == "builder" for role, _ in calls), 4)

    def test_no_architect_approval_no_publication(self):
        result, _ = self.simulate(["PASS"], final="INCOMPLETE")
        self.assertEqual(result["status"], "INCOMPLETE")

    def test_stale_sha_rejected(self):
        with self.assertRaisesRegex(RuntimeError, "SHA"):
            w.validate_verdict({"status": "PASS", "sha": "old"}, "new", False)

    def test_missing_evidence_rejected(self):
        with self.assertRaisesRegex(RuntimeError, "evidence"):
            w.validate_verdict({"status": "PASS", "sha": "new"}, "new", False)

    def test_skipped_required_check_rejected(self):
        checks = {key: {"status": "PASS", "command": "test", "evidence": "test.log"} for key in w.CHECKS}
        checks["worker_recovery"]["status"] = "SKIP"
        with self.assertRaisesRegex(RuntimeError, "worker_recovery"):
            w.validate_verdict({"status": "PASS", "sha": "new", "checks": checks}, "new", False)

    def test_exact_models_and_efforts(self):
        for role, (_, model, effort) in w.ROLES.items():
            w.Workflow.check_model(role, {"settings": {"model": model, "reasoningEffort": effort}})
            with self.assertRaises(RuntimeError):
                w.Workflow.check_model(role, {"settings": {"model": "substitute"}})

    def test_reroute_rejected_even_when_selection_matches(self):
        with self.assertRaises(RuntimeError):
            w.Workflow.check_model("architect", {"settings": {"model": "gpt-6-astra", "reasoningEffort": "high"},
                                                  "modelReroute": {"model": "substitute"}})

    def test_effort_mismatch_rejected(self):
        with self.assertRaises(RuntimeError):
            w.Workflow.check_model("architect", {"settings": {"model": "gpt-6-astra", "reasoningEffort": "medium"}})

    def test_model_failure_does_not_start_another_role(self):
        calls = []

        def failed(role, stage, previous):
            calls.append(role)
            raise RuntimeError("worker/controller failure")

        with self.assertRaises(RuntimeError):
            w.sequence(failed, smoke=True)
        self.assertEqual(calls, ["architect"])

    def test_evidence_is_present_current_and_confined(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "test.log").write_text("SHA target\nactual test output")
            checks = {key: {"evidence": "test.log", "exit_code": 0} for key in w.CHECKS}
            w.validate_evidence({"checks": checks}, root, "target")
            with self.assertRaises(RuntimeError):
                w.validate_evidence({"checks": checks}, root, "stale")
            checks["duplicate"]["evidence"] = "../test.log"
            with self.assertRaises(RuntimeError):
                w.validate_evidence({"checks": checks}, root, "target")

    def test_develop_and_dirty_worktrees_cannot_publish(self):
        import subprocess
        with tempfile.TemporaryDirectory() as tmp:
            subprocess.run(["git", "init", "-b", "develop", tmp], check=True, capture_output=True)
            with self.assertRaisesRegex(RuntimeError, "develop"):
                w.clean(tmp)
            (Path(tmp) / "uncommitted.txt").write_text("pending")
            with self.assertRaisesRegex(RuntimeError, "Dirty"):
                w.clean(tmp)

    def test_same_sha_is_not_a_rework(self):
        def call(role, stage, previous):
            return {"status": "FAIL" if role == "verifier" else "READY", "sha": "same", "summary": "repair"}
        with self.assertRaisesRegex(RuntimeError, "new descendant"):
            w.sequence(call, code_sha=lambda built, previous: "same")

    def test_non_smoke_pass_requires_matching_architect_sha(self):
        def call(role, stage, previous):
            checks = {key: {"status": "PASS", "command": "test", "evidence": "test.log"} for key in w.CHECKS}
            return {"status": "APPROVED" if stage == "final" else "PASS" if role == "verifier" else "READY",
                    "sha": "stale" if stage == "final" else "target", "checks": checks}
        with self.assertRaisesRegex(RuntimeError, "different SHA"):
            w.sequence(call, code_sha=lambda built, previous: "target")

    def test_pending_approval_stops_instead_of_auto_approving(self):
        workflow = object.__new__(w.Workflow)
        workflow.args = SimpleNamespace(command="smoke", timeout=10)
        workflow.sessions = {"architect": "session-1"}
        workflow.run_id = "run-id"
        snapshot = {"settings": {"model": "gpt-6-astra", "reasoningEffort": "high"}}
        pending = dict(snapshot, activities=[{"status": "pending", "activityKind": "approval"}])
        with patch.object(workflow, "api", side_effect=[snapshot, {"turnId": "turn-1"}, pending]) as api:
            with self.assertRaisesRegex(RuntimeError, "Human approval"):
                workflow.call("architect", "design", None)
            self.assertEqual(api.call_count, 3)

    def test_timeout_stops_instead_of_retrying_send(self):
        workflow = object.__new__(w.Workflow)
        workflow.args = SimpleNamespace(command="smoke", timeout=0)
        workflow.sessions = {"architect": "session-1"}
        workflow.run_id = "run-id"
        snapshot = {"settings": {"model": "gpt-6-astra", "reasoningEffort": "high"}}
        with patch.object(workflow, "api", side_effect=[snapshot, {"turnId": "turn-1"}]) as api:
            with self.assertRaisesRegex(RuntimeError, "Timeout"):
                workflow.call("architect", "design", None)
            self.assertEqual(api.call_count, 2)

    def test_unapproved_result_never_pushes_or_opens_pr(self):
        with tempfile.TemporaryDirectory() as tmp:
            workflow = object.__new__(w.Workflow)
            workflow.args = SimpleNamespace(command="run", publish_approved=True)
            workflow.out = Path(tmp)
            with patch.object(w.subprocess, "run") as command:
                self.assertEqual(workflow.finish({"status": "INCOMPLETE"}), 2)
                command.assert_not_called()


if __name__ == "__main__":
    unittest.main()
