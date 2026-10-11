#!/usr/bin/env python3
"""Inspect allowlisted limits/labels and require live cgroup evidence before calibration."""
import json,subprocess,sys
from pathlib import Path
def check(state,cpu,memory,owner):
    return state['labels'].get('xyz.buzz.verifier.owner')==owner and state['labels'].get('xyz.buzz.verifier.managed')=='capped-harness' and state['cpu']==int(cpu*1e9) and state['memory']==memory and state['memswap']==memory and state['running'] is True
def check_generator(path,owner):
    good=[]
    for raw in path.read_text().splitlines():
        row=json.loads(raw)
        if row.get('state_status')!=0 or row.get('cgroup_status')!=0:continue
        state=json.loads(row['state']);sections={};key=None
        for line in row['cgroup'].splitlines():
            if line.startswith('[') and line.endswith(']'):key=line[1:-1];sections[key]=[]
            elif key:sections[key].append(line)
        try:
            q,period=sections['cpu.max'][0].split()
            good.append(state['cpu_nano']==750000000 and state['memory']==3072*1024**2 and state['memswap']==3072*1024**2 and q!='max' and int(q)/int(period)==.75 and int(sections['memory.max'][0])==3072*1024**2 and sections['memory.swap.max'][0]=='0')
        except (KeyError,IndexError,ValueError):continue
    return {'verified_live_limit_samples':len(good),'pass':bool(good) and all(good),'owner_required_by_recorder':owner,'scope':'missing/contradictory live generator limits refuse calibration; not a throughput PASS'}
if __name__=='__main__':
    if sys.argv[1]=='generator':
        result=check_generator(Path(sys.argv[2]),sys.argv[3]);Path(sys.argv[4]).write_text(json.dumps(result,indent=2)+'\n');sys.exit(0 if result['pass'] else 3)
    prefix,owner,out=sys.argv[1:];rows=[]
    for suffix,cpu,mb in [('app1',.55,768),('app2',.55,768),('mysql',1,1024),('redis-mail',.05,64),('redis-coupon',.05,64)]:
        name=f'{prefix}-{suffix}'
        fmt='{"labels":{{json .Config.Labels}},"cpu":{{.HostConfig.NanoCpus}},"memory":{{.HostConfig.Memory}},"memswap":{{.HostConfig.MemorySwap}},"running":{{.State.Running}}}'
        r=subprocess.run(['docker','inspect','--format',fmt,name],capture_output=True,text=True,timeout=10)
        state=json.loads(r.stdout) if r.returncode==0 else {};ok=bool(state) and check(state,cpu,mb*1024**2,owner)
        cg=subprocess.run(['docker','exec',name,'sh','-c','cat /sys/fs/cgroup/cpu.max /sys/fs/cgroup/memory.max /sys/fs/cgroup/memory.swap.max /sys/fs/cgroup/cpu.stat /sys/fs/cgroup/memory.events'],capture_output=True,text=True,timeout=10) if ok else None
        live=False
        if cg and cg.returncode==0:
            lines=cg.stdout.splitlines()
            try:q,period=lines[0].split();live=q!='max' and int(q)/int(period)==cpu and int(lines[1])==mb*1024**2 and int(lines[2])==0
            except (ValueError,IndexError):pass
        rows.append({'name':name,'inspect':state,'cgroup':cg.stdout if cg else None,'cgroup_exit':cg.returncode if cg else None,'pass':ok and live})
    Path(out).write_text(json.dumps({'containers':rows,'pass':all(r['pass'] for r in rows)},indent=2)+'\n')
    sys.exit(0 if all(r['pass'] for r in rows) else 3)
