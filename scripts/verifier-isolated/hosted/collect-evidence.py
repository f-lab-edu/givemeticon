#!/usr/bin/env python3
"""Allowlisted, bounded synthetic evidence. Never copies raw dumps or credentials."""
import hashlib,importlib.util,json,sys
from pathlib import Path
ALLOWED={'.json','.jsonl','.txt','.tsv','.csv','.prom','.log'}
LIMIT=16*1024*1024;TOTAL=128*1024*1024
_spec=importlib.util.spec_from_file_location('policy_failure',Path(__file__).with_name('policy-failure.py'))
failure=importlib.util.module_from_spec(_spec);_spec.loader.exec_module(failure)

def collect_policy_failures(src,dst):
    for path in ('stock','v1'):
        for app in ('app1','app2'):
            rel=Path(path+'-policy')/(app+'.failure.json');p=src/rel
            if p.is_symlink() or p.parent.is_symlink() or not p.is_file():continue
            if p.stat().st_size>4096:raise ValueError('invalid diagnostic')
            record=failure.validate(json.loads(p.read_text()))
            if record['path']!=path or record['app']!=app:raise ValueError('invalid diagnostic')
            target=dst/rel;target.parent.mkdir(parents=True,exist_ok=True)
            target.write_text(json.dumps(record,sort_keys=True)+'\n')

def collect(src,dst,secret):
    if not secret: raise ValueError('redaction input missing or empty; evidence destination unchanged')
    if not src.is_dir(): raise ValueError('evidence source missing; destination unchanged')
    dst.mkdir(parents=True,exist_ok=True); entries=[];total=0
    for p in sorted(src.rglob('*')):
        if not p.is_file() or p.is_symlink() or p.suffix not in ALLOWED or 'dump' in p.name: continue
        # k6's unbounded time-series is excluded; per-request ledger + summary are kept.
        if p.name=='k6.json':continue
        rel=p.relative_to(src);size=p.stat().st_size
        if size>LIMIT or total+size>TOTAL:
            entries.append({'path':str(rel),'omitted':'size-budget','bytes':size});continue
        data=p.read_bytes()
        if secret: data=data.replace(secret,b'[REDACTED]')
        target=dst/rel;target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(data)
        total+=len(data);entries.append({'path':str(rel),'bytes':len(data),'sha256':hashlib.sha256(data).hexdigest()})
    (dst/'artifact-manifest.json').write_text(json.dumps({'scope':'synthetic isolated run; omissions prohibit completeness claims','redaction_applied':True,'files':entries},indent=2)+'\n')
if __name__=='__main__':
    try:
        collect_policy_failures(Path(sys.argv[1]),Path(sys.argv[2]))
        secret=Path(sys.argv[3]).read_bytes().strip()
        collect(Path(sys.argv[1]),Path(sys.argv[2]),secret)
    except (OSError,ValueError):
        print('evidence collection refused: redaction input unavailable or collection failed',file=sys.stderr)
        sys.exit(3)
