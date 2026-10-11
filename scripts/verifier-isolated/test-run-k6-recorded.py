import importlib.util,io,json,subprocess,tempfile,unittest
from pathlib import Path
from unittest.mock import patch
spec=importlib.util.spec_from_file_location('recorder',Path(__file__).with_name('run-k6-recorded.py'));m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
class Process:
 def __init__(self,*args):self.calls=0
 def poll(self):self.calls+=1;return None if self.calls==1 else 7
 def wait(self,*args,**kw):return 7
class T(unittest.TestCase):
 def fake(self,*args):
  self.calls.append(args)
  if args[0]=='container':return subprocess.CompletedProcess([],0,'','')
  if 'Config.Labels' in ' '.join(args):s=json.dumps({'xyz.buzz.verifier.owner':'job','xyz.buzz.verifier.managed':'capped-harness'})
  elif 'State.Running' in ' '.join(args):s='false'
  elif args[0]=='exec':s='[cpu.stat]\nnr_throttled 3\n[memory.peak]\n1024'
  else:s='{}'
  return subprocess.CompletedProcess([],0,s,'')
 def test_snapshot_retained_and_exit_preserved(self):
  self.calls=[]
  with tempfile.TemporaryDirectory() as d,patch.object(m,'docker',side_effect=self.fake),patch.object(m.subprocess,'Popen',side_effect=Process) as pop,patch.object(m.time,'sleep'):
   out=Path(d)/'capture.jsonl';self.assertEqual(m.execute('k6','job',out,['--label','xyz.buzz.verifier.owner=job','k6']),7)
   self.assertNotIn('--rm',pop.call_args.args[0]);self.assertTrue(any(x[0]=='exec' for x in self.calls));self.assertIn(('rm','k6'),self.calls)
   rows=[json.loads(x) for x in out.read_text().splitlines()];self.assertTrue(any('nr_throttled 3' in x.get('cgroup','') for x in rows))
 def test_mismatched_owner_cannot_exec_or_delete(self):
  self.calls=[]
  with patch.object(m,'docker',side_effect=self.fake):self.assertFalse(m.capture('k6','other',io.StringIO()))
  self.assertFalse(any(x[0] in ('exec','stop','rm') for x in self.calls))
 def test_empty_owner_cannot_create(self):
  with patch.object(m,'docker') as docker,patch.object(m.subprocess,'Popen') as pop:self.assertEqual(m.execute('k6','',Path('unused'),[]),2);pop.assert_not_called();docker.assert_not_called()
 def test_unavailable_daemon_cannot_create(self):
  with patch.object(m,'docker',return_value=subprocess.CompletedProcess([],124,'','timeout')),patch.object(m.subprocess,'Popen') as pop:
   self.assertEqual(m.execute('k6','job',Path('unused'),[]),2);pop.assert_not_called()
 def test_missing_live_cgroup_is_recorded(self):
  self.calls=[]
  def docker(*args):
   if args[0]=='exec':return subprocess.CompletedProcess([],1,'','container exited')
   return self.fake(*args)
  s=io.StringIO()
  with patch.object(m,'docker',side_effect=docker):self.assertTrue(m.capture('k6','job',s))
  self.assertEqual(json.loads(s.getvalue())['cgroup_status'],1)
unittest.main()
