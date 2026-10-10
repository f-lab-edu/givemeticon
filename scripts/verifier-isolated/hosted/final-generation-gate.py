#!/usr/bin/env python3
"""Explicit target-generation gate, separate from latency/integrity and set comparison."""
import importlib.util,json,sys
from pathlib import Path
spec=importlib.util.spec_from_file_location('workload',Path(__file__).parents[1]/'compare-workload.py');workload=importlib.util.module_from_spec(spec);spec.loader.exec_module(workload)

def assess(root):
    stage=json.loads((root/'stage-check.json').read_text());records=workload.parse(root/'run/k6-failures.log')
    first=[r for r in records if r.get('kind')=='admission' or (r.get('kind')=='stock_request' and r.get('attempt',1)==1)]
    known=bool(first) and all(isinstance(r.get('inWindow'),bool) for r in first)
    inside=sum(r.get('inWindow') is True for r in first)
    reason='GENERATION_MET'
    if not known or len(first)!=stage['actual_iterations']:reason='EVIDENCE_MISSING_OR_MISMATCH'
    elif inside<stage['requested_unique_iterations'] or stage['dropped_iterations']!=0 or stage['oom_observed']:reason='GENERATION_MISSED'
    return {'path':root.name,'first_sent':len(first),'first_in_window':inside,'requested':stage['requested_unique_iterations'],'dropped':stage['dropped_iterations'],'reason':reason}

def evaluate(roots):
    results=[]
    for root in roots:
        try:results.append(assess(Path(root)))
        except (OSError,ValueError,KeyError):results.append({'path':str(root),'reason':'EVIDENCE_MISSING_OR_MISMATCH'})
    reasons={r['reason'] for r in results};code=4 if 'EVIDENCE_MISSING_OR_MISMATCH' in reasons else 3 if 'GENERATION_MISSED' in reasons else 0
    return code,{'exit_code':code,'stages':results,'scope':'target generation only; zero is not baseline or SLO PASS'}
if __name__=='__main__':
    code,result=evaluate(sys.argv[1:]);print(json.dumps(result,indent=2));sys.exit(code)
