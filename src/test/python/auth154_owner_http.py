"""Staged OWNED auth HTTPS proof; secret input and cookie jar remain private.
Usage: auth154_owner_http.py initial|resume|recovery /tmp/ultistats-auth154
Between initial/resume restart the owned API; before recovery run owner CLI recover.
"""
import json
from pathlib import Path
import secrets
import sys
import uuid
from auth154_client import Client

phase,root=sys.argv[1:]
r=Path(root).resolve();assert r==Path('/tmp/ultistats-auth154')
client=Client('127.0.0.5',str(r/'runtime-caddy-data/caddy/pki/authorities/local/root.crt'))
context_file=r/'owner-context.json';cookie_file=r/'owner-private.cookies'
def expected(context):
    return {'X-UltiStats-Installation-Id':context['installationId'],'X-UltiStats-Account-Id':context['account']['id'],'X-UltiStats-Session-Id':context['sessionId']}
def mutation(path,body,context):
    return client.request(path,'POST',body,headers={**expected(context),'X-CSRF-TOKEN':client.token})
def save(context):
    # A private native Cookie jar, never Web Storage/API JSON or public evidence.
    cookie_file.write_text('\n'.join(k+'='+v for k,v in client.cookies.items())+'\n');cookie_file.chmod(0o600)
    context_file.write_text(json.dumps(context,indent=2)+'\n');context_file.chmod(0o600)
def login(password,installation):
    status,body,headers=client.request('/api/v1/auth/login','POST',{'login':'synthetic154','password':password},
        headers={'X-UltiStats-Installation-Id':installation,'X-CSRF-TOKEN':client.token})
    assert status==200,(status,body.get('code'))
    cookie=next(v for k,v in headers if k.lower()=='set-cookie' and v.startswith('__Host-ULTISTATS_SESSION='))
    assert all(flag in cookie for flag in ['Secure','HttpOnly','SameSite=Lax','Path=/']) and 'Domain=' not in cookie
    return body
if phase=='initial':
    status,info,_=client.request('/api/v1/installation');assert status==200 and info['entryMode']=='SECURE'
    client.csrf();context=login((r/'owner-password').read_text().rstrip('\n'),info['installationId'])
    assert context['state']=='PASSWORD_CHANGE_REQUIRED' and context['permissions']==[]
    client.csrf(expected(context))
    status,denied,_=mutation('/api/v1/teams',{'name':'MUST-NOT-CREATE-TEMP'},context)
    assert status==403 and denied['code']=='AUTH_PASSWORD_CHANGE_REQUIRED' and denied['requestRejectedBeforeExecution'] is True
    password=secrets.token_urlsafe(32);p=r/'owner-permanent-password';assert not p.exists();p.write_text(password+'\n');p.chmod(0o600)
    status,changed,_=mutation('/api/v1/auth/password',{'currentPassword':(r/'owner-password').read_text().rstrip('\n'),'newPassword':password},context)
    assert status==200 and changed['state']=='AUTHENTICATED'
    assert expected(changed)==expected(context),'own password change altered stable owner/session identity'
    wrong=Client('127.0.0.5',str(r/'runtime-caddy-data/caddy/pki/authorities/local/root.crt'))
    wrong.cookies={k:v for k,v in client.cookies.items() if k=='__Host-ULTISTATS_SESSION'}
    headers={**expected(changed),'X-UltiStats-Account-Id':str(uuid.uuid4())}
    for route,method,body in [('/api/v1/auth/csrf','GET',None),('/api/v1/auth/renew','POST',{'expectedSessionId':changed['sessionId']})]:
        status,denied,response_headers=wrong.request(route,method,body,headers=headers)
        assert status==403 and denied['code']=='AUTH_IDENTITY_MISMATCH',(route,status,denied.get('code'))
        assert not any(k.lower()=='set-cookie' for k,v in response_headers),'mismatch materialized a cookie before rejection'
    save(changed)
    receipt={'phase':phase,'status':'PASS','temporarySportsWrite':'403 before execution','passwordChange':'HTTPS masked CSRF accepted; same logical owner/session',
        'wrongCapturedOwnerNoCsrfCookie':'GETcsrf+POSTrenew403 identity mismatch ZERO Set-Cookie','cookieFlags':'__Host Secure HttpOnly SameSite=Lax Path=/ no Domain'}
else:
    context=json.loads(context_file.read_text());client.cookies=dict(line.split('=',1) for line in cookie_file.read_text().splitlines())
    if phase=='resume':
        status,resumed,_=client.request('/api/v1/auth/session',headers=expected(context))
        assert status==200 and expected(resumed)==expected(context) and resumed['lastActivityAt']==context['lastActivityAt']
        client.csrf(expected(context));status,logout,_=mutation('/api/v1/auth/logout',{'expectedSessionId':context['sessionId']},context)
        assert status==200 and logout=={'sessionId':context['sessionId'],'status':'REVOKED'}
        status,denied,headers=client.request('/api/v1/auth/session',headers=expected(context))
        assert status==401 and denied['code']=='AUTH_SESSION_REVOKED'
        assert not any(k.lower()=='set-cookie' for k,v in headers)
        client.csrf();new=login((r/'owner-permanent-password').read_text().rstrip('\n'),context['installationId'])
        assert new['account']['id']==context['account']['id'] and new['sessionId']!=context['sessionId']
        save(new)
        receipt={'phase':phase,'status':'PASS','restart':'same persistent logical session/account/activity','logout':'exact REVOKED, old cookie denied without clearing',
            'reloginWithRevokedCookie':'bootstrap CSRF and new login succeed; new logical session same account'}
    else:
        assert phase=='recovery'
        status,denied,headers=client.request('/api/v1/auth/session',headers=expected(context))
        assert status==401 and denied['code']=='AUTH_SESSION_REVOKED'
        assert not any(k.lower()=='set-cookie' for k,v in headers)
        client.csrf();new=login((r/'owner-recovery-password').read_text().rstrip('\n'),context['installationId'])
        assert new['state']=='PASSWORD_CHANGE_REQUIRED' and new['account']['id']==context['account']['id']
        save(new)
        receipt={'phase':phase,'status':'PASS','recovery':'owner CLI preserved account UUID and revoked original session','recoveredLogin':'temporary password restricted; bootstrap with revoked cookie usable'}
(r/('owner-https-'+phase+'.json')).write_text(json.dumps(receipt,indent=2)+'\n')
print('actual owner HTTPS',phase,'PASS; credentials/private cookie jar not published')
