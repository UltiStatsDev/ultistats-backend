"""Owned native Caddy routing/TLS/source test; no auth/browser/OBS acceptance claim.
Requires the exact Caddy binary and source LAN Caddyfile; never installs trust.
"""
import argparse
import contextlib
import http.client
import http.server
import hashlib
import json
import os
from pathlib import Path
import socket
import ssl
import subprocess
import tempfile
import threading
import time

p = argparse.ArgumentParser()
p.add_argument('--caddy', required=True)
p.add_argument('--config', required=True)
p.add_argument('--output', required=True)
a = p.parse_args()
ports = {'public': 21154, 'private': 21155, 'anonymous': 21156, 'tls': 24154}

class Probe(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        body = json.dumps({'upstream': self.server.label, 'clientIp': self.headers.get('X-UltiStats-Client-IP'), 'path': self.path}).encode()
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)
    def log_message(self, *_):
        pass

with contextlib.ExitStack() as stack:
    # Reserve/check every external test listener before any child is started.
    for port in [ports['public'], ports['tls']]:
        with socket.socket() as sock:
            sock.bind(('127.0.0.1', port))
    servers = []
    for label, port in [('ANONYMOUS', ports['anonymous']), ('SECURE', ports['private']), ('WEB', 0)]:
        server = http.server.ThreadingHTTPServer(('127.0.0.1', port), Probe)
        server.label = label
        stack.callback(server.server_close)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        stack.callback(server.shutdown)
        servers.append(server)
    root = Path(stack.enter_context(tempfile.TemporaryDirectory(prefix='auth154-caddy-')))
    root.chmod(0o700)
    env = dict(os.environ, PUBLIC_HOST='127.0.0.1', STAFF_HOST='127.0.0.1',
        CADDY_HTTP_PORT=str(ports['public']), CADDY_HTTPS_PORT=str(ports['tls']),
        ANONYMOUS_UPSTREAM=f"127.0.0.1:{ports['anonymous']}", PRIVATE_UPSTREAM=f"127.0.0.1:{ports['private']}",
        WEB_UPSTREAM=f'127.0.0.1:{servers[2].server_port}', XDG_DATA_HOME=str(root/'data'), XDG_CONFIG_HOME=str(root/'config'))
    log = stack.enter_context((root/'caddy.log').open('w'))
    child = subprocess.Popen([a.caddy, 'run', '--config', str(Path(a.config).resolve()), '--adapter', 'caddyfile'], env=env, stdout=log, stderr=log)
    def stop():
        if child.poll() is None:
            child.terminate()
            try:
                child.wait(timeout=8)
            except subprocess.TimeoutExpired:
                child.kill()
                child.wait(timeout=3)
    stack.callback(stop)
    def request(path, *, tls=False, context=None, source='127.0.0.1'):
        kind = http.client.HTTPSConnection if tls else http.client.HTTPConnection
        kwargs = {'context': context} if tls else {}
        connection = kind('127.0.0.1', ports['tls'] if tls else ports['public'], timeout=3, source_address=(source, 0), **kwargs)
        try:
            connection.request('GET', path, headers={'X-UltiStats-Client-IP': '198.51.100.99', 'X-Forwarded-For': '198.51.100.98', 'X-Forwarded-Proto': 'https'})
            response = connection.getresponse()
            body = response.read()
            assert response.status == 200, (path, response.status, body[:200])
            return json.loads(body)
        finally:
            connection.close()
    deadline = time.monotonic()+15
    while True:
        assert child.poll() is None, 'owned Caddy exited during startup: '+(root/'caddy.log').read_text()[-4000:]
        try:
            guest = request('/api/v1/installation')
            break
        except (OSError, http.client.HTTPException):
            if time.monotonic() >= deadline:
                raise
            time.sleep(.1)
    assert guest == {'upstream':'ANONYMOUS', 'clientIp':None, 'path':'/api/v1/installation'}, guest
    assert request('/matches')['upstream'] == 'WEB'
    for route in ['/obs-battle-log.html', '/obs-scoreboard.html', '/obs-admin.html', '/obs/admin', '/health/ready', '/uploads/synthetic.png']:
        assert request(route)['upstream'] == 'ANONYMOUS', ('public backend route not routed to anonymous connector', route)
    try:
        request('/api/v1/installation', tls=True, context=ssl.create_default_context())
    except ssl.SSLCertVerificationError:
        pass
    else:
        raise AssertionError('untrusted local CA was accepted')
    ca = root/'data/caddy/pki/authorities/local/root.crt'
    trusted = ssl.create_default_context(cafile=str(ca))
    ca_hash = hashlib.sha256(ca.read_bytes()).hexdigest()
    try:
        with socket.create_connection(('127.0.0.1', ports['tls']), timeout=3) as raw:
            with trusted.wrap_socket(raw, server_hostname='wrong.example.test'):
                pass
    except ssl.SSLError:
        # Caddy can reject unknown SNI before a certificate is presented; this
        # remains a failed TLS connection, never a certificate-validation bypass.
        pass
    else:
        raise AssertionError('wrong hostname was accepted under trusted CA')
    assert request('/matches', tls=True, context=trusted)['upstream'] == 'WEB'
    assert request('/uploads/synthetic.png', tls=True, context=trusted)['upstream'] == 'SECURE'
    sources = []
    for source in ['127.0.0.2', '127.0.0.3']:
        result = request('/api/v1/installation', tls=True, context=trusted, source=source)
        assert result == {'upstream':'SECURE', 'clientIp':source, 'path':'/api/v1/installation'}, result
        sources.append(result['clientIp'])
    assert len(set(sources)) == 2, sources
    stop()
    assert child.returncode == 0, child.returncode
    child = subprocess.Popen([a.caddy, 'run', '--config', str(Path(a.config).resolve()), '--adapter', 'caddyfile'], env=env, stdout=log, stderr=log)
    deadline = time.monotonic()+15
    while True:
        assert child.poll() is None, 'Caddy exited on retained-state restart'
        try:
            assert request('/api/v1/installation', tls=True, context=trusted)['upstream'] == 'SECURE'
            break
        except (OSError, http.client.HTTPException):
            if time.monotonic() >= deadline:
                raise
            time.sleep(.1)
    assert hashlib.sha256(ca.read_bytes()).hexdigest() == ca_hash, 'restart replaced the CA state'
    stop()
    assert child.returncode == 0, child.returncode
    Path(a.output).write_text(json.dumps({'status':'PASS', 'scope':'real Caddy routing/TLS/verified source only; not auth or browser/native OBS',
        'ports':ports, 'guestHttp':'ANONYMOUS, no TLS/CA', 'untrustedTLS':'rejected', 'trustedTLS':'explicit per-request CA accepted',
        'twoRealClientSources':sources, 'forgedSource':'overwritten by proxy', 'wrongHostname':'rejected', 'certificateStateRestart':'retained CA; trusted handshake succeeds', 'globalTrustChanged':False, 'ownedChildStopped':True}, indent=2)+'\n')
print('Caddy routing/TLS and two real source addresses PASS; own child stopped')
