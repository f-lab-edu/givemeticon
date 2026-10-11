#!/usr/bin/env python3
"""Whitelisted run policy evidence, not a general runtime filter-verification framework."""
import argparse,hashlib,importlib.util,json,re,subprocess,time,zipfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[3]
LEGACY=('CouponIssueAsyncWorker','CouponBatchIssueWorker','CouponIssueRequestRecoveryScheduler')
EVENT='CouponEventIssuanceWorker'

def conditions(document,path):
    pos={};neg={}
    for ctx in document.get('contexts',{}).values():
        pos.update(ctx.get('positiveMatches',{}));neg.update(ctx.get('negativeMatches',{}))
    def find(table,name):return [(k,v) for k,v in table.items() if k.endswith('.'+name) or k==name]
    selected={};ok=True
    for name in (*LEGACY,EVENT):
        p=find(pos,name);n=find(neg,name);wanted=(name==EVENT and path=='v1')
        matched=len(p)==1 and not n;excluded=len(n)==1 and not p
        ok=ok and (matched if wanted else excluded)
        selected[name]={'expected_present':wanted,'condition_matched':matched,'condition_excluded':excluded,'condition_evidence':p or n}
    return {'pass':bool(ok),'selected_conditions':selected,'scope':'Spring conditional registration decision; not scheduler activity/latency evidence'}

def metrics_config(args,environment,configurations):
    """Audit known mounted/embedded inputs; never claim framework filters absent."""
    key='--management.metrics.web.server.max-uri-tags='
    limits=[x[len(key):] for x in args if x.startswith(key)]
    if limits!=['100']:raise ValueError('explicit URI limit absent/conflicting')
    if any(x.startswith('--management.metrics.enable') for x in args):raise ValueError('unreviewed metrics enable override')
    # Read only names; never persist environment values.
    names={x.split('=',1)[0] for x in environment}
    if any(n.startswith('MANAGEMENT_METRICS_') or n in ('SPRING_APPLICATION_JSON','JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS') for n in names):raise ValueError('unreviewed metrics property source')
    for body in configurations.values():
        # Conservative refusal rather than a YAML parser or a runtime policy verifier.
        if re.search(r'(?m)^\s*enable\s*:|management[.]metrics[.]enable',body.decode()):raise ValueError('unreviewed enable map in configuration')
    hashes={name:hashlib.sha256(body).hexdigest() for name,body in configurations.items()}
    return {'max_uri_tags':100,'enable':{},'source_sha256':hashlib.sha256(json.dumps(hashes,sort_keys=True).encode()).hexdigest(),
            'configuration_sha256':hashes,'uri_limit_source':'explicit CLI on same owned container',
            'enable_scope':'no enable override in reviewed embedded/mounted configuration, selected CLI and environment keys; not proof of every runtime registry policy'}

