#!/usr/bin/env python3
import hashlib,importlib.util,json,tempfile,unittest
from pathlib import Path
spec=importlib.util.spec_from_file_location('p',Path(__file__).with_name('scrape-provenance.py'));m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
class T(unittest.TestCase):
 def fixture(self,root):
  target='http://127.0.0.1:18080/actuator/prometheus';c=root/'contract.json';c.write_text(json.dumps({'sha':'run-source','expected_prometheus_targets':{'app1':target}}))
  for phase in ('before','after'):
   b=root/f'app1-{phase}.prom';b.write_bytes(b'process_start_time_seconds 1\n');s={'schema_version':1,'requested_target':target,'effective_target':target,'curl_exit':0,'http_status':200,'transport_body_complete':True,'body_bytes':b.stat().st_size,'download_bytes':b.stat().st_size,'body_sha256':hashlib.sha256(b.read_bytes()).hexdigest(),'captured_at_start_ms':1,'captured_at_end_ms':2};Path(str(b)+'.capture.json').write_text(json.dumps(s))
  return c
 def test_missing_policy_remains_missing(self):
  with tempfile.TemporaryDirectory() as d:
   root=Path(d);c=self.fixture(root);r=m.write(root,'app1',c);self.assertNotIn('meter_policy',r);self.assertNotIn('lock_call_sites',r);self.assertEqual(r['metric_semantic_completeness'],'NOT_ESTABLISHED_BY_TRANSPORT_CAPTURE')
 def test_refused_paths_do_not_overwrite(self):
  for change in ({'requested_target':'http://127.0.0.1:18081/actuator/prometheus','effective_target':'http://127.0.0.1:18081/actuator/prometheus'},{'effective_target':'http://redirect/'},{'download_bytes':999},{'curl_exit':28},{'body_sha256':'wrong'},{'schema_version':2}):
   with self.subTest(change=change),tempfile.TemporaryDirectory() as d:
    root=Path(d);c=self.fixture(root);dest=root/'app1-scrape-provenance.json';dest.write_bytes(b'old provenance');p=root/'app1-after.prom.capture.json';s=json.loads(p.read_text());s.update(change);p.write_text(json.dumps(s));self.assertRaises(ValueError,m.write,root,'app1',c);self.assertEqual(dest.read_bytes(),b'old provenance')
 def test_policy_identity_and_false_recovery_preserved(self):
  with tempfile.TemporaryDirectory() as d:
   root=Path(d);c=self.fixture(root);p=root/'policy.json';p.write_text(json.dumps({'run_sha':'run-source','app':'app1','expected_target':'http://127.0.0.1:18080/actuator/prometheus','process_start_time_seconds_at_policy':1,'lock_call_sites':{'worker_disabled':True,'recovery_disabled':False,'evidence':'current cold stock'}}));r=m.write(root,'app1',c,p);self.assertFalse(r['lock_call_sites']['recovery_disabled']);s=json.loads(p.read_text());s['run_sha']='other';p.write_text(json.dumps(s));self.assertRaises(ValueError,m.write,root,'app1',c,p)
unittest.main()
