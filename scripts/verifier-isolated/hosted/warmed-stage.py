#!/usr/bin/env python3
"""Actual iteration ledger and conservative per-path warmup drain evidence."""
import importlib.util,json,re,sys,math
from pathlib import Path
HERE=Path(__file__).parent
s=importlib.util.spec_from_file_location('gate',HERE/'final-generation-gate.py');gate=importlib.util.module_from_spec(s);s.loader.exec_module(gate)

def occupancy(records,dropped):
    started={};finished={};invalid=0;timeline=[]
    for r in records:
        if r.get('kind') not in ('iteration_started','iteration_completed'):continue
        key=r.get('iterationKey');t=r.get('atMs');table=started if r['kind']=='iteration_started' else finished
        if not isinstance(key,str) or not isinstance(t,(int,float)) or key in table:invalid+=1;continue
        table[key]=t;timeline.append((t,1 if table is started else -1))
        if table is finished and r.get('completedNormally') is not True:invalid+=1
    invalid+=sum(k not in started or t<started.get(k,t) for k,t in finished.items())
    q=0;peak=0
    for _,d in sorted(timeline,key=lambda x:(x[0],-x[1])):q+=d;peak=max(q,peak)
    return {'started_iterations':len(started),'completed_iterations':len(finished),'unfinished_iteration_keys':sorted(set(started)-set(finished)),
            'actual_occupancy_peak':peak,'actual_occupancy_definition':'started - completed; client-side iteration ledger, not server in-flight',
            'scheduled_demand_backlog_at_end':len(started)-len(finished)+dropped,'unsent_scheduled_iterations':dropped,
            'scheduled_backlog_definition':'scheduled - completed includes dropped/unstarted demand; never actual VU occupancy',
            'invalid_records':invalid,'pass':bool(started) and invalid==0 and set(started)==set(finished)}

def http_series(file,uri):
    spec=importlib.util.spec_from_file_location('pb',HERE.parent/'prom_baseline.py');pb=importlib.util.module_from_spec(spec);spec.loader.exec_module(pb)
    document=pb.read_prom(file);values={};starts=[];invalid=document is None or document['bad_lines']>0
    for name,labels,value in (document or {}).get('rows',[]):
        if name=='process_start_time_seconds':starts.append(value)
        if name=='http_server_requests_seconds_count' and labels.get('method')=='POST' and labels.get('uri')==uri:
            key=tuple(sorted(labels.items()))
            if key in values or not math.isfinite(value) or value<0:invalid=True
            values[key]=value
    return values,starts,invalid

def http_counts(file,uri):
    values,starts,invalid=http_series(file,uri)
    return sum(values.values()) if values and not invalid else None,starts

def direct_http_delta(before,after):
    new=set(after)-set(before);gone=set(before)-set(after)
    decreased={key for key in set(before)&set(after) if after[key]<before[key]}
    complete=bool(before) and bool(after) and not (new or gone or decreased)
    show=lambda keys:[dict(key) for key in sorted(keys)]
    return {'pass':complete,'baseline_status':'RESET' if decreased else 'MISSING_SERIES' if gone else 'MISSING_NEW_SERIES' if new else 'OK_DIRECT' if complete else 'MISSING',
            'new_series':show(new),'missing_after_series':show(gone),'decreased_series':show(decreased),
            'series_deltas':[{'labels':dict(key),'before':before[key],'after':after[key],'delta':after[key]-before[key]} for key in sorted(before) if key in after],
            'delta':sum(after[key]-before[key] for key in before) if complete else None,
            'scope':'all POST/URI labels must match exactly, including status/outcome/exception; no absent series receives zero'}

