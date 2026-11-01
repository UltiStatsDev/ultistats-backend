"""Real owned Backend/Caddy TLS boundary test. No credentials/tokens in evidence.
Usage: auth154_http.py PRIVATE_CA_FILE OUTPUT.json
"""
import http.client
from http.cookies import SimpleCookie
import json
from pathlib import Path
import ssl
import sys
import uuid

ca,output=sys.argv[1:]
trusted=ssl.create_default_context(cafile=ca)
findings=[]
origin='https://127.0.0.1:24154'
from auth154_client import Client

initial=Client('127.0.0.4',ca)
status,info,_=initial.request('/api/v1/installation');assert status==200 and info['entryMode']=='SECURE'
assert set(info)=={'contractVersion','installationId','entryMode','publicOrigin','secureOrigin'}
# Wrong captured owner with no CSRF cookie fails before materialization, not Set-Cookie.
status,body,headers=initial.request('/api/v1/auth/csrf',headers={'X-UltiStats-Installation-Id':info['installationId'],
    'X-UltiStats-Account-Id':str(uuid.uuid4()),'X-UltiStats-Session-Id':str(uuid.uuid4())},send_cookies=False)
assert status==401 and body['code']=='AUTH_REQUIRED',(status,body)
assert not any(k.lower()=='set-cookie' for k,v in headers)
initial.csrf()
status,body,headers=initial.request('/api/v1/auth/login','POST',{'login':'absent-origin-probe','password':'invalid-synthetic'},
    headers={'X-CSRF-TOKEN':initial.token,'X-UltiStats-Installation-Id':info['installationId'],'Origin':'https://attacker.example.test'})
if not (status==403 and body.get('code')=='CSRF_INVALID'):
    findings.append({'case':'wrongOrigin','expected':'403 RFC9457 CSRF_INVALID','actualStatus':status,'actualBody':body})
assert not any(k.lower()=='set-cookie' for k,v in headers)
clients=[Client('127.0.0.2',ca),Client('127.0.0.3',ca)]
for client in clients:client.csrf()
first,second=clients
for i in range(20):
    status,body,headers=first.request('/api/v1/auth/login','POST',{'login':'absent-source-'+str(i),'password':'invalid-synthetic'},
        headers={'X-CSRF-TOKEN':first.token,'X-UltiStats-Installation-Id':info['installationId'],
            'X-UltiStats-Client-IP':'198.51.100.'+str(i),'X-Forwarded-For':'203.0.113.'+str(i)})
    assert status==401 and body['code']=='AUTH_CREDENTIALS_INVALID',(i,status,body)
status,body,headers=first.request('/api/v1/auth/login','POST',{'login':'absent-source-over','password':'invalid-synthetic'},
    headers={'X-CSRF-TOKEN':first.token,'X-UltiStats-Installation-Id':info['installationId'],'X-UltiStats-Client-IP':'192.0.2.123'})
assert status==429 and body['code']=='AUTH_RATE_LIMITED',(status,body)
assert any(k.lower()=='retry-after' for k,v in headers)
status,body,_=second.request('/api/v1/auth/login','POST',{'login':'absent-source-second','password':'invalid-synthetic'},
    headers={'X-CSRF-TOKEN':second.token,'X-UltiStats-Installation-Id':info['installationId']})
assert status==401 and body['code']=='AUTH_CREDENTIALS_INVALID',(status,body)
Path(output).write_text(json.dumps({'status':'FAIL' if findings else 'PASS','findings':findings,'scope':'actual backend/Caddy TLS CSRF/source-rate boundary; no authenticated owner flow yet',
    'installationId':info['installationId'],'secureEntry':'SECURE','cookieFlags':'host-only Secure HttpOnly SameSite=Lax Path=/','maskedCsrfRoundtrip':True,
    'wrongOrigin':'FAIL: see findings' if findings else '403 CSRF_INVALID no Set-Cookie','wrongCapturedOwnerWithoutCookie':'401 AUTH_REQUIRED no Set-Cookie',
    'sourceA':'20 generic401 then429+Retry-After despite forged source headers','sourceB':'independent401 not sourceA429',
    'trust':'explicit private CA file; verification enabled'},indent=2)+'\n')
print('actual HTTPS source-rate and CSRF checks executed; status', 'FAIL' if findings else 'PASS', 'findings',len(findings))
sys.exit(1 if findings else 0)
