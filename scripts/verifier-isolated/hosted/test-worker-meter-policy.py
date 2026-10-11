#!/usr/bin/env python3
import contextlib,io,sys,hashlib,importlib.util,json,os,subprocess,tempfile,unittest,zipfile
from unittest.mock import patch
from pathlib import Path
s=importlib.util.spec_from_file_location('p',Path(__file__).with_name('worker-meter-policy.py'));m=importlib.util.module_from_spec(s);s.loader.exec_module(m)
class T(unittest.TestCase):
 def report(self,event=True,recovery=False):
  pos={};neg={}
  for n in (*m.LEGACY,m.EVENT):(pos if ((n==m.EVENT and event) or (n=='CouponIssueRequestRecoveryScheduler' and recovery)) else neg)['package.'+n]=[{'condition':'OnPropertyCondition','message':'fixture'}]
  return {'contexts':{'application':{'positiveMatches':pos,'negativeMatches':neg}}}
 def test_metrics_inputs_refuse_unknown_overrides(self):
  args=['--management.metrics.web.server.max-uri-tags=100'];good={'application.yml':b'management:\n  metrics:\n    tags:\n      application: test\n'}
  self.assertEqual(m.metrics_config(args,[],good)['enable'],{})
  for a,e,c in (([],[],good),(args+['--management.metrics.enable.http=false'],[],good),(args,['MANAGEMENT_METRICS_ENABLE_ALL=false'],good),(args,[],{'config':b'management:\n  metrics:\n    enable:\n      all: false'})):
   with self.subTest(a=a,e=e):self.assertRaises(ValueError,m.metrics_config,a,e,c)
 def test_event_worker_retained_only_v1(self):
  self.assertTrue(m.conditions(self.report(),'v1')['pass']);self.assertTrue(m.conditions(self.report(event=False),'stock')['pass']);self.assertFalse(m.conditions(self.report(event=False),'v1')['pass'])
 def test_recovery_enabled_refused(self):self.assertFalse(m.conditions(self.report(recovery=True),'v1')['pass'])
 def test_missing_report_is_not_disabled(self):self.assertFalse(m.conditions({},'stock')['pass'])
 def test_recorded_runtime_whitelist_and_source_binding(self):
  with tempfile.TemporaryDirectory() as d:
   root=Path(d);jar=root/'build/verifier-cap/app.jar';jar.parent.mkdir(parents=True);config=root/'scripts/verifier-isolated/verifier-loadtest-config.yml';config.parent.mkdir(parents=True);config.write_text('nonsecret test config')
   libs={'micrometer-core-1.11.3.jar':b'fixture core','spring-web-6.0.11.jar':b'fixture web','spring-boot-actuator-autoconfigure-3.1.3.jar':b'fixture framework'}
   with zipfile.ZipFile(jar,'w') as z:
    for name,data in libs.items():z.writestr('BOOT-INF/lib/'+name,data)
   evidence=root/'docs/experiments/warmed-meter-registration-evidence';evidence.mkdir(parents=True);(evidence/'boot-dependencies.json').write_text(json.dumps({'libraries':[{'path':'BOOT-INF/lib/'+n,'sha256':hashlib.sha256(b).hexdigest()} for n,b in libs.items()]}));(root/'docs/experiments/WARMED_SOURCE_MANIFEST_ISSUE_177.json').write_text('{}')
   contract=root/'contract.json';target='http://127.0.0.1:18080/actuator/prometheus';contract.write_text(json.dumps({'sha':'fixed-run','expected_prometheus_targets':{'app1':target}}))
   inspected={'Config':{'Labels':{'xyz.buzz.verifier.owner':'owner','xyz.buzz.verifier.managed':'capped-harness'},'Cmd':['--coupon.issue-worker.mode=off','--coupon.issue-worker.enabled=false','--management.metrics.web.server.max-uri-tags=100'],'Env':['UNRELATED_SECRET=must-not-persist']},'NetworkSettings':{'Ports':{'8080/tcp':[{'HostPort':'18080'}]}},'Mounts':[{'Destination':'/app.jar','Source':str(jar),'RW':False},{'Destination':'/cfg/config.yml','Source':str(config),'RW':False}]}
   response=subprocess.CompletedProcess([],0,json.dumps(self.report())+'\n200\n'+target.replace('prometheus','conditions'),'')
   def recorded_run(args,**kw):
    if '-o' in args:
     dest=Path(args[args.index('-o')+1]);dest.write_text('process_start_time_seconds 1\n')
     return subprocess.CompletedProcess(args,0,'200\n'+target+'\n'+str(dest.stat().st_size),'')
    return response
   out=root/'policy.json'
   with patch.object(m,'ROOT',root),patch.dict(os.environ,{'VERIFIER_OWNER_ID':'owner','VERIFIER_PREFIX':'prefix'}),patch.object(m.subprocess,'check_output',side_effect=lambda *a,**k:json.dumps([inspected])),patch.object(m.subprocess,'run',side_effect=recorded_run):
    r=m.execute('v1','app1',contract,out);self.assertTrue(r['lock_call_sites']['recovery_disabled']);self.assertEqual(r['lock_call_sites']['evidence'],'');self.assertEqual(r['lock_call_sites']['other_lock_call_sites_verdict'],'UNKNOWN');self.assertEqual(r['dependency_hashes']['micrometer-core-1.11.3.jar'],hashlib.sha256(libs['micrometer-core-1.11.3.jar']).hexdigest());self.assertNotIn('must-not-persist',out.read_text());self.assertNotIn('meter_filter_or_registry_remove_used',r['meter_policy']);self.assertFalse(r['meter_policy']['custom_app_meter_filter_or_registry_remove_used']);self.assertEqual(r['meter_policy']['effective_metrics_config']['max_uri_tags'],100);self.assertTrue(r['meter_policy']['framework_meter_filters'])
    # Exercise real checkpoints, with credential-like exception/output fixtures.
    contract.write_text(json.dumps({'sha':'a'*40,'expected_prometheus_targets':{'app1':target}}))
    sentinel='password=sentinel-secret token=credential https://user:pass@example.invalid'
    def assert_failure(stage,cls,**extra):
     stream=io.StringIO()
     with contextlib.redirect_stderr(stream):self.assertEqual(m.run('v1','app1',contract,out),1)
     record=json.loads(out.with_suffix('.failure.json').read_text())
     self.assertEqual(record['stage'],stage);self.assertEqual(record['reason'],stage.upper()+'_FAILED');self.assertEqual(record['exception_class'],cls);self.assertEqual(record['run_sha'],'a'*40)
     for key,value in extra.items():self.assertEqual(record[key],value)
     self.assertNotIn('sentinel-secret',stream.getvalue()+out.with_suffix('.failure.json').read_text());self.assertNotIn('credential',stream.getvalue());self.assertNotIn('Traceback',stream.getvalue())
    with patch.object(m.subprocess,'check_output',side_effect=subprocess.CalledProcessError(7,['docker',sentinel],output=sentinel,stderr=sentinel)):
     assert_failure('inspect','CalledProcessError',return_code=7)
    with patch.object(m.subprocess,'run',return_value=subprocess.CompletedProcess([],22,sentinel+'\n401\n'+target,sentinel)):
     assert_failure('conditions_capture','ValueError',return_code=22,http_status=401)
    with patch.object(m.subprocess,'run',return_value=subprocess.CompletedProcess([],0,sentinel+'\n200\n'+target.replace('prometheus','conditions'),sentinel)):
     assert_failure('conditions_decode','JSONDecodeError')
    with patch.object(m.subprocess,'run',side_effect=subprocess.TimeoutExpired([sentinel],5,output=sentinel,stderr=sentinel)):
     assert_failure('conditions_capture','TimeoutExpired')
    inspected['Config']['Labels']['xyz.buzz.verifier.owner']=sentinel
    assert_failure('owner','ValueError')
    stream=io.StringIO();original_write=Path.write_text
    def refuse_sidecar(p,*args,**kwargs):
     if p.name.endswith('.failure.json'):raise OSError(sentinel)
     return original_write(p,*args,**kwargs)
    with patch.object(Path,'write_text',refuse_sidecar),contextlib.redirect_stderr(stream):self.assertEqual(m.run('v1','app1',contract,out),1)
    self.assertIn('sidecar write failed; gate remains refused',stream.getvalue());self.assertNotIn('sentinel-secret',stream.getvalue());self.assertNotIn('credential',stream.getvalue())
    inspected['Config']['Labels']['xyz.buzz.verifier.owner']='other';prior=out.read_bytes();self.assertRaises(ValueError,m.execute,'v1','app1',contract,out);self.assertEqual(prior,out.read_bytes())
 def test_initial_failure_without_sha_is_safe(self):
  with tempfile.TemporaryDirectory() as d:
   p=Path(d);stream=io.StringIO()
   with contextlib.redirect_stderr(stream):self.assertEqual(m.run('stock','app2',p/'password=sentinel-secret',p/'app2.json'),1)
   record=json.loads((p/'app2.failure.json').read_text());self.assertEqual(record['stage'],'contract');self.assertEqual(record['run_sha'],'UNKNOWN');self.assertNotIn('sentinel-secret',stream.getvalue())
 def test_unknown_exception_class_is_fixed(self):
  stream=io.StringIO()
  with tempfile.TemporaryDirectory() as d,patch.object(m,'execute',side_effect=type('credential_secret',(Exception,),{})('token=sentinel')),contextlib.redirect_stderr(stream):
   self.assertEqual(m.run('stock','app1',Path(d)/'contract',Path(d)/'app1.json'),1)
   self.assertEqual(json.loads((Path(d)/'app1.failure.json').read_text())['exception_class'],'Exception');self.assertNotIn('credential_secret',stream.getvalue());self.assertNotIn('sentinel',stream.getvalue())
 def test_diagnostic_failure_keeps_nonzero_and_safe_summary(self):
  stream=io.StringIO()
  with patch.object(m,'execute',side_effect=ValueError('password=sentinel')),patch.object(m.failure.Diagnostic,'fail',side_effect=OSError('token=sentinel')),contextlib.redirect_stderr(stream):
   self.assertEqual(m.run('stock','app1',Path('contract'),Path('out')),1)
  self.assertEqual(stream.getvalue(),'policy diagnostic failed; gate remains refused\n')
 def test_cli_captures_subprocess_stderr(self):
  with tempfile.TemporaryDirectory() as d:
   p=Path(d);docker=p/'docker';docker.write_text("#!/bin/sh\nprintf 'password=sentinel-secret token=credential\n' >&2\nexit 7\n");docker.chmod(0o700)
   contract=p/'contract.json';contract.write_text(json.dumps({'sha':'b'*40,'expected_prometheus_targets':{'app1':'http://127.0.0.1:18080/actuator/prometheus'}}))
   env=dict(os.environ,PATH=str(p)+os.pathsep+os.environ['PATH'],VERIFIER_OWNER_ID='fixture',VERIFIER_PREFIX='fixture')
   result=subprocess.run([sys.executable,str(Path(__file__).with_name('worker-meter-policy.py')),'stock','app1',str(contract),str(p/'app1.json')],env=env,capture_output=True,text=True)
   self.assertEqual(result.returncode,1);self.assertNotIn('sentinel-secret',result.stdout+result.stderr);self.assertNotIn('credential',result.stdout+result.stderr);self.assertNotIn('Traceback',result.stderr)
   record=json.loads((p/'app1.failure.json').read_text());self.assertEqual(record['stage'],'inspect');self.assertEqual(record['return_code'],7);self.assertEqual(record['run_sha'],'b'*40)
unittest.main()
