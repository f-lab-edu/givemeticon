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
 def test_generator_pressure_does_not_infer_cause_or_discard_run(self):
  with tempfile.TemporaryDirectory() as d:
   p=Path(d);(p/'run').mkdir()
   (p/'stage-check.json').write_text(json.dumps({'actual_iterations':1,'requested_unique_iterations':2,'dropped_iterations':1,'oom_observed':False}))
   (p/'run/k6-failures.log').write_text(json.dumps({'kind':'admission','inWindow':True})+'\n'+json.dumps({'kind':'duplicate','inWindow':False}))
   rows=[]
   for n in (1,4):
    rows.append({'state_status':0,'state':json.dumps({'oom':False,'memory':1000}),'cgroup_status':0,'cgroup':f'[cpu.stat]\nnr_throttled {n}\nnr_periods {n*10}\n[memory.peak]\n950\n[memory.events]\nmax {n}\n'})
   (p/'run/k6-cgroup.jsonl').write_text('\n'.join(json.dumps(r) for r in rows))
   code,result=m.evaluate([p]);stage=result['stages'][0]
   self.assertEqual(code,3);self.assertEqual(stage['reason'],'GENERATION_MISSED')
   self.assertEqual(stage['generator_resource_observation']['nr_throttled_delta'],3)
   self.assertEqual(stage['generator_resource_observation']['memory_peak_cap_ratio'],.95)
   self.assertEqual(stage['cause_assessment']['attribution'],'UNKNOWN')
   self.assertEqual(stage['denominators']['unsent_scheduled_iterations'],1)
   self.assertEqual(stage['denominators']['sent_duplicate_requests'],1)
   self.assertEqual(stage['consistency_observation']['status'],'NOT_ASSESSED_BY_THIS_GATE')
 def test_missing_generator_evidence_does_not_erase_known_generation(self):
  with tempfile.TemporaryDirectory() as d:
   p=Path(d);(p/'run').mkdir();(p/'stage-check.json').write_text(json.dumps({'actual_iterations':1,'requested_unique_iterations':1,'dropped_iterations':0,'oom_observed':False}))
   (p/'run/k6-failures.log').write_text(json.dumps({'kind':'admission','inWindow':True}))
   code,result=m.evaluate([p]);self.assertEqual(code,0)
   self.assertIsNone(result['stages'][0]['generator_resource_observation']['nr_throttled_delta'])
   self.assertIsNone(result['stages'][0]['generator_resource_observation']['oom_state'])
unittest.main()
