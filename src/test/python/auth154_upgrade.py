"""Synthetic upgrade helpers. Seed only an OWNED247/V9 API, never a user target.
Usage: auth154_upgrade.py seed-v9 http://127.0.0.1:21156 PRIVATE_OUTPUT.json
"""
import hashlib
import json
from pathlib import Path
import struct
import sys
import urllib.error
import urllib.request
import uuid
import zlib

mode, base, output = sys.argv[1:]
assert base == 'http://127.0.0.1:21156', 'this harness is restricted to the assigned owned fixture URL'
assert mode == 'seed-v9'

def req(route, method='GET', body=None, expected=200, headers=None):
    data = body if isinstance(body, bytes) else None if body is None else json.dumps(body).encode()
    request = urllib.request.Request(base+route, data=data, method=method, headers=headers or {'Content-Type':'application/json'})
    try:
        response = urllib.request.urlopen(request, timeout=10)
    except urllib.error.HTTPError as error:
        response = error
    raw = response.read()
    assert response.status == expected, (route,response.status,raw[:500])
    return json.loads(raw) if raw else None

def png():
    def chunk(name,data):
        return struct.pack('!I',len(data))+name+data+struct.pack('!I',zlib.crc32(name+data)&0xffffffff)
    return b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('!2I5B',2,2,8,2,0,0,0))+chunk(b'IDAT',zlib.compress(b'\0'+b'\xff\0\0'*2+b'\0'+b'\0\xff\0'*2))+chunk(b'IEND',b'')

def photo(route):
    boundary='auth154-'+str(uuid.uuid4())
    body=(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="synthetic.png"\r\nContent-Type: image/png\r\n\r\n'.encode()+png()+f'\r\n--{boundary}--\r\n'.encode())
    return req(route,'PUT',body,headers={'Content-Type':'multipart/form-data; boundary='+boundary})

teams=[]
players=[]
for side in ['A','B']:
    team=req('/api/v1/teams','POST',{'name':'Auth154 V9 '+side},201)['id'];teams.append(team)
    for number in [1,2]:
        player=req('/api/v1/players','POST',{'firstName':'Synthetic'+side,'lastName':'Auth154-'+str(number)},201)['id'];players.append(player)
        req(f'/api/v1/teams/{team}/players/{player}','PUT',{'number':number})
photo_results=[photo(f'/api/v1/players/{players[0]}/photo'),photo(f'/api/v1/teams/{teams[0]}/photo')]
matches=[]
for finished in [False,True]:
    match=req('/api/v1/matches','POST',{'teamIds':teams,'playersPerTeam':2},201)
    mid=match['id'];req(f'/api/v1/matches/{mid}/start','POST',{'timestamp':'2026-11-01T10:00:00Z'})
    current=req(f'/api/v1/matches/{mid}');actors=current['teams'][0]['participants']
    events=[{'eventId':str(uuid.uuid4()),'type':kind,'occurredAt':'2026-11-01T10:00:00.123456Z',
        'participantId':actors[0]['participantId']} for kind in ['PULL','PICKUP']]
    if finished:
        events.append({'eventId':str(uuid.uuid4()),'type':'GOAL','occurredAt':'2026-11-01T10:00:01.123456Z',
            'fromParticipantId':actors[0]['participantId'],'toParticipantId':actors[1]['participantId']})
    batch=req(f'/api/v2/matches/{mid}/events/batch','POST',{'expectedLastSequenceNumber':0,'expectedLogRevision':0,'events':events},201)
    if finished:
        req(f'/api/v1/matches/{mid}/end','POST',{'timestamp':'2026-11-01T10:00:02Z'})
        receipt=None
    else:
        receipt=req(f'/api/v2/matches/{mid}/events/undo','POST',{'commandId':str(uuid.uuid4()),
            'expectedLastEventId':events[-1]['eventId'],'expectedLogRevision':batch['logRevision']})
    profile={'swapped':True,'shortNames':{teams[0]:'SYN-A'},'teamColors':{teams[1]:'#123456'}}
    req(f'/api/v1/obs/scoreboard/{mid}/profile','PUT',profile)
    matches.append({'id':mid,'batch':batch,'undoReceipt':receipt,'match':req(f'/api/v1/matches/{mid}'),
        'log':req(f'/api/v2/matches/{mid}/events'),'scoreboard':req(f'/api/v1/obs/scoreboard/{mid}')})
req('/api/v1/obs/config','PUT',{'maxRows':7,'ttlSeconds':12,'teamColors':{teams[0]:'#654321'}})
state={'sourcePhase':'247/V9','teams':teams,'players':players,'matches':matches,'photos':photo_results,
    'pngSha256':hashlib.sha256(png()).hexdigest(),'obsConfig':req('/api/v1/obs/config')}
p=Path(output);p.parent.mkdir(parents=True,exist_ok=True);p.write_text(json.dumps(state,indent=2)+'\n');p.chmod(0o600)
print('seeded OWNED247/V9 sports/photos/batch+undo receipts/OBS fixture')
