#!/usr/bin/env python3
"""Exercise actual shell/sampler commands with a recording Docker substitute."""
import json
import os
from pathlib import Path
import signal
import subprocess
import tempfile
import time
import unittest

ROOT = Path(__file__).resolve().parent
DOCKER = """#!/usr/bin/env python3
import json, os, sys
a=sys.argv[1:]
with open(os.environ["CALLS"], "a") as f: f.write(json.dumps(a)+"\\n")
if a[0]=="inspect":
 if "--format" not in a: sys.exit(0 if os.environ.get("EXISTS") else 1)
 fmt=a[2]
 if "owner" in fmt: print("other" if a[-1].endswith("redis-coupon") and os.environ.get("MISMATCH") else "test-owner")
 elif "managed" in fmt: print("isolated-harness")
 else: print(os.environ.get("RUNNING","false"))
elif a[0]=="run":
 if os.environ.get("FAIL_SECOND") and a[a.index("--name")+1].endswith("redis-mail"): sys.exit(23)
 print(a[a.index("--name")+1])
elif a[0]=="stop" and os.environ.get("STOP_FAIL"): sys.exit(25)
elif a[0]=="ps": print("test-mysql")
elif a[0]=="exec" and "printenv" in a: print("sensitive-test-value")
elif a[0]=="logs": print("ready for connections port: 3306  MySQL")
elif a[0]=="exec" and "-i" in a:
 for line in sys.stdin:
  print("1000\\t1\\t0\\t1",flush=True)
"""

class SecurityTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.path = Path(self.tmp.name)
        for name, body in (("docker", DOCKER), ("lsof", "#!/bin/sh\nexit 1\n")):
            f = self.path / name
            f.write_text(body)
            f.chmod(0o755)
        self.env = dict(os.environ, PATH=f"{self.path}:{os.environ['PATH']}",
                        CALLS=str(self.path / "calls"), VERIFIER_OWNER_ID="test-owner",
                        VERIFIER_PREFIX="test-prefix", VERIFIER_MYSQL_PASSWORD="sensitive-test-value")

    def calls(self):
        f = self.path / "calls"
        return [json.loads(line) for line in f.read_text().splitlines()] if f.exists() else []

    def run_infra(self, command, **extra):
        return subprocess.run(["bash", str(ROOT / "start-infra.sh"), command],
                              env=dict(self.env, **extra), capture_output=True, text=True)

    def test_up_has_labels_and_no_password_in_argv(self):
        self.assertEqual(self.run_infra("up").returncode, 0)
        runs = [c for c in self.calls() if c[0] == "run"]
        self.assertEqual(len(runs), 3)
        for c in runs:
            self.assertIn("xyz.buzz.verifier.owner=test-owner", c)
        self.assertIn("MYSQL_ROOT_PASSWORD", runs[0])
        self.assertNotIn("sensitive-test-value", json.dumps(self.calls()))
        self.assertFalse(any(c[0] == "exec" and any(x.startswith("-p") for x in c) for c in self.calls()))

    def test_existing_name_is_not_reused(self):
        self.assertNotEqual(self.run_infra("up", EXISTS="1").returncode, 0)
        self.assertFalse(any(c[0] == "run" for c in self.calls()))

    def test_unowned_target_prevents_all_mutations(self):
        for command in ("down", "remove"):
            self.assertNotEqual(self.run_infra(command, MISMATCH="1").returncode, 0)
        self.assertFalse(any(c[0] in ("stop", "rm") for c in self.calls()))

    def test_down_stops_without_removing(self):
        self.assertEqual(self.run_infra("down").returncode, 0)
        self.assertEqual(len([c for c in self.calls() if c[0] == "stop"]), 1)
        self.assertFalse(any(c[0] == "rm" for c in self.calls()))

    def test_remove_requires_stopped_owned_targets(self):
        self.assertNotEqual(self.run_infra("remove", RUNNING="true").returncode, 0)
        self.assertFalse(any(c[0] == "rm" for c in self.calls()))
        self.assertEqual(self.run_infra("remove").returncode, 0)
        removes = [c for c in self.calls() if c[0] == "rm"]
        self.assertEqual(len(removes), 1)
        self.assertNotIn("-f", removes[0])

    def test_partial_up_failure_stops_only_created_owned_container(self):
        result = self.run_infra("up", FAIL_SECOND="1")
        self.assertEqual(result.returncode, 23)
        self.assertEqual([c for c in self.calls() if c[0] == "stop"], [["stop", "test-prefix-mysql"]])
        self.assertFalse(any(c[0] == "rm" for c in self.calls()))

    def test_cleanup_failure_keeps_original_exit_and_reports(self):
        result = self.run_infra("up", FAIL_SECOND="1", STOP_FAIL="1")
        self.assertEqual(result.returncode, 23)
        self.assertIn("cleanup stop failed", result.stderr)

    def test_cleanup_does_not_stop_changed_owner(self):
        # Substitute a changed label only for the successfully created MySQL.
        docker = self.path / "docker"
        docker.write_text(DOCKER.replace('else "test-owner")', 'else "changed-owner")'))
        result = self.run_infra("up", FAIL_SECOND="1")
        self.assertEqual(result.returncode, 23)
        self.assertIn("cleanup ownership mismatch", result.stderr)
        self.assertFalse(any(c[0] == "stop" for c in self.calls()))

    def test_runner_entrypoint_database_commands_hide_secret(self):
        import shutil
        repo = self.path / "repo"
        runner = repo / "scripts/coupon-integrated-loadtest/run-integrated-loadtest.sh"
        runner.parent.mkdir(parents=True)
        shutil.copyfile(ROOT.parent / "coupon-integrated-loadtest/run-integrated-loadtest.sh", runner)
        migrations = repo / "src/main/resources/db/migration"
        migrations.mkdir(parents=True)
        for name in ("V20260920__add_coupon_event_admission_ledger.sql",
                     "V20260923__add_coupon_award.sql", "V20260923_2__add_coupon_award_redemption.sql"):
            (migrations / name).write_text("SELECT 1;")
        for name, body in (("k6", "#!/bin/sh\nexit 0\n"),):
            f = self.path / name
            f.write_text(body)
            f.chmod(0o755)
        gradle = repo / "gradlew"
        gradle.write_text("#!/bin/sh\nexit 17\n")
        gradle.chmod(0o755)
        result = subprocess.run(["bash", str(runner)], cwd=repo,
                                env=dict(self.env, MYSQL_CONTAINER="test-mysql",
                                         REPORT_ROOT=str(self.path / "results")),
                                capture_output=True, text=True)
        self.assertEqual(result.returncode, 17, result.stderr)
        self.assertNotIn("sensitive-test-value", json.dumps(self.calls()))
        sql_calls = [c for c in self.calls() if c[0] == "exec" and "sh" in c]
        self.assertEqual(len(sql_calls), 4)
        for call in sql_calls:
            self.assertIn('export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"', " ".join(call))

    def test_integrated_mysql_function_keeps_password_out_of_argv(self):
        source = (ROOT.parent / "coupon-integrated-loadtest/run-integrated-loadtest.sh").read_text()
        start = source.index("docker_mysql() {")
        end = source.index("\n}", start) + 2
        function = source[start:end]
        subprocess.run(["bash", "-c", function + '\ncontainer=test-mysql; docker_mysql testdb --batch -e "SELECT 1"'],
                       env=self.env, check=True, capture_output=True)
        calls = self.calls()
        self.assertEqual(len(calls), 1)
        self.assertIn('export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"', " ".join(calls[0]))
        self.assertIn("SELECT 1", calls[0])
        self.assertNotIn("sensitive-test-value", json.dumps(calls))
        self.assertNotIn("-psensitive-test-value", calls[0])

    def test_manifest_collects_process_fields_without_args(self):
        # A command-line collector would return the sentinel; allowlisted ps does not.
        for name, body in {
            "ps": '#!/bin/sh\ncase "$*" in *args*|*command*) echo sensitive-test-value;; *) echo "PID CPU RSS COMM"; echo "1 0.1 100 java";; esac\n',
            "pgrep": '#!/bin/sh\necho sensitive-test-value\n',
            "sysctl": '#!/bin/sh\necho 1\n',
        }.items():
            f = self.path / name
            f.write_text(body)
            f.chmod(0o755)
        out = self.path / "manifest"
        subprocess.run(["bash", str(ROOT / "capture-manifest.sh"), str(out)],
                       env=self.env, check=True, capture_output=True)
        self.assertIn("1 0.1 100 java", out.read_text())
        self.assertNotIn("sensitive-test-value", out.read_text())

    def test_sampler_uses_container_env_and_records_db(self):
        p = subprocess.Popen(["python3", "-B", str(ROOT / "fast-sampler.py"),
                              str(self.path), "test-mysql", "testdb", "1", "1", "1", "0.01"],
                             env=self.env, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        try:
            deadline = time.monotonic() + 5
            while time.monotonic() < deadline:
                f = self.path / "fast-db.csv"
                if f.exists() and len(f.read_text().splitlines()) > 1:
                    break
                time.sleep(0.02)
            else:
                self.fail("DB sample not recorded")
            p.send_signal(signal.SIGTERM)
            _, err = p.communicate(timeout=5)
            self.assertEqual(p.returncode, 0, err.decode())
        finally:
            if p.poll() is None:
                p.kill()
                p.communicate()
        calls = self.calls()
        self.assertEqual(len(calls), 1)
        self.assertIn('export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"', " ".join(calls[0]))
        self.assertNotIn("printenv", calls[0])
        self.assertNotIn("sensitive-test-value", json.dumps(calls))
        self.assertNotIn("-e", calls[0])

if __name__ == "__main__":
    unittest.main()
