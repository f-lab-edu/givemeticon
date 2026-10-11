#!/usr/bin/env python3
"""Synthetic analyzer sanity checks; no Docker, database or load generation."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ANALYZER = Path(__file__).with_name('analyze-separated-v2.py')


class AnalyzerSanity(unittest.TestCase):
    def analyze(self, records, iterations=2):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            run, out, binaries = root / 'run', root / 'out', root / 'bin'
            run.mkdir()
            binaries.mkdir()
            (run / 'k6-failures.log').write_text('\n'.join(records))
            (run / 'k6-summary.json').write_text(json.dumps({'metrics': {
                'iterations': {'count': iterations},
                'admission_attempts': {'count': iterations},
            }}))
            docker = binaries / 'docker'
            docker.write_text('#!/bin/sh\nprintf "700000000\\t1\\tISSUED\\t1000\\t1100\\n"\n')
            docker.chmod(0o755)
            env = dict(os.environ, PATH=str(binaries) + os.pathsep + os.environ['PATH'])
            subprocess.run([sys.executable, str(ANALYZER), str(run), str(out),
                            'synthetic', 'synthetic', '1', '700000000'],
                           env=env, check=True, capture_output=True, text=True)
            return json.loads((out / 'summary-v2.json').read_text())

    def records(self):
        return [json.dumps({'kind': 'admission', 'memberId': 700000000,
                            'category': 'client_timeout', 'sentAtMs': 900, 'durationMs': 10000}),
                json.dumps({'kind': 'admission', 'memberId': 700000001,
                            'category': 'event_closed', 'sentAtMs': 910, 'durationMs': 5})]

    def test_db_final_does_not_claim_client_confirmation(self):
        result = self.analyze(self.records())
        self.assertEqual(result['integrity']['verdict'], 'OK')
        self.assertEqual(result['denominator_total_first_posts'], 2)
        self.assertEqual(result['headline_final_within_budget']['rate_pct'], 50)
        field = 'db_final_but_client_unconfirmed(DB result known, client saw timeout/CHECKING/error)'
        self.assertEqual(result['db'][field], 1)
        self.assertIn('NOT verified', result['headline_final_within_budget']['note'])
        self.assertEqual(result['hikari']['status'], 'MISSING fast-prom.csv')

    def test_missing_record_keeps_denominator_and_invalidates_integrity(self):
        result = self.analyze(self.records()[:1])
        self.assertEqual(result['denominator_total_first_posts'], 2)
        self.assertEqual(result['integrity']['missing_post_records'], 1)
        self.assertTrue(result['integrity']['verdict'].startswith('MISMATCH'))

    def test_duplicate_first_post_invalidates_integrity(self):
        result = self.analyze(self.records() + self.records()[:1])
        self.assertEqual(result['integrity']['members_with_multiple_admission_records'], 1)
        self.assertTrue(result['integrity']['verdict'].startswith('MISMATCH'))

    def test_malformed_json_invalidates_integrity(self):
        result = self.analyze(self.records() + ['{malformed'])
        self.assertEqual(result['integrity']['parse_errors'], 1)
        self.assertTrue(result['integrity']['verdict'].startswith('MISMATCH'))


if __name__ == '__main__':
    unittest.main()
