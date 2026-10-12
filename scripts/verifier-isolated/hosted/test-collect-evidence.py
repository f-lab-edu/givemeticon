import copy,importlib.util,tempfile,unittest,subprocess,sys,json
from pathlib import Path
spec=importlib.util.spec_from_file_location('collector',Path(__file__).with_name('collect-evidence.py'));m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
class T(unittest.TestCase):
 def test_allowlist_redaction(self):
  with tempfile.TemporaryDirectory() as d:
   src=Path(d)/'src';dst=Path(d)/'dst';src.mkdir();(src/'a.log').write_text('sentinel');(src/'password').write_text('sentinel');(src/'db-dump.sql.gz').write_bytes(b'sentinel');(src/'k6.json').write_text('sentinel');(src/'link.log').symlink_to(src/'password')
   m.collect(src,dst,b'sentinel');self.assertEqual((dst/'a.log').read_text(),'[REDACTED]');self.assertEqual(sorted(p.name for p in dst.iterdir()),['a.log','artifact-manifest.json'])
 def test_second_collection_without_secret_preserves_redacted_artifact(self):
  with tempfile.TemporaryDirectory() as d:
   p=Path(d);src=p/'src';src.mkdir();dst=p/'dst';secret=p/'pw';secret.write_text('sentinel-value');(src/'a.log').write_text('sentinel-value')
   command=[sys.executable,str(Path(__file__).with_name('collect-evidence.py')),str(src),str(dst),str(secret)]
   for _ in range(2):
    result=subprocess.run(command,capture_output=True,text=True);self.assertEqual(result.returncode,0,result.stderr);self.assertEqual((dst/'a.log').read_text(),'[REDACTED]')
   before={str(f.relative_to(dst)):f.read_bytes() for f in dst.rglob('*') if f.is_file()};secret.unlink()
   result=subprocess.run(command,capture_output=True,text=True);self.assertEqual(result.returncode,3);self.assertEqual(before,{str(f.relative_to(dst)):f.read_bytes() for f in dst.rglob('*') if f.is_file()})
   secret.write_bytes(b'');result=subprocess.run(command,capture_output=True,text=True);self.assertEqual(result.returncode,3);self.assertEqual(before,{str(f.relative_to(dst)):f.read_bytes() for f in dst.rglob('*') if f.is_file()})
   self.assertTrue(json.loads((dst/'artifact-manifest.json').read_text())['redaction_applied']);self.assertNotIn('sentinel-value',result.stdout+result.stderr)
 def test_first_collection_failure_and_initial_secret_absence_keep_safe_evidence(self):
  for reason in ('missing','empty','missing-source'):
   with tempfile.TemporaryDirectory() as d:
    p=Path(d);src=p/'src';src.mkdir();(src/'raw.log').write_text('sentinel-value');dst=p/'dst';dst.mkdir();(dst/'capability-initial.json').write_text('{"phase":"before-secret"}');secret=p/'pw'
    if reason=='empty':secret.write_bytes(b'')
    if reason=='missing-source':secret.write_text('sentinel-value');src=p/'absent'
    before={f.name:f.read_bytes() for f in dst.iterdir()}
    result=subprocess.run([sys.executable,str(Path(__file__).with_name('collect-evidence.py')),str(src),str(dst),str(secret)],capture_output=True,text=True)
    self.assertEqual(result.returncode,3);self.assertEqual(before,{f.name:f.read_bytes() for f in dst.iterdir()});self.assertNotIn('sentinel-value',result.stdout+result.stderr)
 def test_empty_secret_refuses_before_creating_destination(self):
  with tempfile.TemporaryDirectory() as d:
   p=Path(d);src=p/'src';src.mkdir();dst=p/'dst'
   with self.assertRaises(ValueError):m.collect(src,dst,b'')
   self.assertFalse(dst.exists())
 def test_calibration_keeps_secret_until_both_collections_finish(self):
  root=Path(__file__).resolve().parents[3];workflow=(root/'.github/workflows/hosted-calibration.yml').read_text();cleanup=Path(__file__).with_name('cleanup-calibration.sh').read_text()
  self.assertNotIn('rm -f "$pw"',cleanup)
  rescue=workflow.index('name: Rescue owned cleanup and evidence');delete=workflow.index('name: Remove ephemeral credential after both evidence collections');upload=workflow.index('actions/upload-artifact')
  self.assertLess(rescue,delete);self.assertLess(delete,upload);self.assertIn('if: always()',workflow[delete:upload]);self.assertIn('rm -f "$RUNNER_TEMP/issue177-b-password"',workflow[delete:upload])
 def test_size_budget_records_omission(self):
  with tempfile.TemporaryDirectory() as d:
   src=Path(d)/'src';src.mkdir();(src/'x.log').write_text('12345');old=m.LIMIT;m.LIMIT=4
   try:m.collect(src,Path(d)/'dst',b'sentinel')
   finally:m.LIMIT=old
   self.assertFalse((Path(d)/'dst/x.log').exists());self.assertIn('size-budget',(Path(d)/'dst/artifact-manifest.json').read_text())
 def test_safe_policy_sidecar_without_redaction_input(self):
  for secret_state in ('missing','empty'):
   with tempfile.TemporaryDirectory() as d:
    p=Path(d);src=p/'src';policy=src/'stock-policy';policy.mkdir(parents=True);dst=p/'dst';secret=p/'pw'
    if secret_state=='empty':secret.write_bytes(b'')
    record=m.failure.Diagnostic('stock','app1').record
    (policy/'app1.failure.json').write_text(json.dumps(record));(src/'raw.log').write_text('password=sentinel-secret')
    result=subprocess.run([sys.executable,str(Path(__file__).with_name('collect-evidence.py')),str(src),str(dst),str(secret)],capture_output=True,text=True)
    self.assertEqual(result.returncode,3);self.assertEqual(json.loads((dst/'stock-policy/app1.failure.json').read_text()),record);self.assertFalse((dst/'raw.log').exists());self.assertNotIn('sentinel-secret',result.stdout+result.stderr)
 def test_untrusted_sidecar_cannot_bypass_redaction(self):
  for key,value in (('stage','password=sentinel-secret'),('exception_class','token=sentinel-secret'),('run_sha','a'*40+'\ncredential'),('return_code','secret'),('extra','password=sentinel-secret')):
   with tempfile.TemporaryDirectory() as d:
    p=Path(d);src=p/'src';policy=src/'stock-policy';policy.mkdir(parents=True);dst=p/'dst';record=m.failure.Diagnostic('stock','app1').record;record[key]=value
    (policy/'app1.failure.json').write_text(json.dumps(record))
    result=subprocess.run([sys.executable,str(Path(__file__).with_name('collect-evidence.py')),str(src),str(dst),str(p/'missing')],capture_output=True,text=True)
    self.assertEqual(result.returncode,3);self.assertFalse(dst.exists());self.assertNotIn('sentinel-secret',result.stdout+result.stderr)
 def test_v1_hosted_and_strict_v2_boundary(self):
  fixture=Path(__file__).parent/'fixtures/worker-conditions/hosted-v1-failure.json'
  old=json.loads(fixture.read_text());self.assertEqual(old['schema'],'policy-failure-v1')
  counts={name:{'pos_count':0,'neg_count':1} for name in m.failure.WORKERS}
  counts[m.failure.WORKERS[-1]]={'pos_count':0,'neg_count':0}
  current=dict(old,schema='policy-failure-v2',condition_counts=counts)
  bad=[]
  bad.append(dict(old,condition_counts=counts))
  bad.append(dict(current,stage='process_capture',reason='PROCESS_CAPTURE_FAILED'))
  absent=dict(current);del absent['condition_counts'];bad.append(absent)
  for name in m.failure.WORKERS:
   for key in ('pos_count','neg_count'):
    for value in (True,False,-1,3,0.0,None,'password=sentinel'):
     changed=copy.deepcopy(current);changed['condition_counts'][name][key]=value;bad.append(changed)
   changed=copy.deepcopy(current);del changed['condition_counts'][name];bad.append(changed)
   changed=copy.deepcopy(current);changed['condition_counts'][name]['extra']=0;bad.append(changed)
   changed=copy.deepcopy(current);del changed['condition_counts'][name]['pos_count'];bad.append(changed)
  changed=copy.deepcopy(current);changed['condition_counts']['unknown']={'pos_count':0,'neg_count':0};bad.append(changed)
  bad.append(dict(current,extra='password=sentinel'))
  bad.append(dict(current,condition_counts={n:{'pos_count':0,'neg_count':1} for n in m.failure.WORKERS}))
  normal_v1=dict(current,path='v1',condition_counts={n:({'pos_count':1,'neg_count':0} if n==m.failure.WORKERS[-1] else {'pos_count':0,'neg_count':1}) for n in m.failure.WORKERS});bad.append(normal_v1)
  for record,valid in [(old,True),(current,True)]+[(r,False) for r in bad]:
   with self.subTest(record=record),tempfile.TemporaryDirectory() as d:
    if valid:self.assertEqual(m.failure.validate(record),record)
    else:self.assertRaises(ValueError,m.failure.validate,record)
    p=Path(d);src=p/'src';policy=src/(record['path']+'-policy');policy.mkdir(parents=True);dst=p/'dst'
    (policy/'app1.failure.json').write_text(json.dumps(record))
    if valid:
     m.collect_policy_failures(src,dst);self.assertEqual(json.loads((dst/(record['path']+'-policy/app1.failure.json')).read_text()),record)
    else:
     self.assertRaises(ValueError,m.collect_policy_failures,src,dst);self.assertFalse(dst.exists())
     result=subprocess.run([sys.executable,str(Path(__file__).with_name('collect-evidence.py')),str(src),str(dst),str(p/'missing')],capture_output=True,text=True)
     self.assertEqual(result.returncode,3);self.assertFalse(dst.exists());self.assertNotIn('sentinel',result.stdout+result.stderr)
 def test_diagnostic_stage_reset_and_count_fallback(self):
  diagnostic=m.failure.Diagnostic('stock','app1');diagnostic.stage('conditions_match')
  counts={n:{'pos_count':0,'neg_count':0} for n in m.failure.WORKERS}
  diagnostic.counts(counts);self.assertEqual(diagnostic.record['schema'],'policy-failure-v2')
  diagnostic.stage('process_capture');self.assertEqual(diagnostic.record['schema'],'policy-failure-v1');self.assertNotIn('condition_counts',diagnostic.record);m.failure.validate(diagnostic.record)
  diagnostic.stage('conditions_match');counts[m.failure.WORKERS[0]]['pos_count']=True
  self.assertRaises(ValueError,diagnostic.counts,counts);self.assertEqual(diagnostic.record['schema'],'policy-failure-v1');m.failure.validate(diagnostic.record)
 def test_symlink_policy_sidecar_is_not_collected(self):
  with tempfile.TemporaryDirectory() as d:
   p=Path(d);src=p/'src';policy=src/'stock-policy';policy.mkdir(parents=True);outside=p/'outside';outside.write_text(json.dumps(m.failure.Diagnostic('stock','app1').record));(policy/'app1.failure.json').symlink_to(outside)
   m.collect_policy_failures(src,p/'dst');self.assertFalse((p/'dst').exists())
unittest.main()
