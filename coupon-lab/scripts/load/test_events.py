"""Checks events.js (which coupon event an iteration goes to) by running it with node. Skipped when node is not installed.
Run: python3 -I -m unittest discover -s scripts/load -v"""
import json
import os
import shutil
import subprocess
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
NODE = shutil.which("node")

LOADER = """
const fs = require('fs');
const source = fs.readFileSync(process.argv[1], 'utf8');
import('data:text/javascript,' + encodeURIComponent(source))
  .then((m) => { console.log(JSON.stringify(%s)); })
  .catch((e) => { console.log(JSON.stringify({error: String(e.message)})); });
"""


@unittest.skipUnless(NODE, "node is not installed; events.js is exercised through node")
class EventsTest(unittest.TestCase):
    def js(self, expression):
        # events.js is loaded as an ES module from a data: URL, so the file extension and node's module mode do not matter
        done = subprocess.run([NODE, "-e", LOADER % expression, os.path.join(HERE, "events.js")],
                              capture_output=True, text=True, timeout=60, check=True)
        return json.loads(done.stdout.strip().splitlines()[-1])

    def test_parse_event_ids(self):
        self.assertEqual(self.js("m.parseEventIds('7,8, 9')"), ["7", "8", "9"])
        self.assertEqual(self.js("m.parseEventIds('7,,8,')"), ["7", "8"])
        self.assertEqual(self.js("[m.parseEventIds(''), m.parseEventIds(undefined), m.parseEventIds(null)]"), [[], [], []])

    def test_a_malformed_id_is_a_configuration_error(self):
        result = self.js("(() => { try { return m.parseEventIds('7,x'); } catch (e) { return {error: e.message}; } })()")
        self.assertIn("'x'", result["error"])

    def test_iterations_go_round_robin(self):
        picks = self.js("Array.from({length: 10}, (_, i) => m.pickEventId(['7','8','9','10'], i))")
        self.assertEqual(picks, ["7", "8", "9", "10", "7", "8", "9", "10", "7", "8"])

    def test_every_event_gets_the_same_share(self):
        for rows, iterations in ((1, 500), (4, 4000), (16, 3200)):
            counts = self.js("(() => { const ids = Array.from({length: %d}, (_, i) => String(100 + i)); const c = {};"
                             " for (let i = 0; i < %d; i++) { const id = m.pickEventId(ids, i); c[id] = (c[id] || 0) + 1; }"
                             " return Object.values(c); })()" % (rows, iterations))
            self.assertEqual(len(counts), rows)
            self.assertEqual(set(counts), {iterations // rows})

    def test_a_single_event_gets_everything_and_an_empty_list_is_an_error(self):
        self.assertEqual(self.js("[0, 1, 99].map((i) => m.pickEventId(['42'], i))"), ["42", "42", "42"])
        result = self.js("(() => { try { return m.pickEventId([], 0); } catch (e) { return {error: e.message}; } })()")
        self.assertIn("no event ids", result["error"])


if __name__ == "__main__":
    unittest.main()
