"""Private-cookie native test client. Never prints credentials or installs trust."""
import http.client
from http.cookies import SimpleCookie
import json
import ssl
class Client:
    def __init__(self,source,ca_file):
        self.source=source;self.cookies={};self.token=None;self.context=ssl.create_default_context(cafile=ca_file)
    def request(self,path,method='GET',body=None,headers=None,send_cookies=True):
        headers=dict(headers or {})
        if send_cookies and self.cookies: headers['Cookie']='; '.join(k+'='+v for k,v in self.cookies.items())
        if body is not None:
            body=json.dumps(body);headers['Content-Type']='application/json'
        conn=http.client.HTTPSConnection('127.0.0.1',24154,context=self.context,timeout=5,source_address=(self.source,0))
        try:
            conn.request(method,path,body=body,headers=headers);r=conn.getresponse();raw=r.read();response_headers=r.getheaders()
            for name,value in response_headers:
                if name.lower()=='set-cookie':
                    cookie=SimpleCookie();cookie.load(value)
                    for key,morsel in cookie.items(): self.cookies[key]=morsel.value
            try: parsed=json.loads(raw) if raw else None
            except json.JSONDecodeError: parsed={'_nonJson':raw.decode(errors='replace')[:120]}
            return r.status,parsed,response_headers
        finally:conn.close()
    def csrf(self,headers=None):
        status,body,headers=self.request('/api/v1/auth/csrf',headers=headers);assert status==200,(status,body)
        assert body['headerName']=='X-CSRF-TOKEN';self.token=body['token']
        cookie=next(value for key,value in headers if key.lower()=='set-cookie')
        assert '__Host-ULTISTATS_CSRF=' in cookie and 'Secure' in cookie and 'HttpOnly' in cookie and 'Path=/' in cookie and 'SameSite=Lax' in cookie and 'Domain=' not in cookie,cookie.split('=',1)[0]
        return headers

