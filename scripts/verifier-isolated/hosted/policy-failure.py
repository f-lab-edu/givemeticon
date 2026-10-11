#!/usr/bin/env python3
"""Fixed diagnostic vocabulary; no exception messages, commands or runtime dumps."""
import json,re,sys
from pathlib import Path
STAGES = ('contract','runtime_context','endpoint','inspect','owner','binding','worker_args','mounts',
          'conditions_capture','conditions_decode','conditions_match','process_capture',
          'process_identity','jar_libraries','metrics_config','dependency_reference',
          'dependency_hashes','custom_policy','source_hashes','result','policy_write')
CLASSES = ('OSError','FileNotFoundError','PermissionError','ValueError','KeyError',
           'TypeError','JSONDecodeError','UnicodeDecodeError','CalledProcessError',
           'TimeoutExpired','SubprocessError','BadZipFile','Exception')
KEYS = {'schema','stage','reason','exception_class','path','app','run_sha','return_code','http_status'}

def validate(record):
    if not isinstance(record,dict) or set(record)!=KEYS: raise ValueError('invalid diagnostic')
    if record['schema'] != 'policy-failure-v1' or record['stage'] not in STAGES: raise ValueError('invalid diagnostic')
    if record['reason'] != record['stage'].upper()+'_FAILED' or record['exception_class'] not in CLASSES: raise ValueError('invalid diagnostic')
    if record['path'] not in ('stock','v1') or record['app'] not in ('app1','app2'): raise ValueError('invalid diagnostic')
    if not isinstance(record['run_sha'],str) or not re.fullmatch(r'[0-9a-f]{40}|UNKNOWN',record['run_sha']): raise ValueError('invalid diagnostic')
    for key,low,high in (('return_code',-255,255),('http_status',100,599)):
        value=record[key]
        if value is not None and (type(value) is not int or not low<=value<=high): raise ValueError('invalid diagnostic')
    return record

class Diagnostic:
    def __init__(self,path,app):
        self.record=dict(schema='policy-failure-v1',stage='contract',reason='CONTRACT_FAILED',
                         exception_class='Exception',path=path,app=app,run_sha='UNKNOWN',return_code=None,http_status=None)
    def stage(self,name):
        self.record.update(stage=name,reason=name.upper()+'_FAILED',return_code=None,http_status=None)
    def sha(self,value):
        if isinstance(value,str) and re.fullmatch(r'[0-9a-f]{40}',value): self.record['run_sha']=value
    def fail(self,exc,out):
        name=type(exc).__name__
        self.record['exception_class']=name if name in CLASSES else 'Exception'
        if name=='CalledProcessError' and type(exc.returncode) is int and -255<=exc.returncode<=255:
            self.record['return_code']=exc.returncode
        record=validate(self.record)
        # This fixed summary survives a sidecar write failure without exposing the failure.
        print('run policy evidence unavailable/mismatched; no inferred defaults '+json.dumps(record,sort_keys=True),file=sys.stderr)
        try:
            out.with_suffix('.failure.json').write_text(json.dumps(record,sort_keys=True)+'\n')
        except Exception:
            print('policy diagnostic sidecar write failed; gate remains refused',file=sys.stderr)
