#!/usr/bin/env python3
"""Five fixed post-warmup samples: same targets/JVMs, idle pool and fixture drain."""
import argparse,importlib.util,json,math,os,re,subprocess,time
from pathlib import Path
spec=importlib.util.spec_from_file_location("capture_prom",Path(__file__).with_name("capture-prom.py"));CP=importlib.util.module_from_spec(spec);spec.loader.exec_module(CP)

def series(text,name):
    values=[]
    for line in text.splitlines():
        if line.startswith('#') or not line.strip():continue
        m=re.fullmatch(r'([a-zA-Z_:][a-zA-Z0-9_:]*)(?:\{((?:[^"}]|"(?:\\.|[^"\\])*")*)\})?\s+([^\s]+)',line)
        if not m:raise ValueError('malformed Prometheus body')
        if m[1]!=name:continue
        value=float(m[3]);labels=dict(re.findall(r'(\w+)="([^"]*)"',m[2] or ''))
        if not math.isfinite(value):raise ValueError('nonfinite required gauge')
        values.append((labels,value))
    return values

def metrics(text):
    starts=series(text,'process_start_time_seconds')
    active=series(text,'hikaricp_connections_active');pending=series(text,'hikaricp_connections_pending')
    if len(starts)!=1 or starts[0][1]<=0:raise ValueError('missing/ambiguous process start')
    for rows in (active,pending):
        if len(rows)!=1 or rows[0][0].get('pool')!='HikariPool-1' or rows[0][1]<0:raise ValueError('missing/ambiguous pool gauge')
    return {'process_start_time_seconds':starts[0][1],'active':active[0][1],'pending':pending[0][1],'pool':'HikariPool-1'}

def assess(samples,baseline,path,expected_rows):
    reasons=[];previous_db=None
    if len(samples)!=5:reasons.append('MISSING_SAMPLE')
    for sample in samples:
        apps=sample.get('apps',[])
        if len(apps)!=2:reasons.append('MISSING_TARGET')
        for i,row in enumerate(apps,1):
            target=f'http://127.0.0.1:{18079+i}/actuator/prometheus'
            if row.get('target')!=target or row.get('effective_target')!=target or row.get('scrape_exit')!=0:reasons.append('TARGET_OR_SCRAPE_MISMATCH')
            try:
                m=metrics(row['body']);b=baseline[i-1]
                if m['process_start_time_seconds']!=b['process_start_time_seconds']:reasons.append('PROCESS_RESTART')
                if m['active']!=0 or m['pending']!=0:reasons.append('POOL_NOT_IDLE')
            except (ValueError,KeyError,IndexError,TypeError):reasons.append('MISSING_OR_INVALID_PROMETHEUS')
        db=sample.get('db')
        if sample.get('db_exit')!=0 or not isinstance(db,list) or len(db)!= (5 if path=='stock' else 6) or any(type(v)!=int or v<0 for v in db):
            reasons.append('MISSING_OR_INVALID_FIXTURE_DRAIN');continue
        if path=='stock':
            total,remain,coupons,duplicates,pending=db
            ok=total==1000 and remain+coupons==total and coupons==expected_rows and duplicates==pending==0
        else:
            total,issued,applications,pending,awards,duplicates=db
            ok=total==1000 and issued==applications==awards==expected_rows and pending==duplicates==0
        if not ok:reasons.append('FIXTURE_NOT_DRAINED')
        if previous_db is not None and db!=previous_db:reasons.append('FIXTURE_CHANGED_DURING_STABILIZATION')
        previous_db=db
    return {'pass':not reasons,'reasons':sorted(set(reasons)),'sample_count':len(samples),'pool_condition':'active=0,pending=0 at all5 samples; fixed policy, no retry/extension',
            'fixture_condition':'same fixture expected DB rows and integrity across5 samples; stock additionally requires previously reconciled HTTP handlers, not PENDING alone',
            'scope':'sampled warmup readiness only; not proof of global background inactivity, performance or SLO'}

