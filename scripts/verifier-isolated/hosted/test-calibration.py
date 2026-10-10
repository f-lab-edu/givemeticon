#!/usr/bin/env python3
import importlib.util,json,subprocess,tempfile,unittest,http.server,threading,os,sys
from pathlib import Path
HERE=Path(__file__).parent
def load(name):
 spec=importlib.util.spec_from_file_location(name,HERE/(name+'.py'));mod=importlib.util.module_from_spec(spec);spec.loader.exec_module(mod);return mod
scope=load('verify-observer-scope');caps=load('verify-calibration-caps');get=load('get-smoke');capacity=load('linux-capability')
class T(unittest.TestCase):
 def test_scope_limit_evidence_fail_closed(self):
  with tempfile.TemporaryDirectory() as d:
   p=Path(d)
   for f,v in {'cpu.max':'25000 100000','memory.max':'536870912','memory.swap.max':'0','cpu.stat':'usage_usec 30','memory.current':'1','memory.peak':'2','memory.events':'oom 0'}.items():(p/f).write_text(v)
   self.assertTrue(scope.assess(p)['pass']);(p/'cpu.max').write_text('max 100000');self.assertFalse(scope.assess(p)['pass'])
   (p/'memory.max').unlink()
   with self.assertRaises(OSError):scope.assess(p)
 def test_caps_owner_and_budget(self):
  state={'labels':{'xyz.buzz.verifier.owner':'job','xyz.buzz.verifier.managed':'capped-harness'},'cpu':550000000,'memory':768*1024**2,'memswap':768*1024**2,'running':True}
  self.assertTrue(caps.check(state,.55,768*1024**2,'job'));self.assertFalse(caps.check(state,.55,768*1024**2,'other'));self.assertFalse(caps.check(state,1,768*1024**2,'job'))
 def test_generator_actual_limits_and_missing_live_snapshot(self):
  with tempfile.TemporaryDirectory() as d:
   p=Path(d)/'k6.jsonl';p.write_text(json.dumps({'state_status':0,'cgroup_status':0,'state':json.dumps({'cpu_nano':750000000,'memory':3072*1024**2,'memswap':3072*1024**2}),'cgroup':'[cpu.max]\n75000 100000\n[memory.max]\n3221225472\n[memory.swap.max]\n0\n'})+'\n')
   self.assertTrue(caps.check_generator(p,'job')['pass']);p.write_text('');self.assertFalse(caps.check_generator(p,'job')['pass'])
 def test_b_capacity_is_distinct_and_fixed(self):
  b=capacity.assess(4,15000,10000,'B');self.assertEqual(b['required_memory_mib'],9088);self.assertEqual(b['caps']['mysql']['cpu'],1);self.assertEqual(b['caps']['apps']['cpu_each'],.55);self.assertIn('broker_empty_slot',b['caps']);self.assertFalse(capacity.assess(4,8000,10000,'B')['pass'])
 def test_adapter_uses_source_observer_with_required_budget(self):
  cmd=get.command('input','http://a',7,'out','idle');self.assertIn('--require-budget',cmd);self.assertIn('conservative-assumption',cmd);self.assertIn('3000',cmd);self.assertIn('v1-observer.py',cmd[2])
 def test_cli_during_get_marks_real_post_interval_overlap(self):
  import time,sys
  class H(http.server.BaseHTTPRequestHandler):
   def do_GET(self):
    body=b'{"data":{"status":"ISSUED"}}';self.send_response(200);self.send_header('Content-Length',str(len(body)));self.end_headers();self.wfile.write(body)
   def log_message(self,*args):pass
  server=http.server.HTTPServer(('127.0.0.1',0),H);thread=threading.Thread(target=server.serve_forever);thread.start()
  try:
   with tempfile.TemporaryDirectory() as d:
    p=Path(d);(p/'run').mkdir();now=int(time.time()*1000);(p/'run-meta.txt').write_text('event_id=7 member_start=91\n')
    (p/'run/k6-failures.log').write_text(json.dumps({'kind':'admission','memberId':91,'target':1,'sentAtMs':now-100,'respondedAtMs':now+5000}))
    (p/'run/k6-started-host-ms.txt').write_text(str(now-100));(p/'run/k6-finished-host-ms.txt').write_text(str(now+5000))
    r=subprocess.run([sys.executable,str(HERE/'get-smoke.py'),str(p),'http://127.0.0.1:'+str(server.server_port),'--mode','during'],capture_output=True,text=True,timeout=10)
    self.assertEqual(r.returncode,0,r.stderr);s=json.loads((p/'get-during-condition.json').read_text());self.assertEqual(s['observed_get_active_post_overlap_n'],1);self.assertEqual(s['mode'],'during');self.assertEqual(s['get_latency_ms']['http_200']['n'],1)
  finally:server.shutdown();thread.join();server.server_close()
 def test_bad_json_200_remains_in_observer_poll_denominator(self):
  import time,sys
  seen=[0]
  class H(http.server.BaseHTTPRequestHandler):
   def do_GET(self):
    seen[0]+=1;body=b'invalid-json' if seen[0]==1 else b'{"data":{"status":"ISSUED"}}'
    self.send_response(200);self.send_header('Content-Length',str(len(body)));self.end_headers();self.wfile.write(body)
   def log_message(self,*args):pass
  server=http.server.HTTPServer(('127.0.0.1',0),H);thread=threading.Thread(target=server.serve_forever);thread.start()
  try:
   with tempfile.TemporaryDirectory() as d:
    p=Path(d);(p/'run').mkdir();now=int(time.time()*1000);(p/'run-meta.txt').write_text('event_id=7 member_start=91\n')
    (p/'run/k6-failures.log').write_text(json.dumps({'kind':'admission','memberId':91,'target':1,'category':'success','sentAtMs':now,'respondedAtMs':now+1}))
    r=subprocess.run([sys.executable,str(HERE/'get-smoke.py'),str(p),'http://127.0.0.1:'+str(server.server_port),'--mode','idle'],capture_output=True,text=True,timeout=10)
    self.assertEqual(r.returncode,0,r.stderr);s=json.loads((p/'get-idle/observer-summary.json').read_text());self.assertEqual(s['polls']['errors']['bad_json'],1)
    n=sum(v['n'] for v in s['get_latency_ms']['by_http_status'].values())+s['get_latency_ms']['timeouts_or_connection_errors_not_in_latency']+s['polls']['errors']['bad_json']
    self.assertEqual(n,s['polls']['total']);self.assertEqual(s['polls']['total'],2)
  finally:server.shutdown();thread.join();server.server_close()
 def test_guard_refuses_local_before_sudo_or_docker(self):
  r=subprocess.run(['bash',str(HERE/'observer-scope.sh')],env={'PATH':'/usr/bin:/bin','GITHUB_ACTIONS':'false'},capture_output=True,text=True);self.assertEqual(r.returncode,2)
 def test_no_escalation_or_current_branch_trigger(self):
  s=(HERE/'run-calibration.sh').read_text();self.assertIn('RATE=100 DURATION=2s',s);self.assertIn('VUS=201 MAX_VUS=201',s);self.assertNotIn('RATE=1000',s)
  workflow=(HERE.parents[2]/'.github/workflows/hosted-calibration.yml').read_text();self.assertIn('branches: [test/issue-177-profile-b-calibration-approved]',workflow);self.assertIn('timeout-minutes: 30',workflow);self.assertIn('if: always()',workflow)
 def test_scope_recording_preserves_failure_and_checks_owner_before_stop(self):
  for mode in ('owned','mismatch','existing','gone'):
   with tempfile.TemporaryDirectory() as d:
    p=Path(d);(p/'uname').write_text('#!/bin/sh\necho x86_64\n');(p/'uname').chmod(0o755)
    script="""#!/usr/bin/env python3
import json,os,sys
from pathlib import Path
root=Path(os.environ['RECORD_ROOT']);args=sys.argv[1:]
with (root/'calls').open('a') as f:f.write(json.dumps(args)+'\n')
if 'systemd-run' in args:
 (root/'created').touch();sys.exit(7)
mode=os.environ['RECORD_MODE']
if '--property=LoadState' in args:print('loaded' if mode=='existing' or ((root/'created').exists() and mode!='gone') else 'not-found')
elif '--property=Description' in args:print('other-owner' if mode=='mismatch' else 'coupon-calibration-owner-123-1')
"""
    (p/'sudo').write_text(script);(p/'sudo').chmod(0o755)
    env=dict(os.environ,PATH=str(p)+':'+os.environ['PATH'],GITHUB_ACTIONS='true',RUNNER_OS='Linux',GITHUB_RUN_ID='123',GITHUB_RUN_ATTEMPT='1',RUNNER_TEMP=d,GITHUB_WORKSPACE=d,RECORD_ROOT=d,RECORD_MODE=mode)
    r=subprocess.run(['bash',str(HERE/'observer-scope.sh')],env=env,capture_output=True,text=True)
    calls=[json.loads(line) for line in (p/'calls').read_text().splitlines()]
    self.assertEqual(r.returncode,2 if mode=='existing' else 7,r.stderr)
    stops=[call for call in calls if 'stop' in call];self.assertEqual(bool(stops),mode=='owned')
    if mode=='existing':self.assertFalse(any('systemd-run' in call for call in calls))
 def test_scope_launcher_has_aggregate_limits_and_metadata_only(self):
  s=(HERE/'observer-scope.sh').read_text();self.assertIn('CPUQuota=25%',s);self.assertIn('MemoryMax=512M',s);self.assertIn('MemorySwapMax=0',s);self.assertNotIn('PASSWORD',s);self.assertNotIn('sudo -E',s)
unittest.main()
