import importlib.util,tempfile,unittest,subprocess,sys,json
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
unittest.main()
