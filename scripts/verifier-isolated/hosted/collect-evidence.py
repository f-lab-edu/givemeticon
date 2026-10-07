#!/usr/bin/env python3
"""Allowlisted, bounded synthetic evidence. Never copies raw dumps or credentials."""
import hashlib,json,sys
from pathlib import Path
ALLOWED={'.json','.txt','.tsv','.csv','.prom','.log'}
LIMIT=16*1024*1024;TOTAL=128*1024*1024

def collect(src,dst,secret):
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
    (dst/'artifact-manifest.json').write_text(json.dumps({'scope':'synthetic isolated run; omissions prohibit completeness claims','files':entries},indent=2)+'\n')
if __name__=='__main__':
    secret=Path(sys.argv[3]).read_bytes().strip() if Path(sys.argv[3]).exists() else b''
    collect(Path(sys.argv[1]),Path(sys.argv[2]),secret)
