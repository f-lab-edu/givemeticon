import csv,json,subprocess,sys,tempfile,threading,time,unittest
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
from pathlib import Path
class Handler(BaseHTTPRequestHandler):
 def do_GET(self):
  self.send_response(200);self.end_headers();self.wfile.write(b'hikaricp_connections_active 3\nhikaricp_connections_pending 1\n')
 def log_message(self,*args):pass
class T(unittest.TestCase):
 def test_stock_sampler_without_mysql_records_success_and_gap(self):
  server=ThreadingHTTPServer(('127.0.0.1',0),Handler);threading.Thread(target=server.serve_forever,daemon=True).start()
  with tempfile.TemporaryDirectory() as d:
   p=subprocess.Popen([sys.executable,str(Path(__file__).with_name('fast-sampler.py')),d,'unused','unused','-',str(server.server_port),str(server.server_port),'0.02'])
   try:
    deadline=time.monotonic()+3
    while time.monotonic()<deadline:
     f=Path(d)/'fast-prom.csv'
     if f.exists() and len(f.read_text().splitlines())>=5:break
     time.sleep(.02)
   finally:p.terminate();p.wait(timeout=3);server.shutdown();server.server_close()
   self.assertEqual(p.returncode,0);self.assertFalse((Path(d)/'fast-db.csv').exists())
   h=json.loads((Path(d)/'fast-prom-health.json').read_text())['apps'];self.assertGreaterEqual(h['app1']['successes'],2);self.assertIsNotNone(h['app1']['max_success_gap_ms'])
   with (Path(d)/'fast-prom.csv').open() as f:rows=list(csv.DictReader(f))
   self.assertEqual(rows[0]['active'],'3.0');self.assertEqual(rows[0]['idle'],'NA')
unittest.main()
