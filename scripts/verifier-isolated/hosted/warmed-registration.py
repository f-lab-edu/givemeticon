#!/usr/bin/env python3
"""Separate owned registration fixture evidence; not warmup or measurement demand."""
import importlib.util,json,sys
from pathlib import Path
spec=importlib.util.spec_from_file_location('stage',Path(__file__).with_name('warmed-stage.py'));stage=importlib.util.module_from_spec(spec);spec.loader.exec_module(stage)
def assess(root,path):
    records=stage.gate.workload.parse(root/'run/k6-failures.log')
    kinds=('stock_request',) if path=='stock' else ('admission','duplicate')
    posts=[r for r in records if r.get('kind') in kinds]
    per_app={str(app):[{'target':r.get('target'),'status':r.get('status'),'attempt':r.get('attempt'),'memberId':r.get('memberId'),'userId':r.get('userId'),'sentAtMs':r.get('sentAtMs'),'respondedAtMs':r.get('respondedAtMs')} for r in posts if r.get('target')==app] for app in (1,2)}
    observed={}
    for app in (1,2):
        if path=='stock':
            count,_=stage.http_counts(root/f'run/app{app}-after.prom','/internal/loadtest/coupons')
            observed[str(app)]=count
    ok=all(per_app[str(app)] for app in (1,2)) and all(r.get('status')==200 and type(r.get('sentAtMs')) in (int,float) and r['sentAtMs']>0 and type(r.get('respondedAtMs')) in (int,float) and r['respondedAtMs']>=r['sentAtMs'] for r in posts)
    if path=='stock':ok=ok and all(observed[str(app)] is not None and observed[str(app)]>=len(per_app[str(app)]) for app in (1,2))
    result={'pass':bool(ok),'phase':'registration_only','fixture_ref':'run-meta.txt','requests_by_app':per_app,'observed_http_counter_after':observed,'excluded_from_warmup_and_measurement':True,'note':'Actual completed requests on separate owned fixture. New status series are not assigned zero. Later before must directly expose aggregate POST counter.'}
    (root/'registration-evidence.json').write_text(json.dumps(result,indent=2)+'\n');return result
if __name__=='__main__':sys.exit(0 if assess(Path(sys.argv[1]),sys.argv[2])['pass'] else 3)
