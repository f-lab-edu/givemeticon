import json,subprocess,sys,tempfile,unittest
from pathlib import Path
class T(unittest.TestCase):
 def run_stage(self,rows,oom=False,diagnostic=False,k6_oom=False):
  with tempfile.TemporaryDirectory() as d:
   p=Path(d);(p/'run').mkdir();(p/'analysis').mkdir()
   (p/'run/k6-summary.json').write_text(json.dumps({'metrics':{'iterations':{'values':{'count':200}}}}))
   (p/'analysis/stock-summary.json').write_text(json.dumps({'db':{'coupon_rows':rows}}))
   (p/'containers-after.txt').write_text('oom_killed='+str(oom).lower())
   if k6_oom:(p/'run/k6-cgroup.jsonl').write_text(json.dumps({'state':json.dumps({'oom':True})})+'\n')
   return subprocess.run([sys.executable,str(Path(__file__).with_name('check-stage.py')),d,'200']+(['--diagnostic'] if diagnostic else []),capture_output=True).returncode
 def test_live_smoke_requires_db_effect(self):self.assertEqual(self.run_stage(0),3)
 def test_oom_blocks_baseline(self):self.assertEqual(self.run_stage(100,True),3)
 def test_generator_oom_blocks_capability(self):self.assertEqual(self.run_stage(100,k6_oom=True),3)
 def test_successful_capability(self):self.assertEqual(self.run_stage(100),0)
 def test_diagnostic_is_not_pass(self):self.assertEqual(self.run_stage(0,True,True),0)
unittest.main()
