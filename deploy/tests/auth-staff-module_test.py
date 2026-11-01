"""Source/config-only contract: frozen staff module stays on the Web upstream.
Caddy adapt parses configuration only; no listeners, TLS provisioning or Docker.
"""
import argparse
import copy
import fnmatch
import json
import os
from pathlib import Path
import subprocess

MODULE = '/staff-auth-v1.mjs'
ROOT = Path(__file__).resolve().parents[2]
p = argparse.ArgumentParser()
p.add_argument('--caddy', required=True, help='explicit existing Caddy binary; never installed by this test')
a = p.parse_args()


def matches(route, host, path):
    alternatives = route.get('match')
    if alternatives is None:
        return True
    for alternative in alternatives:
        assert set(alternative) <= {'host', 'path'}, 'new matcher needs explicit contract review'
        if all(any(fnmatch.fnmatchcase(value, pattern) for pattern in patterns)
               for key, patterns in alternative.items()
               for value in [host if key == 'host' else path]):
            return True
    return False


def resolve(routes, host, path):
    for route in routes:
        if not matches(route, host, path):
            continue
        for handler in route.get('handle', []):
            kind = handler['handler']
            if kind == 'reverse_proxy':
                # Preserve Web's explicit JS MIME/no-store response. Caddy must
                # neither rewrite to SPA nor stamp immutable response caching.
                assert not handler.get('headers', {}).get('response'), 'proxy overrides Web response contract'
                return [upstream['dial'] for upstream in handler['upstreams']]
            assert kind == 'subroute', 'new rewriting/response handler needs explicit contract review'
            result = resolve(handler['routes'], host, path)
            if result is not None:
                return result
    return None


def routes_for(config, port):
    servers = [s for s in config['apps']['http']['servers'].values()
               if any(address.endswith(':' + str(port)) for address in s['listen'])]
    assert len(servers) == 1, ('unexpected listener config', port)
    return servers[0]['routes']


def assert_contract(config, lan):
    secure = routes_for(config, 443)
    assert resolve(secure, 'staff.example.test', MODULE) == ['web:80'], 'staff module was diverted away from Web'
    assert resolve(secure, 'staff.example.test', '/auth/staff-session.mjs') == ['127.0.0.1:8081'], 'backend adapter routing changed'
    assert resolve(secure, 'staff.example.test', '/api/v1/auth/session') == ['127.0.0.1:8081']
    if lan:
        public = routes_for(config, 80)
        assert resolve(public, 'guest.example.test', MODULE) == ['web:80']
        assert resolve(public, 'guest.example.test', '/api/v1/installation') == ['127.0.0.1:8080']


def divert_module_to_backend(value):
    if isinstance(value, dict):
        for matcher in value.get('match', []):
            paths = matcher.get('path', [])
            if '/auth/*' in paths:
                paths.append(MODULE)
        for child in value.values():
            divert_module_to_backend(child)
    elif isinstance(value, list):
        for child in value:
            divert_module_to_backend(child)


for name in ['Caddyfile.lan', 'Caddyfile.internet']:
    result = subprocess.run([a.caddy, 'adapt', '--adapter', 'caddyfile', '--config', str(ROOT / 'deploy/auth' / name)],
        env=dict(os.environ, PUBLIC_HOST='guest.example.test', STAFF_HOST='staff.example.test'),
        capture_output=True, text=True, check=True)
    config = json.loads(result.stdout)
    lan = name.endswith('.lan')
    assert_contract(config, lan)
    mutation = copy.deepcopy(config)
    divert_module_to_backend(mutation)
    try:
        assert_contract(mutation, lan)
    except AssertionError:
        pass
    else:
        raise AssertionError('regression oracle missed a module-to-backend routing mutation')
    print(name, 'frozen Web module routing PASS; backend-diversion mutation rejected')
print('source/config checks only; Web103 owns actual JS MIME/no-store/no-SPA serving')
