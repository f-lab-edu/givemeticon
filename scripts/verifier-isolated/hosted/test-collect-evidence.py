import importlib.util,tempfile,unittest
from pathlib import Path
spec=importlib.util.spec_from_file_location('collector',Path(__file__).with_name('collect-evidence.py'));m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
class T(unittest.TestCase):
 def test_allowlist_redaction(self):
  with tempfile.TemporaryDirectory() as d:
   src=Path(d)/'src';dst=Path(d)/'dst';src.mkdir();(src/'a.log').write_text('sentinel');(src/'password').write_text('sentinel');(src/'db-dump.sql.gz').write_bytes(b'sentinel');(src/'k6.json').write_text('sentinel');(src/'link.log').symlink_to(src/'password')
   m.collect(src,dst,b'sentinel');self.assertEqual((dst/'a.log').read_text(),'[REDACTED]');self.assertEqual(sorted(p.name for p in dst.iterdir()),['a.log','artifact-manifest.json'])
 def test_size_budget_records_omission(self):
  with tempfile.TemporaryDirectory() as d:
   src=Path(d)/'src';src.mkdir();(src/'x.log').write_text('12345');old=m.LIMIT;m.LIMIT=4
   try:m.collect(src,Path(d)/'dst',b'')
   finally:m.LIMIT=old
   self.assertFalse((Path(d)/'dst/x.log').exists());self.assertIn('size-budget',(Path(d)/'dst/artifact-manifest.json').read_text())
unittest.main()