def drain(root,path,records,ledger,warmup):
    result={'client_iterations_finished':ledger['pass'],'generator_finished':'see retained generator exit/state',
            'server_residual_processing':'UNKNOWN','note':'PENDING=0 alone never proves stock drain; client timeout does not cancel server processing'}
    if path=='stock':
        # Direct cumulative handler completion values after FIRST workload on fresh apps.
        # No missing-before series is converted to a metric zero or timer baseline.
        sent={app:sum(r.get('kind')=='stock_request' and r.get('target')==app for r in records) for app in (1,2)}
        checks=[]
        for app in (1,2):
            before,bst=http_counts(root/f'run/app{app}-before.prom','/internal/loadtest/coupons')
            after,ast=http_counts(root/f'run/app{app}-after.prom','/internal/loadtest/coupons')
            try:scrapes_complete=all((root/f'run/app{app}-{when}.prom.exit.txt').read_text().strip()=='0' for when in ('before','after'))
            except OSError:scrapes_complete=False
            spec=importlib.util.spec_from_file_location('pb',HERE.parent/'prom_baseline.py');pb=importlib.util.module_from_spec(spec);spec.loader.exec_module(pb)
            bp=root/f'run/app{app}-before.prom';ap=root/f'run/app{app}-after.prom'
            prov=pb.read_provenance(root/f'run/app{app}-scrape-provenance.json')
            ev=pb.scrape_evidence(pb.read_prom(bp),pb.read_prom(ap),prov,(bp,ap))
            bseries,_,binvalid=http_series(bp,'/internal/loadtest/coupons')
            aseries,_,ainvalid=http_series(ap,'/internal/loadtest/coupons')
            series=direct_http_delta(bseries,aseries)
            direct=scrapes_complete and not binvalid and not ainvalid and series['pass']
            delta=series['delta'] if direct else None
            # HTTP absent-before is never inferred in this execution gate.
            evidence_ok=ev['structural_ok'] and ev['provenance_ok']
            checks.append({'app':app,'sent':sent[app],'before_observed':before,'after_observed':after,'delta':delta,
                           'baseline_status':'OK_DIRECT' if direct else series['baseline_status'] if not series['pass'] else 'MISSING_CAPTURE',
                           'baseline_reason':'exact full-label series baselines required; new/missing/decreased series refuses transition',
                           'series_baseline':series,
                           'inferred_zero_baseline':False,'scrape_evidence':ev,
                           'successful_capture_evidence':evidence_ok,'same_process':len(bst)==1 and bst==ast,
                           'completion_reconciled':evidence_ok and direct and len(bst)==1 and bst==ast and delta==sent[app]})
        result['http_completion']=checks
        # First cold baseline can legitimately be missing; do not infer it here.
        result['server_residual_processing']='RECONCILED_HTTP_HANDLERS' if all(c['completion_reconciled'] for c in checks) else 'UNKNOWN'
        result['warmup_transition_allowed']=all(c['completion_reconciled'] for c in checks) and ledger['pass']
        result['cold_meter_dependency']='Direct HTTP baseline only; registration fixture separate; absent baseline refuses transition; no HTTP/manual inference.'
    else:
        try:pending=int((root/'run/unresolved-after-quiescence.txt').read_text().strip())
        except (OSError,ValueError):pending=None
        result['pending_checking']=pending;result['worker_drain']='WORKLOAD_DB_TERMINAL' if pending==0 else 'UNKNOWN'
        result['warmup_transition_allowed']=pending==0 and ledger['pass']
        result['worker_scope']='This fixture only; DB terminal after client iterations, not proof of process-global worker idleness/commit timestamps'
    return result

def db_reconciliation(root,path):
    try:
        data=json.loads((root/'analysis'/('stock-summary.json' if path=='stock' else 'summary-v2.json')).read_text())
        db=data['db'];integrity=data['integrity']['verdict']=='OK'
        if path=='stock':
            ok=integrity and db['duplicate_users']==0 and db['over_issued']==0 and data['success_response_but_not_in_db']==0
        else:
            ok=integrity and db['db_unresolved(PENDING/CHECKING)']==0 and db['db_reconciliation_unconfirmed_members(DB-reconciliation basis ONLY; client-side confirmation not assessed)']==0
            for file in ('duplicate-award-by-member.txt','duplicate-award-by-application.txt'):
                ok=ok and int((root/'run'/file).read_text())==0
            ok=ok and not (root/'run/tier-mismatch.tsv').read_text().strip()
        return {'pass':ok,'integrity':data['integrity'],'db':db,'scope':'client/DB separately reconciled; no client-observation SLO claim'}
    except (OSError,ValueError,KeyError):return {'pass':False,'reason':'MISSING_OR_INVALID_DB_RECONCILIATION'}

def assess(root,path,requested,warmup=False):
    check=__import__('subprocess').run([sys.executable,str(HERE/'check-stage.py'),str(root),str(requested),'--diagnostic'],capture_output=True,text=True)
    code,generation=gate.evaluate([root]);r=generation['stages'][0]
    resource=r.get('generator_resource_observation',{})
    limits_spec=importlib.util.spec_from_file_location('caps',HERE/'verify-calibration-caps.py');caps=importlib.util.module_from_spec(limits_spec);limits_spec.loader.exec_module(caps)
    limits=caps.check_generator(root/'run/k6-cgroup.jsonl',__import__('os').environ.get('VERIFIER_OWNER_ID',''))
    generator_ready=limits['pass'] and resource.get('live_samples',0)>=2 and resource.get('oom_state') is False and all(resource.get('memory_events_delta',{}).get(k)==0 for k in ('oom','oom_kill'))
    records=gate.workload.parse(root/'run/k6-failures.log');ledger=occupancy(records,r.get('dropped',0));d=drain(root,path,records,ledger,warmup)
    # Fresh inventory1000 exceeds planned first users201/101; no CLOSED bypass allowed.
    ended=sum(x.get('category')=='event_closed' for x in records)
    db=db_reconciliation(root,path)
    accepted=check.returncode==0 and code==0 and generator_ready and ledger['pass'] and ended==0 and db['pass'] and (not warmup or d['warmup_transition_allowed'])
    result={'pass':accepted,'warmup':warmup,'generation':generation,'live_generator_ready':generator_ready,'generator_limits':limits,'iteration_ledger':ledger,'drain':d,'db_reconciliation':db,'ended_responses':ended,
            'scope':'limited diagnosis; no mandatory baseline/SLO/comparison PASS; unresolved drain stops transition'}
    (root/'warmed-stage.json').write_text(json.dumps(result,indent=2)+'\n');return 0 if accepted else 3
if __name__=='__main__':sys.exit(assess(Path(sys.argv[1]),sys.argv[2],int(sys.argv[3]),'--warmup' in sys.argv))
