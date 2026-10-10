#!/usr/bin/env python3
"""One bounded GET sample per recorded V1 member; no 180-second SLO claim."""
import argparse,concurrent.futures,http.client,importlib.util,json,time,urllib.parse,threading,resource
from pathlib import Path
spec=importlib.util.spec_from_file_location('ledger',Path(__file__).parents[1]/'compare-workload.py');ledger=importlib.util.module_from_spec(spec);spec.loader.exec_module(ledger)
rate_lock=threading.Lock();next_request=0.0
def sample(target,event,member):
    global next_request
    with rate_lock:
        slot=max(time.monotonic(),next_request);next_request=slot+.05
    delay=slot-time.monotonic()
    if delay>0:time.sleep(delay)
    u=urllib.parse.urlparse(target);c=http.client.HTTPConnection(u.hostname,u.port,timeout=3)
    start=time.time_ns();status=None;state=None;error=None
    try:
        c.request('GET',f'/test-support/coupon-events/{event}/applications/me',headers={'X-Coupon-Admission-Test-Member':str(member)})
        r=c.getresponse();status=r.status;data=r.read()
        if status==200:state=(json.loads(data).get('data') or {}).get('status')
    except Exception as e:error=type(e).__name__
    finally:c.close()
    return {'member_id':member,'target':target,'sent_ms':start//1000000,'responded_ms':time.time_ns()//1000000,'latency_ms':(time.time_ns()-start)/1e6,'http_status':status,'application_status':state,'error':error}
def summarize(rows):
    groups={}
    for r in rows:groups.setdefault(f"{r['target']}|{r['http_status']}|{r['application_status']}|{r['error']}",[]).append(r['latency_ms'])
    def p95(v):s=sorted(v);return s[min(len(s)-1,int(.95*(len(s)-1)))]
    return {'sample_n':len(rows),'by_target_and_status':{k:{'n':len(v),'p95_ms':p95(v)} for k,v in groups.items()},
            'condition':'after POST completion and DB-quiescence, one GET/member, max20 requests/s, concurrency4, timeout3000ms, shared observer0.25CPU/512MiB; low-load feasibility only',
            'usable_get_success_n':sum(r['http_status']==200 and r['error'] is None and r['application_status'] in ('ISSUED','SOLD_OUT','PENDING','CHECKING','ENDED') for r in rows),
            'note':'state-dependent low-load p95 does not guarantee GET latency under burst; raw errors and missing states retained'}
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('stage');p.add_argument('targets');p.add_argument('--mode',choices=['idle','during'],default='idle');a=p.parse_args();stage=Path(a.stage)
    meta=dict(line.split('=',1) for line in (stage/'run-meta.txt').read_text().splitlines() if line.startswith('event_id='))
    event=int(meta['event_id'].split()[0]);targets=a.targets.split(',')
    records=[r for r in ledger.parse(stage/'run/k6-failures.log') if r.get('kind')=='admission'] if (stage/'run/k6-failures.log').exists() else []
    if a.mode=='idle' and (not records or len(records)>201):raise SystemExit('unexpected calibration fixture size; GET smoke refused')
    seen=set();tasks=[];rows=[];started=time.time();cpu_start=time.process_time()
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        deadline=time.monotonic()+60
        while True:
            if a.mode=='during':
                log=stage/'run/k6-failures.log'
                records=[r for r in ledger.parse(log) if r.get('kind')=='admission'] if log.exists() else []
            if len(records)>201:raise SystemExit('fixture exceeds declared bounds')
            for r in records:
                if r['memberId'] in seen:continue
                if a.mode=='during' and len(tasks)>=40:break
                seen.add(r['memberId']);tasks.append(pool.submit(sample,targets[(r['target']-1)%len(targets)],event,r['memberId']))
            if a.mode=='idle' or len(tasks)>=40 or (stage/'run/k6-finished-host-ms.txt').exists() or time.monotonic()>=deadline:break
            time.sleep(.1)
        rows=[t.result() for t in tasks]
    prefix='get-smoke-'+a.mode
    (stage/(prefix+'.jsonl')).write_text(''.join(json.dumps(r)+'\n' for r in rows))
    report=summarize(rows);report['mode']=a.mode;report['condition']=report['condition'].replace('after POST completion and DB-quiescence','after POST completion and DB-quiescence' if a.mode=='idle' else 'concurrent with POST generator; target comes from completed POST records; actual overlap separately counted');report.update(started_ms=int(started*1000),ended_ms=int(time.time()*1000),process_cpu_seconds=time.process_time()-cpu_start,max_rss_kib=resource.getrusage(resource.RUSAGE_SELF).ru_maxrss,actual_sent_span_ms=max(r['sent_ms'] for r in rows)-min(r['sent_ms'] for r in rows) if rows else None)
    def stamp(name):
        try:return int((stage/'run'/name).read_text())
        except (OSError,ValueError):return None
    begin=stamp('k6-started-host-ms.txt');end=stamp('k6-finished-host-ms.txt')
    report.update(post_generator_start_ms=begin,post_generator_end_ms=end,
                  observed_get_post_overlap_n=sum(begin<=r['sent_ms']<=end for r in rows) if begin is not None and end is not None else None,
                  overlap_note='generator process window includes initialization/graceful tail; also reconcile with actual first POST sent/response timestamps before claiming active POST overlap')
    completed_post=ledger.parse(stage/'run/k6-failures.log')
    post_intervals=[(r.get('sentAtMs'),r.get('respondedAtMs')) for r in completed_post if r.get('kind') in ('admission','duplicate') and isinstance(r.get('sentAtMs'),(int,float)) and isinstance(r.get('respondedAtMs'),(int,float))]
    actual_overlap=sum(any(begin<=r['sent_ms']<=end for begin,end in post_intervals) for r in rows)
    report.update(observed_get_active_post_overlap_n=actual_overlap,active_post_overlap_status='OBSERVED' if actual_overlap else 'NOT_OBSERVED',
                  expected_low_post_rate=100,expected_low_post_duration_s=2,
                  note='no active POST overlap means concurrent condition unverified, not measured low-load latency guarantee')
    (stage/(prefix+'-summary.json')).write_text(json.dumps(report,indent=2)+'\n')
    raise SystemExit(3 if not report['usable_get_success_n'] else 4 if a.mode=='during' and not actual_overlap else 0)
