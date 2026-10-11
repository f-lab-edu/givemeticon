#!/usr/bin/env python3
"""Future-run scrape transport provenance; never certifies metric semantic completeness."""
import argparse,hashlib,json,subprocess,time
from pathlib import Path
from urllib.parse import urlsplit
HERE=Path(__file__).parent

def capture(target,destination):
    url=urlsplit(target)
    if url.scheme!='http' or url.hostname!='127.0.0.1' or url.path!='/actuator/prometheus' or url.username or url.password or url.query or url.fragment:raise ValueError('non-local or filtered target refused')
    destination.parent.mkdir(parents=True,exist_ok=True)
    # A dedicated new path is expected; no stale success body can survive failed capture.
    destination.write_bytes(b'');started=int(time.time()*1000)
    command=['curl','--max-time','3','-fsS','-H','Accept: text/plain; version=0.0.4','-o',str(destination),'-w','%{http_code}\n%{url_effective}\n%{size_download}',target]
    try:p=subprocess.run(command,capture_output=True,text=True,timeout=5)
    except (OSError,subprocess.TimeoutExpired):p=subprocess.CompletedProcess(command,124,'','')
    data=destination.read_bytes();status=None;effective=None;download=None
    try:
        fields=p.stdout.splitlines();status=int(fields[0]);effective=fields[1];download=int(fields[2])
    except (IndexError,ValueError):pass
    config=HERE.parent/'verifier-loadtest-config.yml'
    result={'schema_version':1,'captured_at_start_ms':started,'captured_at_end_ms':int(time.time()*1000),'requested_target':target,'effective_target':effective,
            'curl_exit':p.returncode,'http_status':status,'download_bytes':download,'body_bytes':len(data),'body_sha256':hashlib.sha256(data).hexdigest(),
            'transport_body_complete':p.returncode==0 and status==200 and effective==target and download==len(data) and bool(data),
            'request_filter':{'query':'','accept':'text/plain; version=0.0.4'},'config_source_sha256':hashlib.sha256(config.read_bytes()).hexdigest() if config.exists() else None,
            'server_registry_filter_state':'UNKNOWN: source hash alone does not prove running registry exposure','metric_semantic_completeness':'NOT_ESTABLISHED_BY_TRANSPORT_CAPTURE',
            'note':'future capture only; exit/target/body digest do not justify absent metric zero or prove no server-side truncation/filtering'}
    Path(str(destination)+'.capture.json').write_text(json.dumps(result,indent=2)+'\n')
    Path(str(destination)+'.exit.txt').write_text(str(p.returncode)+'\n')
    return result
if __name__=='__main__':
    a=argparse.ArgumentParser();a.add_argument('target');a.add_argument('destination',type=Path);args=a.parse_args();result=capture(args.target,args.destination);raise SystemExit(0 if result['transport_body_complete'] else 3)
