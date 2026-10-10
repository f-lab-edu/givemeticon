import importlib.util,json,tempfile,unittest
from pathlib import Path
spec=importlib.util.spec_from_file_location('gate',Path(__file__).with_name('final-generation-gate.py'));m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
class T(unittest.TestCase):
 def check(self,inside,dropped=0,missing=False):
  with tempfile.TemporaryDirectory() as d:
   p=Path(d);(p/'run').mkdir();(p/'stage-check.json').write_text(json.dumps({'actual_iterations':2,'requested_unique_iterations':2,'dropped_iterations':dropped,'oom_observed':False}))
   (p/'run/k6-failures.log').write_text('\n'.join(json.dumps({'kind':'admission','inWindow':i<inside} if not missing else {'kind':'admission'}) for i in range(2)))
   return m.evaluate([p])[0]
 def test_window_met(self):self.assertEqual(self.check(2),0)
 def test_tail_does_not_meet_target(self):self.assertEqual(self.check(1),3)
 def test_drops_fail_explicit_gate(self):self.assertEqual(self.check(2,1),3)
 def test_unknown_window_is_missing(self):self.assertEqual(self.check(2,missing=True),4)
unittest.main()
