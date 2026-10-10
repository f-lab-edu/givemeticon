#!/usr/bin/env python3
"""Target achievement and independent observations; no automatic causal verdict."""
import importlib.util,json,sys
from pathlib import Path
spec=importlib.util.spec_from_file_location('workload',Path(__file__).parents[1]/'compare-workload.py');workload=importlib.util.module_from_spec(spec);spec.loader.exec_module(workload)

def generator_evidence(root):
    rows=[];bad=0
    path=root/'run/k6-cgroup.jsonl'
    if path.exists():
        for line in path.read_text().splitlines():
            try: rows.append(json.loads(line))
            except ValueError:bad+=1
    states=[];stats=[];peaks=[];events=[]
    for row in rows:
        if row.get('state_status')==0:
            try:states.append(json.loads(row['state']))
            except (ValueError,KeyError):bad+=1
        if row.get('cgroup_status')!=0:continue
        sections={};section=None
        for line in row.get('cgroup','').splitlines():
            if line.startswith('[') and line.endswith(']'):section=line[1:-1];sections[section]=[]
            elif section:sections[section].append(line)
        def counters(name):
            try:return {parts[0]:int(parts[1]) for parts in (line.split() for line in sections.get(name,[])) if len(parts)==2}
            except ValueError:return {}
        cpu=counters('cpu.stat');mem=counters('memory.events')
        if cpu:stats.append(cpu)
        if mem:events.append(mem)
        try:peaks.append(int(''.join(sections['memory.peak'])))
        except (KeyError,ValueError):pass
    def delta(samples,key):
        values=[s[key] for s in samples if key in s]
        return values[-1]-values[0] if len(values)>=2 and values[-1]>=values[0] else None
    caps=[s.get('memory') for s in states if isinstance(s.get('memory'),int) and s['memory']>0]
    peak=max(peaks) if peaks else None;cap=caps[-1] if caps else None
    summary_path=root/'run/k6-summary.json'
    summary=json.loads(summary_path.read_text()) if summary_path.exists() else {}
    def maximum(name):
        metric=summary.get('metrics',{}).get(name,{})
        return metric.get('max',metric.get('values',{}).get('max'))
    return {'sample_rows':len(rows),'parse_errors':bad,'live_samples':len(stats),
            'oom_state':any(s.get('oom') is True for s in states) if states else None,
            'nr_throttled_delta':delta(stats,'nr_throttled'),'nr_periods_delta':delta(stats,'nr_periods'),
            'throttled_usec_delta':delta(stats,'throttled_usec'),'usage_usec_delta':delta(stats,'usage_usec'),
            'memory_peak_bytes':peak,'memory_cap_bytes':cap,'memory_peak_cap_ratio':peak/cap if peak is not None and cap else None,
            'memory_events_delta':{key:delta(events,key) for key in ('high','max','oom','oom_kill')},
            'vus_observed_max':maximum('vus'),'vus_configured_max':maximum('vus_max'),
            'note':'null is MISSING. Sampling covers only observed intervals; throttling/pressure/VU ceiling do not establish causality or invalidate the entire run.'}

def assess(root):
    stage=json.loads((root/'stage-check.json').read_text());records=workload.parse(root/'run/k6-failures.log')
    first=[r for r in records if r.get('kind')=='admission' or (r.get('kind')=='stock_request' and r.get('attempt',1)==1)]
    duplicates=[r for r in records if r.get('kind')=='duplicate' or (r.get('kind')=='stock_request' and r.get('attempt',1)>1)]
    known=bool(first) and all(isinstance(r.get('inWindow'),bool) for r in first)
    inside=sum(r.get('inWindow') is True for r in first)
    reason='GENERATION_MET'
    if not known or len(first)!=stage['actual_iterations']:reason='EVIDENCE_MISSING_OR_MISMATCH'
    elif inside<stage['requested_unique_iterations'] or stage['dropped_iterations']!=0 or stage['oom_observed']:reason='GENERATION_MISSED'
    return {'path':root.name,'first_sent':len(first),'first_in_window':inside,'requested':stage['requested_unique_iterations'],'dropped':stage['dropped_iterations'],'reason':reason,
            'denominators':{'requested_unique_iterations':stage['requested_unique_iterations'],'sent_first_requests':len(first),
                            'sent_first_in_window':inside,'sent_first_outside_window':len(first)-inside if known else None,
                            'unsent_scheduled_iterations':stage['dropped_iterations'],'sent_duplicate_requests':len(duplicates),
                            'note':'unsent demand remains in total demand; it is not a request that reached the SUT. Duplicate requests are separate from first iterations.'},
            'generator_resource_observation':generator_evidence(root),
            'consistency_observation':{'status':'NOT_ASSESSED_BY_THIS_GATE','source':'independent DB/attempt and client reconciliation required'},
            'failure_observation':{'target_generation_missed':reason=='GENERATION_MISSED','oom_observed':stage['oom_observed']},
            'cause_assessment':{'attribution':'UNKNOWN','confidence':'NOT_ESTABLISHED','note':'response delay, scheduling, generator and service limits require independent analysis; target miss does not discard other observations'}}

def evaluate(roots):
    results=[]
    for root in roots:
        try:results.append(assess(Path(root)))
        except (OSError,ValueError,KeyError):results.append({'path':str(root),'reason':'EVIDENCE_MISSING_OR_MISMATCH'})
    reasons={r['reason'] for r in results};code=4 if 'EVIDENCE_MISSING_OR_MISMATCH' in reasons else 3 if 'GENERATION_MISSED' in reasons else 0
    return code,{'exit_code':code,'stages':results,'scope':'target generation only; exit3 preserves failure/consistency observations, zero is not baseline or SLO PASS'}
if __name__=='__main__':
    code,result=evaluate(sys.argv[1:]);print(json.dumps(result,indent=2));sys.exit(code)
