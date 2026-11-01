"""Read-only row/file fingerprints for Backend154's explicitly owned synthetic fixture.
Run with Docker group access; never prints raw rows, credentials or uploaded bytes.
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess

p=argparse.ArgumentParser()
p.add_argument('--phase',required=True)
p.add_argument('--database',required=True,choices=['auth154_v9','auth154_restore_v9','auth154_restore_v10','auth154_restore_auth'])
p.add_argument('--storage',required=True)
p.add_argument('--output',required=True)
p.add_argument('--previous')
a=p.parse_args()
root=Path('/tmp/ultistats-auth154').resolve()
for value in [a.storage,a.output,*([a.previous] if a.previous else [])]:
    assert Path(value).resolve().is_relative_to(root),'only explicitly owned private fixture paths are allowed'
owner=subprocess.check_output(['docker','inspect','--format','{{index .Config.Labels "ultistats.owner"}}','ultistats-auth154-pg'],text=True).strip()
assert owner=='backend154','refuse a container without exact fixture owner label'

def sql(query):
    return subprocess.check_output(['docker','exec','ultistats-auth154-pg','psql','-X','-U','auth154','-d',a.database,'-At','-v','ON_ERROR_STOP=1','-c',query],text=True).strip()
def quote(name):
    assert name.replace('_','').isalnum()
    return '"'+name+'"'
previous=json.loads(Path(a.previous).read_text()) if a.previous else None
tables=sql("SELECT tablename FROM pg_tables WHERE schemaname='public' AND tablename<>'flyway_schema_history' ORDER BY tablename").splitlines()
rows={}
for table in (previous['tables'] if previous else tables):
    columns=previous['tables'][table]['columns'] if previous else sql("SELECT column_name FROM information_schema.columns WHERE table_schema='public' AND table_name='"+table+"' ORDER BY ordinal_position").splitlines()
    count,digest=sql('SELECT count(*),md5(COALESCE(string_agg(j,E\'\\n\' ORDER BY j),\'\')) FROM (SELECT row_to_json(t)::text j FROM (SELECT '+','.join(map(quote,columns))+' FROM '+quote(table)+')t)s').split('|')
    rows[table]={'columns':columns,'count':int(count),'digest':digest}
files={}
for file in Path(a.storage).rglob('*'):
    assert not file.is_symlink(),'unexpected symlink in synthetic upload fixture'
    if file.is_file():files[str(file.relative_to(a.storage))]=hashlib.sha256(file.read_bytes()).hexdigest()
result={'phase':a.phase,'database':a.database,'tables':rows,'files':files,'flyway':sql('SELECT version,checksum,success FROM flyway_schema_history ORDER BY installed_rank')}
out=Path(a.output);out.write_text(json.dumps(result,indent=2)+'\n');out.chmod(0o600)
if previous:
    assert rows==previous['tables'],'DB preservation fingerprint mismatch'
    assert files==previous['files'],'file preservation fingerprint mismatch'
print(a.phase,'fingerprints',len(rows),'tables',sum(x['count'] for x in rows.values()),'rows',len(files),'files','MATCH' if previous else 'CAPTURED')
