#!/usr/bin/env python3
"""Capability gate, not a performance or consistency PASS."""
import argparse,json
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('root');p.add_argument('requested',type=int);p.add_argument('--diagnostic',action='store_true');a=p.parse_args()
r=Path(a.root)
summary=r/'run/k6-summary.json'
s=json.loads(summary.read_text()) if summary.exists() else {}
m=s.get('metrics',{})
def metric(name):
    v=m.get(name,{})
    return v.get('count',v.get('values',{}).get('count',0))
count=metric('iterations');dropped=metric('dropped_iterations')
oom=any('oom_killed=true' in f.read_text() or 'MYSQL_OOMKILLED' in f.read_text() for f in r.rglob('*') if f.is_file() and f.suffix in ('.txt','.tsv'))
k6_state=[]
k6_file=r/'run/k6-cgroup.jsonl'
if k6_file.exists():
    for line in k6_file.read_text().splitlines():
        try:k6_state.append(json.loads(json.loads(line)['state']))
        except (ValueError,KeyError):pass
if any(x.get('oom') is True for x in k6_state):oom=True
db_rows=0
for name in ('stock-summary.json','summary-v2.json'):
    f=r/'analysis'/name
    if f.exists():
        db=json.loads(f.read_text()).get('db',{})
        db_rows=db.get('coupon_rows',db.get('rows',0))
result={'k6_state_evidence':'PRESENT' if k6_state else 'MISSING','db_rows_observed':db_rows,'requested_unique_iterations':a.requested,'actual_iterations':count,'dropped_iterations':dropped,'oom_observed':oom,'generation_met':count>=a.requested and dropped==0,'scope':'generation/capability only; latency, DB integrity and client observation require independent review'}
(r/'stage-check.json').write_text(json.dumps(result,indent=2)+'\n')
print(json.dumps(result))
# Smoke must generate actual traffic and not OOM; full target generation is reported separately.
raise SystemExit(0 if (a.diagnostic or (count>0 and db_rows>0 and not oom)) else 3)
