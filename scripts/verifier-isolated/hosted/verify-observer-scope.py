#!/usr/bin/env python3
"""Refuse runtime unless this controller and inherited observers are in the capped scope."""
import json,sys
from pathlib import Path

def assess(root):
    files={f:(root/f).read_text().strip() for f in ('cpu.max','memory.max','memory.swap.max','cpu.stat','memory.current','memory.peak','memory.events')}
    quota,period=files['cpu.max'].split()
    ok=quota!='max' and int(quota)/int(period)==.25 and files['memory.max']=='536870912' and files['memory.swap.max']=='0'
    return {'scope_cgroup':str(root),'files':files,'pass':ok,'limits':{'cpu':.25,'memory_bytes':536870912,'swap_bytes':0},
            'boundary':'host observer/controller descendants; queries and docker exec work inside owned service/generator cgroups consume those budgets; daemon/OS overhead consumes host reserve'}
if __name__=='__main__':
    group=next(line.split(':',2)[2] for line in Path('/proc/self/cgroup').read_text().splitlines() if line.startswith('0::'))
    root=Path('/sys/fs/cgroup')/group.lstrip('/')
    try:result=assess(root)
    except (OSError,ValueError):result={'pass':False,'reason':'scope evidence missing or unsupported cgroup'}
    Path(sys.argv[1]).write_text(json.dumps(result,indent=2)+'\n');sys.exit(0 if result['pass'] else 3)
