#!/usr/bin/env python3
"""Prepare a bounded recorded-member snapshot and invoke the Verifier observer; no duplicate GET client."""
import argparse,collections,importlib.util,json,subprocess,sys,time
from pathlib import Path
HERE=Path(__file__).parent
spec=importlib.util.spec_from_file_location('ledger',HERE.parent/'compare-workload.py');ledger=importlib.util.module_from_spec(spec);spec.loader.exec_module(ledger)
def command(log,targets,event,out,mode):
    return [sys.executable,'-B',str(HERE.parent/'v1-observer.py'),'--post-log',str(log),'--targets',targets,'--event-id',str(event),'--out-dir',str(out),
            '--concurrency','4','--max-rps','20','--interval-ms','100','--timeout-ms','3000','--budget-ms','60000','--grace-ms','0','--calibration','--record-poll-windows',
            '--assumed-latency-ms','3000','--assumed-latency-source','conservative-assumption','--assumed-latency-condition',f'{mode}-GET-calibration-timeout3000ms-before-measurement']
def scope_snapshot():
    try:
        spec=importlib.util.spec_from_file_location('scope',HERE/'verify-observer-scope.py');mod=importlib.util.module_from_spec(spec);spec.loader.exec_module(mod)
        group=next(line.split(':',2)[2] for line in Path('/proc/self/cgroup').read_text().splitlines() if line.startswith('0::'))
        return dict(mod.assess(Path('/sys/fs/cgroup')/group.lstrip('/')), observed_ms=int(time.time()*1000))
    except (OSError,ValueError,StopIteration): return {'pass':False,'reason':'MISSING scope evidence','observed_ms':int(time.time()*1000)}
def scope_delta(before,after):
    keys=('usage_usec','nr_periods','nr_throttled','throttled_usec')
    try:
        if not before.get('scope_cgroup') or before['scope_cgroup']!=after.get('scope_cgroup'): raise ValueError()
        b=dict(line.split() for line in before['files']['cpu.stat'].splitlines());a=dict(line.split() for line in after['files']['cpu.stat'].splitlines())
        delta={k:int(a[k])-int(b[k]) for k in keys}
        if any(v<0 for v in delta.values()): raise ValueError()
        return {'status':'OBSERVED','cpu_stat_delta':delta,'note':'aggregate observer scope, not pure server latency; first-to-last snapshots only'}
    except (KeyError,ValueError): return {'status':'MISSING_OR_RESET','cpu_stat_delta':{k:None for k in keys}}
def reconcile(records,observer_rows,mode):
    spans=[(r['sentAtMs'],r['respondedAtMs']) for r in records if isinstance(r.get('sentAtMs'),(int,float)) and isinstance(r.get('respondedAtMs'),(int,float))]
    times=[w[0] for row in observer_rows for w in row.get('pollWindows',[])]
    overlap=sum(any(start<=t<=end for start,end in spans) for t in times)
    samples=[sample for row in observer_rows for sample in row.get('calibrationPolls',[])]
    active=[s for s in samples if any(start<=s['sentAtMs']<=end for start,end in spans)]
    def distribution(rows):
        result={}
        for key in sorted(set(str(x.get('httpStatus')) for x in rows)):
            values=sorted(x['latencyMs'] for x in rows if str(x.get('httpStatus'))==key and not x.get('error'))
            result[key]={'n':len(values),'p95':values[min(len(values)-1,round(.95*(len(values)-1)))] if values else None}
        return {'by_http_status':result,'polls':len(rows),'errors':dict(collections.Counter(x['error'] for x in rows if x.get('error')))}
    return {'mode':mode,'get_latency_active_post_only':distribution(active),'get_latency_without_active_post':distribution([s for s in samples if s not in active]),'slo_verdict':'NOT_APPLICABLE(calibration)' ,'observed_get_active_post_overlap_n':overlap,'active_post_overlap_status':'OBSERVED' if overlap else 'NOT_OBSERVED',
            'last_application_state_members':dict(collections.Counter(row.get('lastStatus') for row in observer_rows)),
            'scope':'latency calibration only; input snapshot is not complete final workload denominator. Original POST log preserved; no whole-run convergence/SLO claim.'}
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('stage');p.add_argument('targets');p.add_argument('--mode',choices=['idle','during'],default='idle');a=p.parse_args();stage=Path(a.stage)
    event=int(next(line.split('=',1)[1].split()[0] for line in (stage/'run-meta.txt').read_text().splitlines() if line.startswith('event_id=')))
    log=stage/'run/k6-failures.log';deadline=time.monotonic()+60
    while True:
        records=ledger.parse(log) if log.exists() else []
        first=[r for r in records if r.get('kind')=='admission']
        if len(first)>201:raise SystemExit('fixture exceeds declared bounds')
        if a.mode=='idle' or first or (stage/'run/k6-finished-host-ms.txt').exists() or time.monotonic()>=deadline:break
        time.sleep(.1)
    if not first:raise SystemExit('no recorded GET targets; calibration refused')
    limit=40
    members=set(r['memberId'] for r in first[:limit]);snapshot=[r for r in records if r.get('memberId') in members and r.get('kind') in ('admission','duplicate')]
    out=stage/('get-'+a.mode);out.mkdir(parents=True,exist_ok=True);input_file=out/'post-input.log'
    input_file.write_text(''.join(json.dumps(r)+'\n' for r in snapshot))
    started_ms=int(time.time()*1000)
    before=scope_snapshot();(out/'scope-before.json').write_text(json.dumps(before,indent=2)+'\n')
    r=subprocess.run(command(input_file,a.targets,event,out,a.mode))
    after=scope_snapshot();(out/'scope-after.json').write_text(json.dumps(after,indent=2)+'\n')
    summary_file=out/'observer-summary.json'
    summary=json.loads(summary_file.read_text()) if summary_file.exists() else {}
    observer_rows=[json.loads(raw) for raw in (out/'observer-records.jsonl').read_text().splitlines()] if (out/'observer-records.jsonl').exists() else []
    report=reconcile(ledger.parse(log),observer_rows,a.mode)
    report.update(observer_scope_cost=scope_delta(before,after),latency_measurement='end-to-end client observed latency',observer_exit=r.returncode,started_ms=started_ms,ended_ms=int(time.time()*1000),get_latency_ms=summary.get('get_latency_ms'),observer_process=summary.get('observer_process'),
                  condition=('idle after POST/quiescence' if a.mode=='idle' else ('GET with observed active POST samples; distribution split by actual overlap' if report['observed_get_active_post_overlap_n'] else 'idle/post-load: configured POST overlap NOT_OBSERVED')),
                  caveat='state/status-specific smoke p95 does not guarantee GET cost at required burst; process CPU/wall excludes input parsing, lifetime RSS peak is broader; shared scope evidence measures aggregate cost')
    (stage/('get-'+a.mode+'-condition.json')).write_text(json.dumps(report,indent=2)+'\n')
    if r.returncode:raise SystemExit(r.returncode)
    if not summary.get('polls',{}).get('total'):raise SystemExit(3)
    # No overlap is NOT_OBSERVED, never an under-load input; continue idle calibration.
