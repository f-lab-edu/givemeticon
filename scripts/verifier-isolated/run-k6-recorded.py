#!/usr/bin/env python3
"""Run one owned k6 container without auto-remove; retain live cgroup evidence."""
import json,subprocess,sys,time
from pathlib import Path

def docker(*args):
    try:return subprocess.run(['docker',*args],capture_output=True,text=True,timeout=10)
    except (subprocess.TimeoutExpired,OSError) as e:return subprocess.CompletedProcess(['docker',*args],124,'',type(e).__name__)

def owned(name,owner):
    p=docker('inspect','--format','{{json .Config.Labels}}',name)
    if p.returncode: return False
    try: labels=json.loads(p.stdout)
    except ValueError:return False
    if not isinstance(labels,dict):return False
    return labels.get('xyz.buzz.verifier.owner')==owner and labels.get('xyz.buzz.verifier.managed')=='capped-harness'

def capture(name,owner,stream):
    if not owned(name,owner):return False
    state=docker('inspect','--format','{"started_at":{{json .State.StartedAt}},"finished_at":{{json .State.FinishedAt}},"running":{{.State.Running}},"exit":{{.State.ExitCode}},"oom":{{.State.OOMKilled}},"cpu_nano":{{.HostConfig.NanoCpus}},"memory":{{.HostConfig.Memory}},"memswap":{{.HostConfig.MemorySwap}}}',name)
    record={'host_ms':int(time.time()*1000),'state':state.stdout.strip(),'state_status':state.returncode}
    cg=docker('exec',name,'sh','-c','for f in cpu.max memory.max memory.swap.max cpu.stat memory.current memory.peak memory.events; do echo "[$f]"; cat "/sys/fs/cgroup/$f" || exit 1; done')
    record.update(cgroup=cg.stdout,cgroup_status=cg.returncode)
    # Missing live exec after fast exit is explicitly recorded; not interpreted as zero.
    stream.write(json.dumps(record)+'\n');stream.flush();return True

def execute(name,owner,out,args):
    if not owner:return 2
    existing=docker('container','ls','-a','--filter',f'name=^/{name}$','--format','{{.ID}}')
    if existing.returncode or existing.stdout.strip():return 2
    Path(out).parent.mkdir(parents=True,exist_ok=True)
    with open(out,'w') as stream:
        p=subprocess.Popen(['docker','run','--name',name,*args])
        try:
            while p.poll() is None:
                capture(name,owner,stream)
                time.sleep(0.5)
            status=p.wait()
            capture(name,owner,stream)
            return status if status>=0 else 128-status
        finally:
            if p.poll() is None:
                p.terminate()
                try:p.wait(timeout=10)
                except subprocess.TimeoutExpired:print('k6 docker client termination timed out',file=sys.stderr)
            if owned(name,owner):
                state=docker('inspect','--format','{{.State.Running}}',name)
                if state.stdout.strip()=='true':docker('stop',name)
                capture(name,owner,stream)
                state=docker('inspect','--format','{{.State.Running}}',name)
                if state.returncode==0 and state.stdout.strip()=='false':
                    removed=docker('rm',name)
                    if removed.returncode:print('k6 owned remove failed',file=sys.stderr)
                else:print('k6 not stopped; removal refused',file=sys.stderr)
            else:print('k6 ownership unavailable/mismatched; cleanup refused',file=sys.stderr)

if __name__=='__main__':sys.exit(execute(sys.argv[1],sys.argv[2],sys.argv[3],sys.argv[4:]))
