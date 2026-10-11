#!/usr/bin/env python3
import hashlib,importlib.util,json,subprocess,tempfile,unittest
from pathlib import Path
HERE=Path(__file__).parent;REPO=HERE.parents[2]
spec=importlib.util.spec_from_file_location('stage',HERE/'warmed-stage.py');stage=importlib.util.module_from_spec(spec);spec.loader.exec_module(stage)
class Warmed(unittest.TestCase):
 def test_dropped_is_demand_not_occupancy(self):
  rows=[{'kind':k,'iterationKey':i,'atMs':t,'completedNormally':True} for k,i,t in [('iteration_started','0',0),('iteration_started','1',1),('iteration_completed','0',2),('iteration_completed','1',3)]]
  r=stage.occupancy(rows,8);self.assertTrue(r['pass']);self.assertEqual(r['actual_occupancy_peak'],2);self.assertEqual(r['scheduled_demand_backlog_at_end'],8)
 def test_aborted_duplicate_iteration_and_missing_start(self):
  r=stage.occupancy([{'kind':'iteration_started','iterationKey':'0','atMs':0}],0);self.assertFalse(r['pass']);self.assertEqual(r['unfinished_iteration_keys'],['0'])
  r=stage.occupancy([{'kind':'iteration_completed','iterationKey':'0','atMs':0,'completedNormally':False}],0);self.assertFalse(r['pass'])
 def test_stock_pending_does_not_prove_drain(self):
  with tempfile.TemporaryDirectory() as d:
   p=Path(d);(p/'run').mkdir();
   for a in (1,2):
    for when in ('before','after'):
     (p/f'run/app{a}-{when}.prom').write_text('process_start_time_seconds 10\n');(p/f'run/app{a}-{when}.prom.exit.txt').write_text('0')
   r=stage.drain(p,'stock',[],{'pass':True},True);self.assertFalse(r['warmup_transition_allowed']);self.assertEqual(r['server_residual_processing'],'UNKNOWN')
 def test_direct_http_completions_and_restart(self):
  with tempfile.TemporaryDirectory() as d:
   p=Path(d);(p/'run').mkdir();rows=[{'kind':'stock_request','target':1},{'kind':'stock_request','target':2}]
   for a in (1,2):
    for when,n in [('before',2),('after',3)]:
     (p/f'run/app{a}-{when}.prom.exit.txt').write_text('0')
     (p/f'run/app{a}-{when}.prom').write_text(f'process_start_time_seconds 10\nprocess_uptime_seconds {10 if when=="before" else 20}\njvm_info{{version="17"}} 1\nhttp_server_requests_seconds_count{{method="POST",uri="/internal/loadtest/coupons",status="200"}} {n}\n')
   for a in (1,2):
    target=f'http://127.0.0.1:{18079+a}/actuator/prometheus';sc={}
    for when in ('before','after'):
     f=p/f'run/app{a}-{when}.prom';sc[when]={'exit_code':0,'http_status':200,'requested_target':target,'effective_target':target,'body_bytes':f.stat().st_size,'body_sha256':hashlib.sha256(f.read_bytes()).hexdigest()}
    (p/f'run/app{a}-scrape-provenance.json').write_text(json.dumps({'schema':'scrape-provenance/v1','expected_target':target,'scrapes':sc,'meter_policy':{'custom_app_meter_filter_or_registry_remove_used':False,'framework_meter_filters':['synthetic Boot filters'],'effective_metrics_config':{'max_uri_tags':100,'enable':{},'source_sha256':'synthetic'},'evidence':'synthetic complete capture policy','spring_boot':'3.1.3','micrometer':'1.11.3','registration_paths':{'http_server_requests':'synthetic registration fixture'}}}))
   self.assertTrue(stage.drain(p,'stock',rows,{'pass':True},True)['warmup_transition_allowed'])
   f=p/'run/app1-after.prom';f.write_text(f.read_text().replace('seconds 10','seconds 11'))
   self.assertFalse(stage.drain(p,'stock',rows,{'pass':True},True)['warmup_transition_allowed'])
 def test_http_full_label_set_new_status_and_exception_refuse(self):
  base=tuple(sorted({'method':'POST','uri':'/internal/loadtest/coupons','status':'200','outcome':'SUCCESS','exception':'None'}.items()))
  for change in ({'status':'429','outcome':'CLIENT_ERROR'},{'status':'500','outcome':'SERVER_ERROR'},{'status':'503'},{'exception':'RuntimeException'}):
   new=dict(base);new.update(change);new=tuple(sorted(new.items()))
   with self.subTest(change=change):
    r=stage.direct_http_delta({base:2},{base:2,new:1});self.assertFalse(r['pass']);self.assertIsNone(r['delta']);self.assertEqual(r['new_series'],[dict(new)])
 def test_http_existing_multistatus_deltas_label_order_and_reset(self):
  a=(('method','POST'),('status','200'),('uri','/internal/loadtest/coupons'));b=(('method','POST'),('status','429'),('uri','/internal/loadtest/coupons'))
  r=stage.direct_http_delta({a:10,b:2},{a:12,b:3});self.assertTrue(r['pass']);self.assertEqual(r['delta'],3)
  self.assertEqual(stage.direct_http_delta({a:10,b:2},{a:9,b:4})['baseline_status'],'RESET')
  self.assertEqual(stage.direct_http_delta({a:10,b:2},{a:12})['baseline_status'],'MISSING_SERIES')
  with tempfile.TemporaryDirectory() as d:
   p=Path(d)/'sample.prom';p.write_text('http_server_requests_seconds_count{uri="/internal/loadtest/coupons",status="200",method="POST"} 2\n')
   values,_,invalid=stage.http_series(p,'/internal/loadtest/coupons');self.assertFalse(invalid);self.assertEqual(values,{a:2})
   p.write_text('http_server_requests_seconds_count{method="POST",uri="/internal/loadtest/coupons",status="200"} 3\n')
   after,_,invalid=stage.http_series(p,'/internal/loadtest/coupons');self.assertFalse(invalid);self.assertEqual(stage.direct_http_delta(values,after)['delta'],1)
 def test_drain_new_status_is_missing_even_if_aggregate_matches_demand(self):
  with tempfile.TemporaryDirectory() as d:
   p=Path(d);(p/'run').mkdir()
   for app in (1,2):
    for when in ('before','after'):
     text='process_start_time_seconds 10\nprocess_uptime_seconds 20\njvm_info{version="17"} 1\nhttp_server_requests_seconds_count{method="POST",uri="/internal/loadtest/coupons",status="200"} 2\n'
     if when=='after':text+='http_server_requests_seconds_count{method="POST",uri="/internal/loadtest/coupons",status="429"} 1\n'
     (p/f'run/app{app}-{when}.prom').write_text(text);(p/f'run/app{app}-{when}.prom.exit.txt').write_text('0')
   rows=[{'kind':'stock_request','target':app} for app in (1,2)]
   r=stage.drain(p,'stock',rows,{'pass':True},True);self.assertFalse(r['warmup_transition_allowed'])
   for c in r['http_completion']:
    self.assertEqual(c['baseline_status'],'MISSING_NEW_SERIES');self.assertIsNone(c['delta']);self.assertFalse(c['inferred_zero_baseline']);self.assertEqual(c['series_baseline']['new_series'][0]['status'],'429')
 def test_http_absent_before_never_inferred_for_gate(self):
  with tempfile.TemporaryDirectory() as d:
   p=Path(d);(p/'run').mkdir()
   for app in (1,2):
    for when in ('before','after'):
     (p/f'run/app{app}-{when}.prom').write_text('process_start_time_seconds 10\nprocess_uptime_seconds 20\njvm_info{version="17"} 1\n'+('http_server_requests_seconds_count{method="POST",uri="/internal/loadtest/coupons",status="200"} 1\n' if when=='after' else ''))
     (p/f'run/app{app}-{when}.prom.exit.txt').write_text('0')
   r=stage.drain(p,'stock',[],{'pass':True},True);self.assertFalse(r['warmup_transition_allowed']);self.assertTrue(all(x['baseline_status']=='MISSING_NEW_SERIES' and not x['inferred_zero_baseline'] for x in r['http_completion']))
 def test_registration_is_separate_and_failed_response_refuses(self):
  spec=importlib.util.spec_from_file_location('reg',HERE/'warmed-registration.py');m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
  with tempfile.TemporaryDirectory() as d:
   p=Path(d);(p/'run').mkdir()
   for app in (1,2):(p/f'run/app{app}-after.prom').write_text('process_start_time_seconds 10\nhttp_server_requests_seconds_count{method="POST",uri="/internal/loadtest/coupons",status="200"} 1\n')
   rows=[{'kind':'stock_request','target':app,'status':200,'sentAtMs':10,'respondedAtMs':20} for app in (1,2)]
   with __import__('unittest.mock',fromlist=['patch']).patch.object(m.stage.gate.workload,'parse',return_value=rows):
    r=m.assess(p,'stock');self.assertTrue(r['pass']);self.assertTrue(r['excluded_from_warmup_and_measurement']);self.assertEqual(r['registered_http_label_keys_after']['1'][0],{'method':'POST','status':'200','uri':'/internal/loadtest/coupons'});rows[0]['status']=429;self.assertFalse(m.assess(p,'stock')['pass'])
  text=(HERE/'run-warmed.sh').read_text();self.assertLess(text.index('warmed-registration.py'),text.index('DURATION=20s'));self.assertIn('USER_ID_START=900000000 MEMBER_ID_START=700000000',text)
 def test_v1_worker_unresolved_refuses(self):
  with tempfile.TemporaryDirectory() as d:
   p=Path(d);(p/'run').mkdir();f=p/'run/unresolved-after-quiescence.txt';f.write_text('1')
   self.assertFalse(stage.drain(p,'v1',[],{'pass':True},True)['warmup_transition_allowed']);f.write_text('0')
   self.assertTrue(stage.drain(p,'v1',[],{'pass':True},True)['warmup_transition_allowed'])
 def test_actual_scripts_preflight_no_http_and_sequential_ledger(self):
  js=r'''
const fs=require('fs'),vm=require('vm');let source=fs.readFileSync(process.argv[1],'utf8');source=source.replace(/^import .*;\n/gm,'').replace(/export const /g,'const ').replace('export default function ()','function runIteration()');
let logs=[],posts=0;class Metric{add(){}};let c={__ENV:{EVENT_ID:'1',STOCK_ID:'1',INITIALIZATION_PREFLIGHT:process.argv[2],ITERATION_LEDGER:'true',PRE_ALLOCATED_VUS:'201',MAX_VUS:'201',POLL_BUDGET_MS:'0'},exec:{scenario:{iterationInTest:0,startTime:Date.now()},vu:{idInTest:1,iterationInScenario:0}},Counter:Metric,Trend:Metric,sleep:()=>{},check:()=>{},http:{post:()=>{posts++;return {status:200,body:'{"data":{"status":"ENDED"}}',timings:{duration:1}}}},Date,Math,Number,String,Boolean,JSON,console:{log:s=>logs.push(JSON.parse(s))}};
vm.createContext(c);vm.runInContext(source+'\nrunIteration();globalThis.result=options;',c);process.stdout.write(JSON.stringify({logs,posts,scenarios:c.result.scenarios}));
'''
  for script in (REPO/'scripts/verifier-isolated/stock-arrival-record.js',REPO/'scripts/coupon-integrated-loadtest/integrated-admission-poll.js'):
   pre=json.loads(subprocess.check_output(['node','-e',js,str(script),'true'],text=True));self.assertEqual(pre['posts'],0);self.assertEqual(pre['scenarios']['initialization']['vus'],201)
   live=json.loads(subprocess.check_output(['node','-e',js,str(script),'false'],text=True));self.assertEqual(live['posts'],2)
   self.assertEqual(live['logs'][0]['kind'],'iteration_started');self.assertEqual(live['logs'][-1]['kind'],'iteration_completed');self.assertTrue(live['logs'][-1]['completedNormally'])
 def test_db_missing_and_duplicate_refuse(self):
  with tempfile.TemporaryDirectory() as d:
   root=Path(d);self.assertFalse(stage.db_reconciliation(root,'stock')['pass']);(root/'analysis').mkdir()
   data={'integrity':{'verdict':'OK'},'db':{'duplicate_users':0,'over_issued':0},'success_response_but_not_in_db':0}
   f=root/'analysis/stock-summary.json';f.write_text(json.dumps(data));self.assertTrue(stage.db_reconciliation(root,'stock')['pass'])
   data['db']['duplicate_users']=1;f.write_text(json.dumps(data));self.assertFalse(stage.db_reconciliation(root,'stock')['pass'])
 def test_preflight_missing_evidence_refuses(self):
  spec=importlib.util.spec_from_file_location('preflight',HERE/'warmed-preflight.py');m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
  with tempfile.TemporaryDirectory() as d:
   root=Path(d);(root/'run').mkdir();(root/'run/k6-summary.json').write_text(json.dumps({'metrics':{'iterations':{'count':201}}}));(root/'run/k6-cgroup.jsonl').write_text('')
   self.assertFalse(m.assess(root,201)['pass'])
 def test_no_expansion_or_trigger_branch_creation(self):
  s=(HERE/'run-warmed.sh').read_text();self.assertIn('for size in 201 401',s);self.assertIn('DURATION=20s',s);self.assertIn('DURATION=10s',s);self.assertNotIn('RATE=20',s);self.assertNotIn('RATE=40',s)
  workflow=(REPO/'.github/workflows/hosted-warmed.yml').read_text();self.assertIn('test/issue-177-warmed-workflow-approved',workflow);self.assertNotIn('workflow_dispatch',workflow);self.assertIn('timeout-minutes: 30',workflow)
  self.assertLess(workflow.index('cleanup-warmed.sh'),workflow.index('rm -f'));self.assertLess(workflow.index('rm -f'),workflow.index('sha256sum'))
unittest.main()
