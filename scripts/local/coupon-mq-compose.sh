#!/usr/bin/env bash
# Configuration and explicitly targeted broker startup only; no automatic bootstrap.
set -euo pipefail
set +x
umask 077
FEATURE_DIR=$(cd "$(dirname "$0")/../.." && pwd -P)
: "${LIVE_COMPOSE_DIR:?Set the absolute live base Compose directory}"
: "${MQ_PRIVATE_DIR:?Set a private directory outside the Git worktrees}"
: "${AO_SESSION_ID:?Set the owning AO session}"
export FEATURE_DIR LIVE_COMPOSE_DIR MQ_PRIVATE_DIR AO_SESSION_ID
export MQ_AO_SESSION="$AO_SESSION_ID"
python3 - <<'PY'
import os,re
from pathlib import Path
feature=Path(os.environ['FEATURE_DIR']);live=Path(os.environ['LIVE_COMPOSE_DIR']);private=Path(os.environ['MQ_PRIVATE_DIR'])
if not live.is_absolute() or not private.is_absolute():raise SystemExit('Absolute directories required')
for root in [feature.resolve(),live.resolve()]:
    if private.resolve()==root or root in private.resolve().parents:raise SystemExit('Private directory must be outside both Git worktrees')
if any(not (live/name).is_file() for name in ['docker-compose.infra.yml','docker-compose.observability.yml']):raise SystemExit('Both explicit live base files required')
if not re.fullmatch(r'[A-Za-z0-9_.-]+',os.environ['AO_SESSION_ID']):raise SystemExit('Invalid AO ownership')
if private.is_symlink():raise SystemExit('Private directory cannot be a symlink')
if private.exists() and private.stat().st_mode & 0o777 != 0o700:raise SystemExit('Existing private directory must have mode0700')
PY
PRIVATE_ENV="$MQ_PRIVATE_DIR/compose.env"
compose() {
  docker compose -p givemeticon \
    -f "$LIVE_COMPOSE_DIR/docker-compose.infra.yml" \
    -f "$LIVE_COMPOSE_DIR/docker-compose.observability.yml" \
    -f "$FEATURE_DIR/docker-compose.coupon-mq.yml" \
    --env-file "$PRIVATE_ENV" "$@"
}
case "${1:-}" in
  prepare)
    python3 - <<'PY'
import os,secrets
from pathlib import Path
p=Path(os.environ['MQ_PRIVATE_DIR']);p.mkdir(mode=0o700,exist_ok=True)
files={'compose.env':lambda:'MQ_PRIVATE_DIR='+str(p)+'\nMQ_AO_SESSION='+os.environ['AO_SESSION_ID']+'\n',
       'rabbit.env':lambda:'RABBITMQ_DEFAULT_USER=fixture\nRABBITMQ_DEFAULT_PASS='+secrets.token_hex(32)+'\nRABBITMQ_DEFAULT_VHOST=coupon-local\n',
       'mysql.env':lambda:'MYSQL_DATABASE=coupon_local\nMYSQL_ROOT_HOST=%\nMYSQL_ROOT_PASSWORD='+secrets.token_hex(32)+'\n'}
for name,content in files.items():
    f=p/name
    if f.is_symlink():raise SystemExit('Private file cannot be a symlink')
    if f.exists():
        if f.stat().st_mode & 0o777 != 0o600:raise SystemExit('Existing private file must have mode0600')
        if name=='compose.env' and f.read_text()!=content():raise SystemExit('Private configuration belongs to another path/session')
    else:
        with f.open('x') as out:out.write(content())
        f.chmod(0o600)
PY
    ;;
  config)
    compose --profile coupon-mq-app config --quiet
    ;;
  broker)
    # Root must first check local image, ownership/collisions, ports and VM headroom.
    test -f "$MQ_PRIVATE_DIR/rabbit.env"
    compose up -d --no-deps --no-recreate --pull never mq-rabbit
    ;;
  bind-endpoints)
    # Metadata only, after separately approved creation of all three NEW services.
    # Produces literal endpoint bindings, not bootstrap or application readiness proof.
    MQ_BROKER_ID=$(compose ps -q mq-rabbit)
    MQ_MYSQL_ID=$(compose --profile coupon-mq-app ps -q mq-mysql)
    MQ_REDIS_ID=$(compose --profile coupon-mq-app ps -q mq-redis)
    export MQ_BROKER_ID MQ_MYSQL_ID MQ_REDIS_ID
    python3 - <<'PY'
import ipaddress,json,os,re,subprocess
from pathlib import Path
p=Path(os.environ['MQ_PRIVATE_DIR'])
def inspect(kind,identity):
    return json.loads(subprocess.check_output(['docker',kind,'inspect',identity],text=True))[0]