def execute(root,path,out):
    if os.environ.get('GITHUB_ACTIONS')!='true' or os.environ.get('RUNNER_OS')!='Linux':return 2
    out.mkdir(parents=True,exist_ok=True);samples=[]
    try:
        prior=json.loads((root/'warmed-stage.json').read_text())
        if prior['pass'] is not True or prior['warmup'] is not True or prior['drain']['warmup_transition_allowed'] is not True:raise ValueError('warmup not ready')
        expected_rows=prior['db_reconciliation']['db']['coupon_rows' if path=='stock' else 'rows']
        baseline=[metrics((root/f'run/app{i}-after.prom').read_text()) for i in (1,2)]
        meta=(root/'run-meta.txt').read_text();key='stock_id' if path=='stock' else 'event_id';fixture=int(re.search(rf'\b{key}=(\d+)\b',meta)[1])
        dbname=os.environ['DB_NAME'];prefix=os.environ['VERIFIER_PREFIX']
        if not re.fullmatch(r'givemeticon_coupon_admission_loadtest_177_w_(stock|v1)',dbname) or fixture<=0:raise ValueError('fixture identity refused')
        sql=(f'SELECT total,remain,(SELECT COUNT(*) FROM coupon WHERE stock_id={fixture}),(SELECT COUNT(*) FROM (SELECT user_id FROM coupon WHERE stock_id={fixture} GROUP BY user_id HAVING COUNT(*)>1) d),(SELECT COUNT(*) FROM coupon_issue_request WHERE stock_id={fixture} AND status=\'PENDING\') FROM coupon_stock WHERE id={fixture}' if path=='stock' else
             f'SELECT total_quantity,issued_quantity,(SELECT COUNT(*) FROM coupon_application WHERE event_id={fixture}),(SELECT COUNT(*) FROM coupon_application WHERE event_id={fixture} AND status IN (\'PENDING\',\'CHECKING\')),(SELECT COUNT(*) FROM coupon_award WHERE event_id={fixture}),(SELECT COUNT(*) FROM (SELECT member_id FROM coupon_award WHERE event_id={fixture} GROUP BY member_id HAVING COUNT(*)>1) d) FROM coupon_event WHERE id={fixture}')
        for tick in range(1,6):
            row={'host_ms':int(time.time()*1000),'fixture_id':fixture,'path':path,'apps':[]}
            for app in (1,2):
                target=f'http://127.0.0.1:{18079+app}/actuator/prometheus';file=out/f'app{app}-{tick}.prom'
                provenance=CP.capture(target,file)
                row['apps'].append({'target':target,'effective_target':provenance['effective_target'],'scrape_exit':provenance['curl_exit'],'capture':provenance,'body':file.read_text() if file.exists() else ''})
            p=subprocess.run(['docker','exec',f'{prefix}-mysql','sh','-c','MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --batch --skip-column-names "$1" -e "$2"','sh',dbname,sql],capture_output=True,text=True,timeout=10)
            (out/f'fixture-{tick}.tsv').write_text(p.stdout);row['db_exit']=p.returncode
            try:row['db']=[int(x) for x in p.stdout.strip().split('\t')]
            except ValueError:row['db']=None
            samples.append(row);(out/'samples.json').write_text(json.dumps(samples,indent=2)+'\n')
            time.sleep(1)
        result=assess(samples,baseline,path,expected_rows)
    except (OSError,ValueError,KeyError,TypeError,IndexError,subprocess.TimeoutExpired):result={'pass':False,'reasons':['MISSING_OR_INVALID_STABILIZATION_INPUT'],'sample_count':len(samples)}
    (out/'stabilization.json').write_text(json.dumps(result,indent=2)+'\n');return 0 if result['pass'] else 3
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('root',type=Path);p.add_argument('path',choices=['stock','v1']);p.add_argument('out',type=Path);a=p.parse_args();raise SystemExit(execute(a.root,a.path,a.out))
