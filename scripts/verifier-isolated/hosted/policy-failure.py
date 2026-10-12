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

WORKERS = ('CouponIssueAsyncWorker','CouponBatchIssueWorker','CouponIssueRequestRecoveryScheduler','CouponEventIssuanceWorker')

def validate_counts(counts):
    if not isinstance(counts,dict) or set(counts)!=set(WORKERS): raise ValueError('invalid diagnostic')
    for values in counts.values():
        if not isinstance(values,dict) or set(values)!={'pos_count','neg_count'}: raise ValueError('invalid diagnostic')
        if any(type(v) is not int or v not in (0,1,2) for v in values.values()): raise ValueError('invalid diagnostic')
    return counts

def validate(record):
    if not isinstance(record,dict): raise ValueError('invalid diagnostic')
    schema=record.get('schema')
    if schema not in ('policy-failure-v1','policy-failure-v2'): raise ValueError('invalid diagnostic')
    expected=KEYS | ({'condition_counts'} if schema=='policy-failure-v2' else set())
    if set(record)!=expected or record['stage'] not in STAGES: raise ValueError('invalid diagnostic')
    if 'condition_counts' in record:
        if record['stage']!='conditions_match': raise ValueError('invalid diagnostic')
        counts=validate_counts(record['condition_counts'])
        if all(values==({'pos_count':1,'neg_count':0} if name==WORKERS[-1] and record.get('path')=='v1' else {'pos_count':0,'neg_count':1}) for name,values in counts.items()): raise ValueError('invalid diagnostic')
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
        self.record.pop('condition_counts',None)
        self.record['schema']='policy-failure-v1'
        self.record.update(stage=name,reason=name.upper()+'_FAILED',return_code=None,http_status=None)
    def counts(self,counts):
        if self.record['stage']!='conditions_match': raise ValueError('invalid diagnostic')
        validated=validate_counts(counts)
        candidate=dict(self.record,schema='policy-failure-v2',condition_counts={name:dict(validated[name]) for name in WORKERS})
        validate(candidate)
        self.record=candidate
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