def execute(path,app,contract,out):
    declared=json.loads(contract.read_text());expected=declared['expected_prometheus_targets'][app];owner=__import__('os').environ['VERIFIER_OWNER_ID'];prefix=__import__('os').environ['VERIFIER_PREFIX']
    if expected!=f'http://127.0.0.1:{18080 if app=="app1" else 18081}/actuator/prometheus':raise ValueError('run endpoint mismatch')
    inspected=json.loads(subprocess.check_output(['docker','inspect',f'{prefix}-{app}'],text=True))[0]
    if inspected['Config']['Labels'].get('xyz.buzz.verifier.owner')!=owner or inspected['Config']['Labels'].get('xyz.buzz.verifier.managed')!='capped-harness':raise ValueError('owner mismatch')
    bindings=inspected.get('NetworkSettings',{}).get('Ports',{}).get('8080/tcp') or []
    if not any(b.get('HostPort')==str(18080 if app=='app1' else 18081) for b in bindings):raise ValueError('app endpoint binding mismatch')
    args=inspected['Config'].get('Cmd') or []
    if '--coupon.issue-worker.mode=off' not in args or '--coupon.issue-worker.enabled=false' not in args:raise ValueError('legacy override absent')
    jar=ROOT/'build/verifier-cap/app.jar';config=ROOT/'scripts/verifier-isolated/verifier-loadtest-config.yml'
    mounts={x['Destination']:x for x in inspected['Mounts']}
    for dst,source in (('/app.jar',jar),('/cfg/config.yml',config)):
        if Path(mounts[dst]['Source']).resolve()!=source.resolve() or mounts[dst]['RW']:raise ValueError('run source mount mismatch')
    target=expected.replace('/actuator/prometheus','/actuator/conditions')
    response=subprocess.run(['curl','--max-time','3','-fsS','-w','\n%{http_code}\n%{url_effective}',target],capture_output=True,text=True,timeout=5)
    body,status,effective=response.stdout.rsplit('\n',2)
    if response.returncode!=0 or status!='200' or effective!=target:raise ValueError('condition capture failed')
    verdict=conditions(json.loads(body),path)
    if not verdict['pass']:raise ValueError('Spring worker conditions mismatch')
    capture_spec=importlib.util.spec_from_file_location('capture',Path(__file__).with_name('capture-prom.py'));capture=importlib.util.module_from_spec(capture_spec);capture_spec.loader.exec_module(capture)
    anchor_file=out.with_suffix('.prom');anchor=capture.capture(expected,anchor_file)
    starts=[float(line.rsplit(' ',1)[1]) for line in anchor_file.read_text().splitlines() if line.startswith('process_start_time_seconds')]
    if not anchor['transport_body_complete'] or len(starts)!=1 or starts[0]<=0:raise ValueError('policy process identity missing')

    with zipfile.ZipFile(jar) as z:
        libs=[n for n in z.namelist() if n.startswith('BOOT-INF/lib/')]
        for exact in ('micrometer-core-1.11.3.jar','spring-web-6.0.11.jar','spring-boot-actuator-autoconfigure-3.1.3.jar'):
            if 'BOOT-INF/lib/'+exact not in libs:raise ValueError('resolved library changed')
        hashes={n:hashlib.sha256(z.read('BOOT-INF/lib/'+n)).hexdigest() for n in ('micrometer-core-1.11.3.jar','spring-web-6.0.11.jar','spring-boot-actuator-autoconfigure-3.1.3.jar')}
        config_inputs={n:z.read(n) for n in z.namelist() if n.startswith('BOOT-INF/classes/application') and n.endswith(('.yml','.yaml','.properties'))}
        config_inputs['mounted:/cfg/config.yml']=config.read_bytes()
        effective_metrics=metrics_config(args,inspected['Config'].get('Env',[]),config_inputs)
    # Reviewed application scope only; do not dump environments, conditions or private configs.
    reviewed=json.loads((ROOT/'docs/experiments/warmed-meter-registration-evidence/boot-dependencies.json').read_text())
    expected_hashes={Path(x['path']).name:x['sha256'] for x in reviewed['libraries']}
    if any(expected_hashes.get(k)!=v for k,v in hashes.items()):raise ValueError('dependency bytecode differs from reviewed jars')
    app_sources=list((ROOT/'src/main/java').rglob('*.java'))
    if any('MeterFilter' in f.read_text() or re.search(r'(?:meterRegistry|registry)\s*\.\s*remove\s*\(',f.read_text(),re.I) for f in app_sources):raise ValueError('unreviewed custom meter policy')
    source_hashes={str(f.relative_to(ROOT)):hashlib.sha256(f.read_bytes()).hexdigest() for f in app_sources if f.name in ('DistributedLockAop.java','CouponBatchAdmissionAcceptor.java','CouponAdmissionTransactionTimers.java','CouponLoadTestController.java','CouponAdmissionTestController.java','UserController.java','SpendCashPointFacade.java',*tuple(x+'.java' for x in (*LEGACY,EVENT)))}
    refs={'http_server_requests':'Spring6.0.11 ServerHttpObservationFilter.stop -> Micrometer1.11.3 DefaultMeterObservationHandler.onStop Timer.builder.tags.register; retained matching-jar javap evidence',
          'coupon_redis_lock_acquire':'src/main/java/com/jinddung2/givemeticon/common/aop/DistributedLockAop.java:54-58 and73-74',
          'coupon_redis_lock_hold':'src/main/java/com/jinddung2/givemeticon/common/aop/DistributedLockAop.java:67-70',
          'coupon_admission_batch_queue_wait':'CouponBatchAdmissionAcceptor Timer.builder/register per flush',
          'coupon_admission_transaction':'CouponAdmissionTransactionTimers timer register per call',
          'coupon_admission_commit':'CouponAdmissionTransactionTimers timer register per call',
          'coupon_admission_post_lock_work':'CouponAdmissionTransactionTimers timer register per call'}
    result={'run_sha':declared['sha'],'app':app,'expected_target':expected,'jar_sha256':hashlib.sha256(jar.read_bytes()).hexdigest(),'config_source_sha256':hashlib.sha256(config.read_bytes()).hexdigest(),'dependency_hashes':hashes,'reviewed_source_sha256':source_hashes,
            'process_start_time_seconds_at_policy':starts[0],'policy_process_capture':anchor,'whitelisted_explicit_properties':{'coupon.issue-worker.mode':'off','coupon.issue-worker.enabled':False,'management.metrics.web.server.max-uri-tags':100,'management.endpoints.web.exposure.include':'health,prometheus,conditions'},'spring_worker_conditions':verdict,
            'recorded_at_ms':int(time.time()*1000),'condition_capture':{'exit_code':response.returncode,'http_status':200,'requested_target':target,'effective_target':effective,'body_sha256':hashlib.sha256(body.encode()).hexdigest()},
            'meter_policy':{'custom_app_meter_filter_or_registry_remove_used':False,'framework_meter_filters':['WebMvcObservationAutoConfiguration.OnlyOnceLoggingDenyMeterFilter(http.server.requests, max-uri-tags=100)','PropertiesMeterFilter(enable overrides, common tags and distribution configuration)'],'effective_metrics_config':effective_metrics,'http_uri_contract':{'allowed_uris':[],'verdict':'NOT_ESTABLISHED; no HTTP absent-before inference in warmed gate'},'spring_boot':'3.1.3','micrometer':'1.11.3','registration_paths':refs,'evidence':'Reviewed app source has no custom MeterFilter/registry.remove; exact mounted jar/config and dependency hashes recorded; matching dependency bytecode retained in docs/experiments/warmed-meter-registration-evidence','scope':'bounded reviewed source/config claim; existence is not automatic proof of every runtime registry policy','runtime_registry_policy_verdict':'UNKNOWN; no general runtime registry introspection'},
            'lock_call_sites':{'worker_disabled':True,'recovery_disabled':True,'scope':'legacy worker and recovery conditional beans only; other lock sites not excluded','other_lock_call_sites_verdict':'UNKNOWN','evidence':'','worker_recovery_evidence':'same owned app: explicit CLI off/false plus selected actual Spring conditions; V1 event worker separately remains enabled; insufficient for all-site HTTP/lock reconciliation','artifact_ref':out.name},
            'lock_input_scope':{'contract_ref':'contract.json','stock_route':'POST /internal/loadtest/coupons -> CreateCouponFacade.createCouponAndDecreaseStock','v1_route':'POST /test-support/coupon-events/{eventId}/applications -> CouponAdmissionService.accept','other_lock_route':'UserController POST /api/v1/users/my-point/spend -> SpendCashPointFacade.spendPoint; not a generated workload route, other callers remain UNKNOWN','workload_source_sha256':{str(f.relative_to(ROOT)):hashlib.sha256(f.read_bytes()).hexdigest() for f in (ROOT/'scripts/verifier-isolated/stock-arrival-record.js',ROOT/'scripts/coupon-integrated-loadtest/integrated-admission-poll.js') if f.exists()}},
            'source_manifest_sha256':hashlib.sha256((ROOT/'docs/experiments/WARMED_SOURCE_MANIFEST_ISSUE_177.json').read_bytes()).hexdigest()}
    out.write_text(json.dumps(result,indent=2)+'\n');return result
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('path',choices=['stock','v1']);p.add_argument('app',choices=['app1','app2']);p.add_argument('contract',type=Path);p.add_argument('out',type=Path);a=p.parse_args()
    try:execute(a.path,a.app,a.contract,a.out)
    except (OSError,ValueError,KeyError,TypeError,subprocess.SubprocessError):raise SystemExit('run policy evidence unavailable/mismatched; no inferred defaults')
