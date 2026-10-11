#!/usr/bin/env python3
"""Initialize actual workload script201/401 VUs under B caps, with no HTTP."""
import hashlib,importlib.util,json,math,os,subprocess,sys
from pathlib import Path
HERE=Path(__file__).parent

def module(name,file):
    s=importlib.util.spec_from_file_location(name,HERE/file);m=importlib.util.module_from_spec(s);s.loader.exec_module(m);return m

def metric(metrics,name,field):
    if name not in metrics:return {'status':'NOT_EMITTED','value':None}
    row=metrics[name]
    if not isinstance(row,dict):return {'status':'MALFORMED','value':None}
    value=row.get(field,row.get('values',{}).get(field) if isinstance(row.get('values',{}),dict) else None)
    valid=type(value) in (int,float) and math.isfinite(value) and value>=0
    if field=='count':valid=valid and int(value)==value
    return {'status':'OBSERVED' if valid else 'MALFORMED','value':value if valid else None}

def assess(root,size):
    try:
        summary=json.loads((root/'run/k6-summary.json').read_text())
        metrics=summary['metrics']
        if not isinstance(metrics,dict):raise ValueError('metrics must be object')
    except (OSError,ValueError,KeyError,TypeError):return {'pass':False,'reason':'DAMAGED_OR_MISSING_SUMMARY'}
    counts={name:metric(metrics,name,'count') for name in ('iterations','http_reqs','dropped_iterations')}
    allocated=metric(metrics,'vus_max','max');active=metric(metrics,'vus','max')
    e=module('gate','final-generation-gate.py').generator_evidence(root)
    try:records=module('gate_records','final-generation-gate.py').workload.parse(root/'run/k6-failures.log')
    except OSError:records=[]
    ledger=module('stage','warmed-stage.py').occupancy(records,0)
    try:
        mode=json.loads((root/'preflight-mode.json').read_text())
        script=HERE.parents[2]/('scripts/verifier-isolated/stock-arrival-record.js' if mode['path']=='stock' else 'scripts/coupon-integrated-loadtest/integrated-admission-poll.js')
        mode_valid=mode['path'] in ('stock','v1') and mode['initialization_preflight'] is True and mode['iterations_per_vu']==1 and mode['script_sha256']==hashlib.sha256(script.read_bytes()).hexdigest()
    except (OSError,ValueError,KeyError,TypeError):mode_valid=False
    requests_in_log=any(r.get('kind') in ('admission','duplicate','stock_request') for r in records)
    no_http_branch=mode_valid and ledger['pass'] and ledger['started_iterations']==size and ledger['completed_iterations']==size and not requests_in_log
    optional_ok=all(x['status']=='OBSERVED' and x['value']==0 or x['status']=='NOT_EMITTED' and no_http_branch for x in (counts['http_reqs'],counts['dropped_iterations']))
    vus_ok=(allocated['status']=='OBSERVED' and allocated['value']==size and active['status']=='OBSERVED' and active['value']==size
            and e['vus_configured_max']==size and e['vus_pre_allocated_configured']==size)
    limits=module('caps','verify-calibration-caps.py').check_generator(root/'run/k6-cgroup.jsonl',os.environ.get('VERIFIER_OWNER_ID',''))
    ready=(limits['pass'] and e['live_samples']>=2 and e['oom_state'] is False and counts['iterations']['status']=='OBSERVED' and counts['iterations']['value']==size and optional_ok and no_http_branch and vus_ok
           and e['memory_events_delta'].get('oom')==0 and e['memory_events_delta'].get('oom_kill')==0)
    return {'pass':ready,'size':size,'counts':counts,'vus_allocated':allocated,'vus_active':active,'configured_vus_present_and_matching':vus_ok,
            'not_emitted_policy':{'allowed':no_http_branch,'basis':'verified actual script initialization-only branch + complete201/401 iteration ledger with no request records; per-vu executor schedules no arrival-rate drops','note':'NOT_EMITTED is not directly observed HTTP/dropped zero. iterations, configured/actual VUs and malformed metrics never receive this allowance'},
            'iteration_ledger':ledger,'limits':limits,'resources':e,'scope':'actual module/VU initialization only; no HTTP, throughput, occupancy or large-VU admission proof'}

def execute(path,size,root):
    if os.environ.get('GITHUB_ACTIONS')!='true' or os.environ.get('RUNNER_OS')!='Linux':return 2
    if path not in ('stock','v1') or size not in (201,401):return 2
    root=root.resolve();(root/'run').mkdir(parents=True,exist_ok=True)
    repo=HERE.parents[2];script=repo/('scripts/verifier-isolated/stock-arrival-record.js' if path=='stock' else 'scripts/coupon-integrated-loadtest/integrated-admission-poll.js')
    owner=os.environ['VERIFIER_OWNER_ID'];prefix=os.environ['VERIFIER_PREFIX']
    (root/'preflight-mode.json').write_text(json.dumps({'path':path,'initialization_preflight':True,'iterations_per_vu':1,'script_sha256':hashlib.sha256(script.read_bytes()).hexdigest()},indent=2)+'\n')
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
