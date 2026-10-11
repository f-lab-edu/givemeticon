#!/usr/bin/env python3
"""Initialize actual workload script201/401 VUs under B caps, with no HTTP."""
import importlib.util,json,os,subprocess,sys
from pathlib import Path
HERE=Path(__file__).parent

def module(name,file):
    s=importlib.util.spec_from_file_location(name,HERE/file);m=importlib.util.module_from_spec(s);s.loader.exec_module(m);return m

def assess(root,size):
    e=module('gate','final-generation-gate.py').generator_evidence(root)
    summary=json.loads((root/'run/k6-summary.json').read_text())
    def count(n):
        m=summary.get('metrics',{}).get(n,{});return m.get('count',m.get('values',{}).get('count',0))
    counts={'iterations':count('iterations'),'http_reqs':count('http_reqs'),'dropped':count('dropped_iterations')}
    limits=module('caps','verify-calibration-caps.py').check_generator(root/'run/k6-cgroup.jsonl',os.environ.get('VERIFIER_OWNER_ID',''))
    ready=(limits['pass'] and e['live_samples']>=2 and e['oom_state'] is False and counts['iterations']==size and counts['http_reqs']==0 and counts['dropped']==0
           and e['memory_events_delta'].get('oom')==0 and e['memory_events_delta'].get('oom_kill')==0)
    return {'pass':ready,'size':size,'counts':counts,'limits':limits,'resources':e,'scope':'actual module/VU initialization only; no HTTP, throughput, occupancy or large-VU admission proof'}

def execute(path,size,root):
    if os.environ.get('GITHUB_ACTIONS')!='true' or os.environ.get('RUNNER_OS')!='Linux':return 2
    if path not in ('stock','v1') or size not in (201,401):return 2
    root=root.resolve();(root/'run').mkdir(parents=True,exist_ok=True)
    repo=HERE.parents[2];script=repo/('scripts/verifier-isolated/stock-arrival-record.js' if path=='stock' else 'scripts/coupon-integrated-loadtest/integrated-admission-poll.js')
    owner=os.environ['VERIFIER_OWNER_ID'];prefix=os.environ['VERIFIER_PREFIX']
    (root/'run-meta.txt').write_text(f'k6_pre_allocated_vus={size}\nk6_max_vus={size}\n')
    command=[sys.executable,str(HERE.parent/'run-k6-recorded.py'),f'{prefix}-k6',owner,str(root/'run/k6-cgroup.jsonl'),
             '--label',f'xyz.buzz.verifier.owner={owner}','--label','xyz.buzz.verifier.managed=capped-harness','--oom-score-adj=1000',
             '--network',f'{prefix}-net','--cpus','0.75','--memory','3072m','--memory-swap','3072m',
             '-v',f'{script.parent}:/scripts:ro','-v',f'{root}/run:/results','--user',f'{os.getuid()}:{os.getgid()}']
    for value in ['INITIALIZATION_PREFLIGHT=true','ITERATION_LEDGER=true',f'PRE_ALLOCATED_VUS={size}',f'MAX_VUS={size}','EVENT_ID=1','STOCK_ID=1','POLL_BUDGET_MS=0']:command+=['-e',value]
    command += [os.environ['K6_IMAGE'],'run','--summary-export','/results/k6-summary.json','--console-output','/results/k6-failures.log',f'/scripts/{script.name}']
    with (root/'run/k6-console.log').open('w') as output:rc=subprocess.run(command,stdout=output,stderr=subprocess.STDOUT).returncode
    try:result=assess(root,size)
    except (OSError,ValueError,KeyError):result={'pass':False,'reason':'EVIDENCE_MISSING'}
    result['generator_exit']=rc;result['pass']=result['pass'] and rc==0
    (root/'preflight.json').write_text(json.dumps(result,indent=2)+'\n');return 0 if result['pass'] else 3
if __name__=='__main__':sys.exit(execute(sys.argv[1],int(sys.argv[2]),Path(sys.argv[3])))
