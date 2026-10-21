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
import json, os, re, sys, time
a=sys.argv[1:]
with open(os.environ["CALLS"], "a") as f: f.write(json.dumps(a)+"\\n")
if a[0]=="inspect":
 if "--format" not in a: sys.exit(0 if os.environ.get("EXISTS") else 1)
 fmt=a[2]
 if "owner" in fmt: print("other" if a[-1].endswith("redis-coupon") and os.environ.get("MISMATCH") else "test-owner")
 elif "managed" in fmt: print("isolated-harness")
 else: print(os.environ.get("RUNNING","false"))
elif a[0]=="run":
 if os.environ.get("FAIL_FIRST"): sys.exit(125)
 if os.environ.get("FAIL_SECOND") and a[a.index("--name")+1].endswith("redis-mail"): sys.exit(23)
 print(a[a.index("--name")+1])
elif a[0]=="stop" and os.environ.get("STOP_FAIL"): sys.exit(25)
elif a[0]=="ps": print("test-mysql")
elif a[0]=="exec" and "printenv" in a: print("sensitive-test-value")
elif a[0]=="logs": print("ready for connections port: 3306  MySQL")
elif a[0]=="exec" and any("Innodb_row_lock_waits" in x for x in a):
 q=[x for x in a if "Innodb_row_lock_waits" in x][0]
 print("11\\t22")
 for i in range(q.count("SELECT")-1): print(33+11*i)
