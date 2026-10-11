#!/usr/bin/env python3
"""Pair future captures with independently declared endpoints; never invent policy evidence."""
import argparse,hashlib,json
from pathlib import Path

def assemble(root,app,contract,policy=None):
    declared=json.loads(contract.read_text())
    if app not in ('app1','app2'):raise ValueError('unknown app')
    expected=declared['expected_prometheus_targets'][app]
    if expected!=f'http://127.0.0.1:{18080 if app=="app1" else 18081}/actuator/prometheus':raise ValueError('unexpected run endpoint')
    scrapes={}
    for phase in ('before','after'):
        body=root/f'{app}-{phase}.prom';side=json.loads(Path(str(body)+'.capture.json').read_text());raw=body.read_bytes()
        if side.get('schema_version')!=1 or side.get('requested_target')!=expected or side.get('effective_target')!=expected:raise ValueError('capture schema/target mismatch')
        if side.get('curl_exit')!=0 or side.get('http_status')!=200 or side.get('transport_body_complete') is not True:raise ValueError('failed transport')
        if not raw or type(side.get('body_bytes'))!=int or side.get('body_bytes')!=len(raw) or side.get('download_bytes')!=len(raw) or side.get('body_sha256')!=hashlib.sha256(raw).hexdigest():raise ValueError('body integrity/download mismatch; no normalization')
        scrapes[phase]={'exit_code':side['curl_exit'],'http_status':side['http_status'],'requested_target':side['requested_target'],'effective_target':side['effective_target'],'body_bytes':len(raw),'body_sha256':side['body_sha256'],'capture_start_ms':side['captured_at_start_ms'],'capture_end_ms':side['captured_at_end_ms'],'sidecar_sha256':hashlib.sha256(Path(str(body)+'.capture.json').read_bytes()).hexdigest()}
    result={'schema':'scrape-provenance/v1','expected_target':expected,'scrapes':scrapes,'run_contract':{'sha':declared['sha'],'body_sha256':hashlib.sha256(contract.read_bytes()).hexdigest()},'metric_semantic_completeness':'NOT_ESTABLISHED_BY_TRANSPORT_CAPTURE','runtime_registry_policy':'UNKNOWN unless independently evidenced; strings alone are not runtime verification'}
    if policy is not None:
        supplied=json.loads(policy.read_text())
        # Policy claims must belong to this run/app, not a copied generic template.
        if supplied.get('run_sha')!=declared['sha'] or supplied.get('app')!=app or supplied.get('expected_target')!=expected:raise ValueError('policy run identity mismatch')
        # Tie the selected Spring condition decision to the same measured JVM.
        start=supplied.get('process_start_time_seconds_at_policy')
        if type(start) not in (int,float) or start<=0:raise ValueError('policy process identity missing')
        for phase in ('before','after'):
            values=[float(line.rsplit(' ',1)[1]) for line in (root/f'{app}-{phase}.prom').read_text().splitlines() if line.startswith('process_start_time_seconds')]
            if values!=[start]:raise ValueError('policy/measured process mismatch')
        result['policy_artifact']={'path':policy.name,'sha256':hashlib.sha256(policy.read_bytes()).hexdigest()}
        for key in ('meter_policy','lock_call_sites'):
            if key in supplied:result[key]=supplied[key]
    else:result['policy_evidence']='MISSING; transport does not synthesize meter_policy or lock_call_sites'
    return result

def write(root,app,contract,policy=None):
    result=assemble(root,app,contract,policy)
    (root/f'{app}-scrape-provenance.json').write_text(json.dumps(result,indent=2)+'\n')
    return result
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('root',type=Path);p.add_argument('app',choices=['app1','app2']);p.add_argument('contract',type=Path);p.add_argument('--policy',type=Path);a=p.parse_args()
    try:write(a.root,a.app,a.contract,a.policy)
    except (OSError,ValueError,KeyError,TypeError):raise SystemExit('provenance refused: missing/mismatched capture or policy; destination unchanged')