pins={'mq-rabbit':'rabbitmq@sha256:de03afc2c596b635196692c18425149f1e85d13ba8c1990c1a78684f693dc459','mq-mysql':'mysql@sha256:7dcddc01f13bab2f15cde676d44d01f61fc9f99fe7785e86196dfc07d358ae2b','mq-redis':'redis@sha256:520775a41a63e77e06c73e35d2fd9cc15921a609516818796b4ecbb813078bc7'}
network=inspect('network','givemeticon_coupon-mq')
if not re.fullmatch('[a-f0-9]{64}',network.get('Id','')):raise SystemExit('Invalid immutable network ID')
labels=network.get('Labels') or {}
if network.get('Driver')!='bridge' or network.get('Internal') is not False or network.get('Options',{}).get('com.docker.network.bridge.host_binding_ipv4')!='127.0.0.1' or labels.get('ao.session')!=os.environ['AO_SESSION_ID'] or labels.get('com.docker.compose.project')!='givemeticon':raise SystemExit('Dedicated network ownership/configuration mismatch')
ips={}
for role,key in [('mq-rabbit','MQ_BROKER_ID'),('mq-mysql','MQ_MYSQL_ID'),('mq-redis','MQ_REDIS_ID')]:
    identity=os.environ[key]
    if not re.fullmatch('[a-f0-9]{64}',identity):raise SystemExit('Exactly one immutable owned service ID required')
    data=inspect('container',identity);labels=data.get('Config',{}).get('Labels') or {};nets=data.get('NetworkSettings',{}).get('Networks',{})
    if data.get('Config',{}).get('Image')!=pins[role] or data.get('Id')!=identity or labels.get('ao.session')!=os.environ['AO_SESSION_ID'] or labels.get('com.docker.compose.project')!='givemeticon' or labels.get('com.docker.compose.service')!=role or data.get('State',{}).get('Running') is not True or set(nets)!={'givemeticon_coupon-mq'}:raise SystemExit('Owned service identity/network mismatch')
    endpoint=nets['givemeticon_coupon-mq']
    if endpoint.get('NetworkID')!=network['Id']:raise SystemExit('Immutable network ID mismatch')
    ips[role]=str(ipaddress.IPv4Address(endpoint['IPAddress']))
rabbit=dict(line.split('=',1) for line in (p/'rabbit.env').read_text().splitlines());mysql=dict(line.split('=',1) for line in (p/'mysql.env').read_text().splitlines())
env={'SPRING_PROFILES_ACTIVE':'coupon-mq','SPRING_DATASOURCE_URL':'jdbc:mysql://'+ips['mq-mysql']+':3306/coupon_local?socketTimeout=1000&connectTimeout=1000&useSSL=false&allowPublicKeyRetrieval=true','SPRING_DATASOURCE_USERNAME':'root','SPRING_DATASOURCE_PASSWORD':mysql['MYSQL_ROOT_PASSWORD'],
     'SPRING_DATA_REDIS_MAIL_HOST':ips['mq-redis'],'SPRING_DATA_REDIS_MAIL_PORT':'6379','SPRING_DATA_REDIS_COUPON_HOST':ips['mq-redis'],'SPRING_DATA_REDIS_COUPON_PORT':'6379','SPRING_DATA_REDIS_HOST':ips['mq-redis'],'SPRING_DATA_REDIS_PORT':'6379','SPRING_SESSION_REDIS_NAMESPACE':'coupon-local-'+os.environ['AO_SESSION_ID'],
     'COUPON_MQ_HOST':ips['mq-rabbit'],'COUPON_MQ_PORT':'5672','COUPON_MQ_MANAGEMENT_URL':'http://'+ips['mq-rabbit']+':15672','COUPON_MQ_USERNAME':rabbit['RABBITMQ_DEFAULT_USER'],'COUPON_MQ_PASSWORD':rabbit['RABBITMQ_DEFAULT_PASS'],'COUPON_MQ_VHOST':'coupon-local'}
f=p/'apps.env'
if f.is_symlink():raise SystemExit('App environment cannot be a symlink')
with f.open('w') as out:out.write('\n'.join(k+'='+v for k,v in env.items())+'\n')
f.chmod(0o600)
PY
    ;;
  apps)
    echo 'App startup BLOCKED: verified JAR/source, full legacy bootstrap + Flyway + fixtures, genuine session and exact topology/policy prerequisites require separate approval and verification.' >&2
    exit 1
    ;;
  *) echo 'Usage: coupon-mq-compose.sh prepare|config|broker|bind-endpoints|apps' >&2; exit 2 ;;
esac
