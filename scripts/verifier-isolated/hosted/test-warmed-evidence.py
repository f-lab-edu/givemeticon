#!/usr/bin/env python3
import copy,hashlib,importlib.util,json,tempfile,unittest
from pathlib import Path
HERE=Path(__file__).parent

def module(name,file):
 s=importlib.util.spec_from_file_location(name,HERE/file);m=importlib.util.module_from_spec(s);s.loader.exec_module(m);return m
pre=module('pre','warmed-preflight.py');stable=module('stable','warmed-stabilization.py')
class Preflight(unittest.TestCase):
 def fixture(self,p):
  (p/'run').mkdir();(p/'run-meta.txt').write_text('k6_pre_allocated_vus=201\nk6_max_vus=201\n')
  metrics={'iterations':{'count':201},'vus':{'max':201},'vus_max':{'max':201}}
  (p/'run/k6-summary.json').write_text(json.dumps({'metrics':metrics}))
  cg='[cpu.max]\n75000 100000\n[memory.max]\n3221225472\n[memory.swap.max]\n0\n[memory.peak]\n1024\n[memory.events]\noom 0\noom_kill 0\n[cpu.stat]\nusage_usec 10\nnr_periods 2\nnr_throttled 0\nthrottled_usec 0\n'
  state={'cpu_nano':750000000,'memory':3221225472,'memswap':3221225472,'oom':False}
  rows=[{'host_ms':n,'state_status':0,'state':json.dumps(state),'cgroup_status':0,'cgroup':cg} for n in (1,2)]
  (p/'run/k6-cgroup.jsonl').write_text('\n'.join(json.dumps(r) for r in rows))
  records=[{'kind':k,'iterationKey':str(i),'atMs':t,'completedNormally':True} for i in range(201) for k,t in [('iteration_started',1),('iteration_completed',2)]]
  (p/'run/k6-failures.log').write_text('\n'.join(json.dumps(r) for r in records))
  script=HERE.parents[2]/'scripts/verifier-isolated/stock-arrival-record.js'
  (p/'preflight-mode.json').write_text(json.dumps({'path':'stock','initialization_preflight':True,'iterations_per_vu':1,'script_sha256':hashlib.sha256(script.read_bytes()).hexdigest()}))
  return metrics
 def test_absent_optional_is_not_observed_zero(self):
  with tempfile.TemporaryDirectory() as d:
   p=Path(d);self.fixture(p);r=pre.assess(p,201);self.assertTrue(r['pass'])
   self.assertEqual(r['counts']['http_reqs'],{'status':'NOT_EMITTED','value':None});self.assertTrue(r['not_emitted_policy']['allowed'])
 def test_emitted_zero_and_unexpected_http(self):
  with tempfile.TemporaryDirectory() as d:
   p=Path(d);metrics=self.fixture(p)
   for n in (0,1):
    metrics['http_reqs']={'count':n};metrics['dropped_iterations']={'count':0};(p/'run/k6-summary.json').write_text(json.dumps({'metrics':metrics}))
    r=pre.assess(p,201);self.assertEqual(r['pass'],n==0);self.assertEqual(r['counts']['http_reqs']['status'],'OBSERVED')
 def test_missing_required_or_bad_metrics_refuse(self):
  for name in ('iterations','vus','vus_max'):
   with self.subTest(name=name),tempfile.TemporaryDirectory() as d:
    p=Path(d);metrics=self.fixture(p);del metrics[name];(p/'run/k6-summary.json').write_text(json.dumps({'metrics':metrics}));self.assertFalse(pre.assess(p,201)['pass'])
  with tempfile.TemporaryDirectory() as d:
   p=Path(d);metrics=self.fixture(p);metrics['http_reqs']={'count':'0'};(p/'run/k6-summary.json').write_text(json.dumps({'metrics':metrics}));r=pre.assess(p,201);self.assertFalse(r['pass']);self.assertEqual(r['counts']['http_reqs']['status'],'MALFORMED')
 def test_corrupt_summary_and_settings_refuse(self):
  with tempfile.TemporaryDirectory() as d:
   p=Path(d);self.fixture(p);(p/'run-meta.txt').write_text('');self.assertFalse(pre.assess(p,201)['pass'])
   (p/'run/k6-summary.json').write_text('{broken');self.assertEqual(pre.assess(p,201)['reason'],'DAMAGED_OR_MISSING_SUMMARY')
 def test_missing_mode_or_ledger_does_not_allow_optional(self):
  with tempfile.TemporaryDirectory() as d:
   p=Path(d);self.fixture(p);(p/'preflight-mode.json').unlink();self.assertFalse(pre.assess(p,201)['pass'])
  with tempfile.TemporaryDirectory() as d:
   p=Path(d);self.fixture(p);(p/'run/k6-failures.log').unlink();self.assertFalse(pre.assess(p,201)['pass'])
class Stabilization(unittest.TestCase):
 def body(self,start=10,active=0,pending=0):
  return f'process_start_time_seconds{{application="g",}} {start}\nhikaricp_connections_active{{pool="HikariPool-1",}} {active}\nhikaricp_connections_pending{{pool="HikariPool-1",}} {pending}\nhttp_server_requests_seconds_count{{uri="/test-support/coupon-events/{{eventId}}/applications",method="POST",}} 201\n'
 def fixture(self,path='stock'):
  baseline=[stable.metrics(self.body()),stable.metrics(self.body())];db=[1000,800,200,0,0] if path=='stock' else [1000,200,200,0,200,0]
  sample={'db_exit':0,'db':db,'apps':[{'target':f'http://127.0.0.1:{18079+a}/actuator/prometheus','effective_target':f'http://127.0.0.1:{18079+a}/actuator/prometheus','scrape_exit':0,'body':self.body()} for a in (1,2)]}
  return [copy.deepcopy(sample) for _ in range(5)],baseline
 def test_normal_both_paths(self):
  for path in ('stock','v1'):
   s,b=self.fixture(path);self.assertTrue(stable.assess(s,b,path,200)['pass'])
 def test_restart_wrong_target_and_pool_block(self):
  for field,value in [('body',self.body(start=11)),('effective_target','http://other/actuator/prometheus'),('body',self.body(active=1)),('body',self.body(pending=1)),('scrape_exit',22)]:
   s,b=self.fixture();s[2]['apps'][0][field]=value;self.assertFalse(stable.assess(s,b,'stock',200)['pass'])
 def test_valid_http_invalid_or_missing_body_block(self):
  for body in ('<html>200 OK</html>','process_start_time_seconds 10\n',self.body().replace('hikaricp_connections_active','wrong_name'),'# comments only\n'):
   s,b=self.fixture();s[0]['apps'][0]['body']=body;self.assertFalse(stable.assess(s,b,'stock',200)['pass'])
 def test_fixture_pending_change_or_missing_block(self):
  for path in ('stock','v1'):
   for value in (None,[],[0],['1000']):
    s,b=self.fixture(path);s[1]['db']=value;self.assertFalse(stable.assess(s,b,path,200)['pass'])
   s,b=self.fixture(path);s[4]['db'][3 if path=='v1' else 4]=1;self.assertFalse(stable.assess(s,b,path,200)['pass'])
  s,b=self.fixture();s[4]['db']=[1000,799,201,0,0];self.assertFalse(stable.assess(s,b,'stock',200)['pass'])
 def test_missing_samples_or_targets_block(self):
  s,b=self.fixture();self.assertFalse(stable.assess(s[:4],b,'stock',200)['pass']);s[3]['apps'].pop();self.assertFalse(stable.assess(s,b,'stock',200)['pass'])
unittest.main()
