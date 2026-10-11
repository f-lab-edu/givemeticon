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
 def test_scope_throttling_delta_and_missing_reset(self):
  b={'scope_cgroup':'/same','files':{'cpu.stat':'usage_usec 10\nnr_periods 2\nnr_throttled 1\nthrottled_usec 4'}}
  a={'scope_cgroup':'/same','files':{'cpu.stat':'usage_usec 30\nnr_periods 5\nnr_throttled 3\nthrottled_usec 14'}}
  self.assertEqual(get.scope_delta(b,a)['cpu_stat_delta']['throttled_usec'],10)
  self.assertEqual(get.scope_delta(a,b)['status'],'MISSING_OR_RESET')
  self.assertIsNone(get.scope_delta({},a)['cpu_stat_delta']['nr_throttled'])
 def test_cleanup_stock_and_v1_share_exact_owned_set_and_functions(self):
  capped=HERE.parent/'capped';outputs=[]
  for script in ('run-capped-v1.sh','run-capped-stock.sh'):
   cmd='source "$1"; owned_names; printf "%s\n" "$NET" "$VERIFIER_OWNER_ID"; declare -f down remove'
   result=subprocess.run(['/bin/bash','-c',cmd,str(capped/'contract-test-wrapper'),str(capped/script)],env=dict(os.environ,VERIFIER_PREFIX='contract',VERIFIER_OWNER_ID='same-owner'),capture_output=True,text=True)
   self.assertEqual(result.returncode,0,result.stderr);outputs.append(result.stdout)
  self.assertEqual(outputs[0],outputs[1]);self.assertIn('contract-app1',outputs[0]);self.assertIn('contract-redis-coupon',outputs[0])
 def test_late_fixture_calibration_polls_without_slo_and_no_overlap_is_nonfatal(self):
  import time
  class H(http.server.BaseHTTPRequestHandler):
   def do_GET(self):
    body=b'{"data":{"status":"SOLD_OUT"}}';self.send_response(200);self.send_header('Content-Length',str(len(body)));self.end_headers();self.wfile.write(body)
   def log_message(self,*args):pass
  server=http.server.HTTPServer(('127.0.0.1',0),H);thread=threading.Thread(target=server.serve_forever);thread.start()
  try:
   with tempfile.TemporaryDirectory() as d:
    p=Path(d);(p/'run').mkdir();old=int(time.time()*1000)-600000;(p/'run-meta.txt').write_text('event_id=7\n')
    original={'kind':'admission','memberId':91,'target':1,'category':'event_closed','sentAtMs':old,'respondedAtMs':old+1}
    log=p/'run/k6-failures.log';log.write_text(json.dumps(original))
    for mode in ('during','idle'):
     result=subprocess.run([sys.executable,str(HERE/'get-smoke.py'),str(p),'http://127.0.0.1:'+str(server.server_port),'--mode',mode],capture_output=True,text=True,timeout=10)
     self.assertEqual(result.returncode,0,result.stderr)
     report=json.loads((p/('get-'+mode+'-condition.json')).read_text());self.assertEqual(report['active_post_overlap_status'],'NOT_OBSERVED');self.assertEqual(report['get_latency_active_post_only']['polls'],0)
     summary=json.loads((p/('get-'+mode)/'observer-summary.json').read_text());self.assertEqual(summary['slo']['verdict'],'NOT_APPLICABLE(calibration)');self.assertEqual(summary['polls']['total'],1);self.assertNotIn('adr001_metrics',summary)
     rows=[json.loads(x) for x in (p/('get-'+mode)/'observer-records.jsonl').read_text().splitlines()];self.assertEqual(rows[0]['firstSentAtMs'],old);self.assertEqual(rows[0]['classification'],'calibration_only')
     self.assertEqual(json.loads((p/('get-'+mode)/'post-input.log').read_text()),original)
    self.assertEqual(json.loads(log.read_text()),original)
  finally:server.shutdown();thread.join();server.server_close()
 def test_calibration_rejects_unbounded_parameters_and_members(self):
  import time
  with tempfile.TemporaryDirectory() as d:
   p=Path(d);log=p/'input';now=int(time.time()*1000)
   log.write_text(''.join(json.dumps({'kind':'admission','memberId':i,'sentAtMs':now})+'\n' for i in range(41)))
   cmd=get.command(log,'http://127.0.0.1:1',7,p/'out','idle')
   result=subprocess.run(cmd,capture_output=True,text=True);self.assertNotEqual(result.returncode,0);self.assertIn('at most 40',result.stderr)
   result=subprocess.run(cmd+['--require-budget'],capture_output=True,text=True);self.assertNotEqual(result.returncode,0);self.assertIn('calibration requires',result.stderr)
 def test_caps_owner_and_budget(self):
  state={'labels':{'xyz.buzz.verifier.owner':'job','xyz.buzz.verifier.managed':'capped-harness'},'cpu':550000000,'memory':768*1024**2,'memswap':768*1024**2,'running':True}
  self.assertTrue(caps.check(state,.55,768*1024**2,'job'));self.assertFalse(caps.check(state,.55,768*1024**2,'other'));self.assertFalse(caps.check(state,1,768*1024**2,'job'))
 def test_generator_actual_limits_and_missing_live_snapshot(self):
  with tempfile.TemporaryDirectory() as d:
   p=Path(d)/'k6.jsonl';p.write_text(json.dumps({'state_status':0,'cgroup_status':0,'state':json.dumps({'cpu_nano':750000000,'memory':3072*1024**2,'memswap':3072*1024**2}),'cgroup':'[cpu.max]\n75000 100000\n[memory.max]\n3221225472\n[memory.swap.max]\n0\n'})+'\n')
   self.assertTrue(caps.check_generator(p,'job')['pass']);p.write_text('');self.assertFalse(caps.check_generator(p,'job')['pass'])
 def test_b_capacity_is_distinct_and_fixed(self):
  b=capacity.assess(4,15000,10000,'B');self.assertEqual(b['required_memory_mib'],9088);self.assertEqual(b['caps']['mysql']['cpu'],1);self.assertEqual(b['caps']['apps']['cpu_each'],.55);self.assertIn('broker_empty_slot',b['caps']);self.assertFalse(capacity.assess(4,8000,10000,'B')['pass'])
 def test_adapter_uses_explicit_calibration_without_slo_budget(self):
  cmd=get.command('input','http://a',7,'out','idle');self.assertNotIn('--require-budget',cmd);self.assertIn('--calibration',cmd);self.assertIn('conservative-assumption',cmd);self.assertIn('3000',cmd);self.assertIn('v1-observer.py',cmd[2])
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
    script=r"""#!/usr/bin/env python3
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
