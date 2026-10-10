#!/usr/bin/env python3
"""Prepare a bounded recorded-member snapshot and invoke the Verifier observer; no duplicate GET client."""
import argparse,collections,importlib.util,json,subprocess,sys,time
from pathlib import Path
HERE=Path(__file__).parent
spec=importlib.util.spec_from_file_location('ledger',HERE.parent/'compare-workload.py');ledger=importlib.util.module_from_spec(spec);spec.loader.exec_module(ledger)
def command(log,targets,event,out,mode):
    return [sys.executable,'-B',str(HERE.parent/'v1-observer.py'),'--post-log',str(log),'--targets',targets,'--event-id',str(event),'--out-dir',str(out),
            '--concurrency','4','--max-rps','20','--interval-ms','100','--timeout-ms','3000','--budget-ms','180000','--grace-ms','0','--require-budget','--record-poll-windows',
            '--assumed-latency-ms','3000','--assumed-latency-source','conservative-assumption','--assumed-latency-condition',f'{mode}-GET-calibration-timeout3000ms-before-measurement']
def reconcile(records,observer_rows,mode):
    spans=[(r['sentAtMs'],r['respondedAtMs']) for r in records if isinstance(r.get('sentAtMs'),(int,float)) and isinstance(r.get('respondedAtMs'),(int,float))]
    times=[w[0] for row in observer_rows for w in row.get('pollWindows',[])]
    overlap=sum(any(start<=t<=end for start,end in spans) for t in times)
    return {'mode':mode,'observed_get_active_post_overlap_n':overlap,'active_post_overlap_status':'OBSERVED' if overlap else 'NOT_OBSERVED',
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
    limit=40 if a.mode=='during' else 201
    members=set(r['memberId'] for r in first[:limit]);snapshot=[r for r in records if r.get('memberId') in members and r.get('kind') in ('admission','duplicate')]
    out=stage/('get-'+a.mode);out.mkdir(parents=True,exist_ok=True);input_file=out/'post-input.log'
    input_file.write_text(''.join(json.dumps(r)+'\n' for r in snapshot))
    started_ms=int(time.time()*1000)
    r=subprocess.run(command(input_file,a.targets,event,out,a.mode))
    summary_file=out/'observer-summary.json'
    summary=json.loads(summary_file.read_text()) if summary_file.exists() else {}
    observer_rows=[json.loads(raw) for raw in (out/'observer-records.jsonl').read_text().splitlines()] if (out/'observer-records.jsonl').exists() else []
    report=reconcile(ledger.parse(log),observer_rows,a.mode)
    report.update(observer_exit=r.returncode,started_ms=started_ms,ended_ms=int(time.time()*1000),get_latency_ms=summary.get('get_latency_ms'),observer_process=summary.get('observer_process'),
                  condition='idle after POST/quiescence' if a.mode=='idle' else 'during configured100rps2s POST; actual overlap separately verified',
                  caveat='state/status-specific smoke p95 does not guarantee GET cost at required burst; process CPU/wall excludes input parsing, lifetime RSS peak is broader; shared scope evidence measures aggregate cost')
    (stage/('get-'+a.mode+'-condition.json')).write_text(json.dumps(report,indent=2)+'\n')
    if r.returncode:raise SystemExit(r.returncode)
    if not summary.get('polls',{}).get('total'):raise SystemExit(3)
    if a.mode=='during' and not report['observed_get_active_post_overlap_n']:raise SystemExit(4)
