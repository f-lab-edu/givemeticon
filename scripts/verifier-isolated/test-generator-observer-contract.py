#!/usr/bin/env python3
"""Execute repository generator with mocked k6 runtime, then consume its real log shape."""
import json,pathlib,subprocess,sys,tempfile,unittest
HERE=pathlib.Path(__file__).parent
GENERATOR=HERE.parent/'coupon-integrated-loadtest/integrated-admission-poll.js'
class Contract(unittest.TestCase):
 def test_ended_duplicate_timestamps_and_missing_response(self):
  js=r'''
const fs=require('fs'),vm=require('vm');let source=fs.readFileSync(process.argv[1],'utf8');
source=source.replace(/^import .*;\n/gm,'').replace(/export const /g,'const ').replace('export default function ()','function runIteration()');
const logs=[];let now=Date.now();class Metric {add(){}}
const context={__ENV:{EVENT_ID:'1',DUPLICATE_RATE:'0.1'},Counter:Metric,Trend:Metric,
 exec:{scenario:{iterationInTest:0,startTime:now}},http:{post:()=>({status:200,body:'{"data":{"status":"ENDED"}}',timings:{duration:1}})},
 check:()=>{},sleep:()=>{},Date:{now:()=>now++},Math,Number,String,Boolean,JSON,
 console:{log:s=>logs.push(JSON.parse(s))}};
vm.createContext(context);vm.runInContext(source+'\nrunIteration();',context);process.stdout.write(JSON.stringify(logs));
'''
  records=json.loads(subprocess.check_output(['node','-e',js,str(GENERATOR)],text=True))
  self.assertEqual([r['kind'] for r in records],['admission','duplicate'])
  for r in records:
   self.assertIsInstance(r['sentAtMs'],int);self.assertIsInstance(r['respondedAtMs'],int)
   self.assertGreaterEqual(r['respondedAtMs'],r['sentAtMs']);self.assertEqual(r['category'],'event_closed')
  for missing in (False,True):
   rows=[dict(r) for r in records]
   if missing:del rows[1]['respondedAtMs']
   with tempfile.TemporaryDirectory() as d:
    root=pathlib.Path(d);log=root/'k6.log'
    log.write_text('\n'.join('time="2026-10-10T00:00:00Z" level=info msg='+json.dumps(json.dumps(r)) for r in rows))
    subprocess.run([sys.executable,'-B',str(HERE/'v1-observer.py'),'--post-log',str(log),'--targets','http://127.0.0.1:1','--event-id','1','--out-dir',str(root/'out'),'--assumed-latency-ms','3000','--assumed-latency-source','conservative-assumption','--require-budget'],check=True,capture_output=True,text=True)
    summary=json.loads((root/'out/observer-summary.json').read_text());row=json.loads((root/'out/observer-records.jsonl').read_text())
    metrics=summary['adr001_metrics'];self.assertEqual(summary['denominator_first_posts'],1)
    self.assertEqual(row['firstRequestResult'],'event_closed');self.assertIsNone(row['applicationResult'])
    self.assertEqual(metrics['issuance_result_coverage']['count'],0)
    self.assertEqual(metrics['business_outcome_convergence']['business_ended_untimed_not_counted'],int(missing))
    self.assertEqual(metrics['business_outcome_convergence']['count'],int(not missing))
unittest.main()