elif a[0]=="exec" and "-i" in a:
 first=True
 for line in sys.stdin:
  m=re.match(r"SELECT (\\d+)",line)
  seq=m.group(1) if m else "0"
  lock="performance_schema" in line
  if "data_lock_waits" in line or "data_locks" in line: sys.exit(3)
  mode=os.environ.get("LOCK_MODE","ok") if lock else os.environ.get("DB_MODE","ok")
  if mode=="exit": sys.exit(0)
  if mode=="silent": continue
  if mode=="late" and first: time.sleep(1.5)
  first=False
  stamp=str(int(time.time()*1000)) if mode=="late" else "1000"
  print(seq+"\\t"+stamp+("\\t5\\t30\\t0" if lock else "\\t1\\t0\\t1"),flush=True)
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

    def test_first_run_failure_preserves_exit_in_system_bash(self):
        result = subprocess.run(["/bin/bash", str(ROOT / "start-infra.sh"), "up"],
                                env=dict(self.env, FAIL_FIRST="1"), capture_output=True, text=True)
        self.assertEqual(result.returncode, 125)
        self.assertNotIn("unbound variable", result.stderr)
        self.assertFalse(any(c[0] in ("stop", "rm") for c in self.calls()))

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
        # stdin must not be inherited: the fake `docker exec -i` reads it and would block on an open terminal/pipe.
        result = subprocess.run(["bash", str(runner)], cwd=repo, stdin=subprocess.DEVNULL,
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
                       env=self.env, check=True, capture_output=True, stdin=subprocess.DEVNULL)
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

    def start_sampler(self, **extra_env):
        return subprocess.Popen(["python3", "-B", str(ROOT / "fast-sampler.py"),
                                 str(self.path), "test-mysql", "testdb", "1", "1", "1", "0.01"],
                                env=dict(self.env, **extra_env), stdout=subprocess.PIPE, stderr=subprocess.PIPE)

    def stop_sampler(self, p):
        """SIGTERM then require exit within 5s; returns (returncode, stderr)."""
        try:
            p.send_signal(signal.SIGTERM)
            _, err = p.communicate(timeout=5)
            return p.returncode, err.decode()
        finally:
            if p.poll() is None:
                p.kill()
                p.communicate()

    def lines(self, name):
        f = self.path / name
        return f.read_text().splitlines() if f.exists() else []

    def wait_for(self, predicate, what):
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            if predicate():
                return
            time.sleep(0.02)
        self.fail(what)

    def test_sampler_uses_container_env_and_records_db(self):
        p = self.start_sampler()
        try:
            self.wait_for(lambda: len(self.lines("fast-db.csv")) > 1, "DB sample not recorded")
            rc, err = self.stop_sampler(p)
            self.assertEqual(rc, 0, err)
        finally:
            if p.poll() is None:
                p.kill()
                p.communicate()
        calls = [c for c in self.calls() if c[0] == "exec" and "-i" in c]
        # one session for fast-db.csv and a separate one for lock metrics
        self.assertEqual(len(calls), 2)
        for call in calls:
            self.assertIn('export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"', " ".join(call))
            self.assertNotIn("printenv", call)
            self.assertNotIn("-e", call)
        self.assertNotIn("sensitive-test-value", json.dumps(self.calls()))

    def test_lock_sampler_records_locks(self):
        p = self.start_sampler(LOCK_MODE="ok")
        try:
            self.wait_for(lambda: len(self.lines("fast-mysql-locks.csv")) > 1, "lock sample not recorded")
            rc, err = self.stop_sampler(p)
            self.assertEqual(rc, 0, err)
        finally:
            if p.poll() is None:
                p.kill()
                p.communicate()
        rows = self.lines("fast-mysql-locks.csv")
        self.assertEqual(rows[0], "host_before_ms,host_after_ms,server_ms,row_lock_waits,row_lock_time_ms,row_lock_current_waits")
        self.assertTrue(rows[1].endswith(",1000,5,30,0"), rows[1])

    def test_lock_failure_does_not_stop_db_sampling(self):
        p = self.start_sampler(LOCK_MODE="exit")
        try:
            self.wait_for(lambda: len(self.lines("fast-db.csv")) > 2, "DB sample not recorded")
            first = len(self.lines("fast-db.csv"))
            time.sleep(0.5)
            self.assertGreater(len(self.lines("fast-db.csv")), first, "fast-db.csv stopped growing after lock session ended")
            rc, err = self.stop_sampler(p)
            self.assertEqual(rc, 0, err)
        finally:
            if p.poll() is None:
                p.kill()
                p.communicate()
        self.assertIn("lock sampler stopped", err)
        self.assertEqual(len(self.lines("fast-mysql-locks.csv")), 1)  # header only
        self.assertNotIn("performance_schema", err)

    def test_silent_lock_session_still_terminates(self):
        p = self.start_sampler(LOCK_MODE="silent")
        try:
            self.wait_for(lambda: len(self.lines("fast-db.csv")) > 1, "DB sample not recorded")
            rc, err = self.stop_sampler(p)
            self.assertEqual(rc, 0, err)
        finally:
            if p.poll() is None:
                p.kill()
                p.communicate()
        self.assertEqual(len(self.lines("fast-mysql-locks.csv")), 1)  # no fabricated samples

    def test_silent_db_session_still_terminates(self):
        p = self.start_sampler(DB_MODE="silent")
        try:
            self.wait_for(lambda: len(self.lines("fast-mysql-locks.csv")) > 1, "lock sample not recorded")
            rc, err = self.stop_sampler(p)
            self.assertEqual(rc, 0, err)
        finally:
            if p.poll() is None:
                p.kill()
                p.communicate()
        self.assertEqual(len(self.lines("fast-db.csv")), 1)  # header only, no fabricated samples

    def assert_pairs_within_request_window(self, name):
        rows = [r.split(",") for r in self.lines(name)[1:]]
        self.assertGreaterEqual(len(rows), 2, name)
        for before, after, server, *_ in rows:
            self.assertLessEqual(int(before), int(server), f"{name}: reply older than its request: {before},{server},{after}")
            self.assertLessEqual(int(server), int(after), f"{name}: reply newer than its receipt: {before},{server},{after}")

    def test_late_db_reply_is_not_paired_with_next_sample(self):
        p = self.start_sampler(DB_MODE="late")
        try:
            self.wait_for(lambda: len(self.lines("fast-db.csv")) > 5, "DB samples not recorded")
            rc, err = self.stop_sampler(p)
            self.assertEqual(rc, 0, err)
        finally:
            if p.poll() is None:
                p.kill()
                p.communicate()
        self.assert_pairs_within_request_window("fast-db.csv")
        self.assertIn("db sampler: skipped=1 late_discarded=1", err)

    def test_late_lock_reply_is_not_paired_with_next_sample(self):
        p = self.start_sampler(LOCK_MODE="late")
        try:
            self.wait_for(lambda: len(self.lines("fast-mysql-locks.csv")) > 5, "lock samples not recorded")
            rc, err = self.stop_sampler(p)
            self.assertEqual(rc, 0, err)
        finally:
            if p.poll() is None:
                p.kill()
                p.communicate()
        self.assert_pairs_within_request_window("fast-mysql-locks.csv")
        self.assertIn("lock sampler: skipped=1 late_discarded=1", err)

    OLD_MONITOR_QUERY = (
        "export MYSQL_PWD=\"$MYSQL_ROOT_PASSWORD\"; exec mysql -uroot -Nse \"SELECT COUNT(*), SUM(status='PENDING') FROM "
        "testdb.coupon_application WHERE event_id=7; SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE "
        "VARIABLE_NAME='Innodb_row_lock_waits'; SELECT COUNT(*) FROM performance_schema.data_lock_waits; SELECT VARIABLE_VALUE "
        "FROM performance_schema.global_status WHERE VARIABLE_NAME='Innodb_os_log_written'; SELECT VARIABLE_VALUE FROM "
        "performance_schema.global_status WHERE VARIABLE_NAME='Innodb_data_written'; SELECT VARIABLE_VALUE FROM "
        "performance_schema.global_status WHERE VARIABLE_NAME='Innodb_data_reads'; SELECT VARIABLE_VALUE FROM "
        "performance_schema.global_status WHERE VARIABLE_NAME='Innodb_data_writes';\"")

    def run_monitor(self, **extra_env):
        """Run monitor-admission.sh for one full sampling pass (gc.csv is its last write), then stop it. Returns the run dir."""
        run_dir = self.path / "monitor-run"
        env = dict(self.env, MYSQL_CONTAINER="test-mysql", COUPON_ADMISSION_LOADTEST_DB="testdb", **extra_env)
        p = subprocess.Popen(["bash", str(ROOT.parent / "coupon-admission/monitor-admission.sh"), str(run_dir), "7",
                              "999999", "999998", "999997", "http://127.0.0.1:1/p", "http://127.0.0.1:1/p"],
                             env=env, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                             start_new_session=True)
        try:
            self.wait_for(lambda: len((run_dir / "gc.csv").read_text().splitlines()) > 1
                          if (run_dir / "gc.csv").exists() else False, "monitor sampling pass did not finish")
        finally:
            os.killpg(p.pid, signal.SIGKILL)
            p.wait()
        return run_dir

    def monitor_mysql_calls(self):
        return [c for c in self.calls() if c[0] == "exec" and any("Innodb_row_lock_waits" in x for x in c)]

    def test_monitor_default_keeps_lock_introspection(self):
        run_dir = self.run_monitor()
        everything = json.dumps(self.calls())
        self.assertIn("sys.innodb_lock_waits", everything)
        self.assertIn("performance_schema.data_lock_waits", everything)
        # the default command is byte-for-byte the one the monitor used before the switch existed
        self.assertEqual(self.monitor_mysql_calls()[0][-1], self.OLD_MONITOR_QUERY)
        self.assertEqual(self.lines("monitor-run/mysql.csv")[1].split(",", 1)[1], "11,22,33,44,55,66,77,88")
        self.assertTrue(list((run_dir / "locks").glob("*.tsv")))

    def test_monitor_can_disable_lock_introspection(self):
        run_dir = self.run_monitor(MONITOR_LOCK_INTROSPECTION="false")
        everything = json.dumps(self.calls())
        self.assertNotIn("sys.innodb_lock_waits", everything)
        self.assertNotIn("data_lock_waits", everything)
        self.assertEqual(list((run_dir / "locks").glob("*.tsv")), [])

    def test_monitor_disabled_keeps_mysql_csv_columns_aligned(self):
        self.run_monitor(MONITOR_LOCK_INTROSPECTION="false")
        rows = self.lines("monitor-run/mysql.csv")
        self.assertEqual(rows[0], "timestamp_utc,applications,pending,innodb_row_lock_waits,data_lock_wait_rows,"
                                  "innodb_os_log_written,innodb_data_written,innodb_data_reads,innodb_data_writes")
        # only data_lock_wait_rows is empty; every other column keeps its own value
        self.assertEqual(rows[1].split(",", 1)[1], "11,22,33,,44,55,66,77")

if __name__ == "__main__":
    unittest.main()
