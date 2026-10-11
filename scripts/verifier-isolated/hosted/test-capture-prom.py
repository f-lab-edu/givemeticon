#!/usr/bin/env python3
import hashlib,importlib.util,json,subprocess,tempfile,unittest
from pathlib import Path
from unittest.mock import patch
spec=importlib.util.spec_from_file_location('capture',Path(__file__).with_name('capture-prom.py'));m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
TARGET='http://127.0.0.1:18080/actuator/prometheus'
class Capture(unittest.TestCase):
 def fake(self,body,code=0,target=TARGET,size=None,status=200):
  def run(args,**kw):
   Path(args[args.index('-o')+1]).write_bytes(body)
   return subprocess.CompletedProcess(args,code,f'{status}\n{target}\n{len(body) if size is None else size}','')
  return run
 def test_success_has_effective_target_and_raw_hash(self):
  with tempfile.TemporaryDirectory() as d,patch.object(m.subprocess,'run',side_effect=self.fake(b'process_start_time_seconds 1\n')):
   p=Path(d)/'x.prom';r=m.capture(TARGET,p);self.assertTrue(r['transport_body_complete']);self.assertEqual(r['body_sha256'],hashlib.sha256(p.read_bytes()).hexdigest());self.assertEqual(json.loads(Path(str(p)+'.capture.json').read_text()),r)
   self.assertEqual(r['metric_semantic_completeness'],'NOT_ESTABLISHED_BY_TRANSPORT_CAPTURE')
 def test_cut_body_target_error_and_missing_transport_refuse(self):
  for opts in ({'code':18},{'size':1000},{'target':'http://other/'},{'status':503}):
   with tempfile.TemporaryDirectory() as d,patch.object(m.subprocess,'run',side_effect=self.fake(b'process_start_time_seconds 1\n',**opts)):
    self.assertFalse(m.capture(TARGET,Path(d)/'x.prom')['transport_body_complete'])
 def test_line_boundary_partial_is_not_semantically_complete(self):
  with tempfile.TemporaryDirectory() as d,patch.object(m.subprocess,'run',side_effect=self.fake(b'process_start_time_seconds 1\nprocess_uptime_seconds 2\n')):
   r=m.capture(TARGET,Path(d)/'x.prom');self.assertTrue(r['transport_body_complete']);self.assertNotEqual(r['metric_semantic_completeness'],'VERIFIED')
 def test_failure_does_not_reuse_previous_body(self):
  with tempfile.TemporaryDirectory() as d,patch.object(m.subprocess,'run',side_effect=OSError):
   p=Path(d)/'x.prom';p.write_bytes(b'old success');r=m.capture(TARGET,p);self.assertFalse(r['transport_body_complete']);self.assertEqual(p.read_bytes(),b'')
 def test_filtered_or_remote_request_rejected(self):
  for t in ('http://other/actuator/prometheus',TARGET+'?includedNames=process_start_time_seconds'):
   with tempfile.TemporaryDirectory() as d:self.assertRaises(ValueError,m.capture,t,Path(d)/'x.prom')
unittest.main()
