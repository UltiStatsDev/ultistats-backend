"""Source/config-only contracts: OpenAPI uses Backend; the staff module uses Web.
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


def matches(route, host, path, method='GET'):
    alternatives = route.get('match')
    if alternatives is None:
        return True
    for alternative in alternatives:
        assert set(alternative) <= {'host', 'path', 'method'}, 'new matcher needs explicit contract review'
        if all(any(fnmatch.fnmatchcase(value, pattern) for pattern in patterns)
               for key, patterns in alternative.items()
               for value in [{'host': host, 'path': path, 'method': method}[key]]):
            return True
    return False


def resolve(routes, host, path, method='GET'):
    for route in routes:
        if not matches(route, host, path, method):
            continue
        for handler in route.get('handle', []):
            kind = handler['handler']
            if kind == 'reverse_proxy':
                # Preserve Web's explicit JS MIME/no-store response. Caddy must
                # neither rewrite to SPA nor stamp immutable response caching.
                assert not handler.get('rewrite'), 'proxy rewrites the request path'
                assert not handler.get('headers', {}).get('response'), 'proxy overrides Web response contract'
                return [upstream['dial'] for upstream in handler['upstreams']]
            assert kind == 'subroute', 'new rewriting/response handler needs explicit contract review'
            result = resolve(handler['routes'], host, path, method)
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


# Removing the exact YAML alias from any one site must send the request to
# Web and fail this contract. The expected connectors are independent literals.
SITES = [
    ('LAN guest', 'Caddyfile.lan', 80, 'guest.example.test', '127.0.0.1:8080'),
    ('LAN staff', 'Caddyfile.lan', 443, 'staff.example.test', '127.0.0.1:8081'),
    ('Internet staff', 'Caddyfile.internet', 443, 'staff.example.test', '127.0.0.1:8081'),
]
YAML = '/v3/api-docs.yaml'
BACKEND_CONTROLS = [
    '/v3/api-docs',
    '/v3/api-docs/swagger-config',
    '/swagger-ui.html',
    '/swagger-ui/index.html',
    '/auth/staff-session.mjs',
    '/api/v1/auth/session',
    '/uploads/example.png',
    '/health/ready',
    '/obs-admin.html',
]
WEB_CONTROLS = [
    MODULE,
    '/',
    '/index.html',
    '/assets/app.js',
    '/v3/api-docs.yaml-extra',
    '/v3/api-docs.yaml/extra',
    '/v3/api-docs.yml',
]


def assert_yaml_route(routes, host, method, upstream):
    # resolve rejects every non-subroute/non-proxy handler, including rewrites.
    # Therefore the selected proxy receives the original exact request path.
    assert resolve(routes, host, YAML, method) == [upstream], (
        method, host, YAML, 'must reach', upstream, 'without Web fallback')


def assert_adjacent_routes(routes, host, method, upstream):
    for path in BACKEND_CONTROLS:
        assert resolve(routes, host, path, method) == [upstream], (method, host, path)
    for path in WEB_CONTROLS:
        assert resolve(routes, host, path, method) == ['web:80'], (method, host, path)


def remove_yaml_alias(value):
    if isinstance(value, dict):
        for matcher in value.get('match', []):
            paths = matcher.get('path', [])
            if YAML in paths:
                paths.remove(YAML)
        for child in value.values():
            remove_yaml_alias(child)
    elif isinstance(value, list):
        for child in value:
            remove_yaml_alias(child)


def assert_yaml_contract(config, name):
    failures = []
    for label, site_config, port, host, upstream in SITES:
        if site_config != name:
            continue
        routes = routes_for(config, port)
        for method in ['GET', 'HEAD']:
            assert_adjacent_routes(routes, host, method, upstream)
            try:
                assert_yaml_route(routes, host, method, upstream)
            except AssertionError as failure:
                failures.append((label, method, str(failure)))
                print(label, method, 'exact YAML routing FAIL:', failure)
                continue
            # Exercise the oracle independently per site/method. This is a
            # mutation of actual adapted routes, never a hand-built Caddy model.
            mutation = copy.deepcopy(routes)
            remove_yaml_alias(mutation)
            assert_adjacent_routes(mutation, host, method, upstream)
            assert resolve(mutation, host, YAML, method) == ['web:80']
            try:
                assert_yaml_route(mutation, host, method, upstream)
            except AssertionError:
                pass
            else:
                raise AssertionError((label, method, 'missing-alias mutation escaped'))
            print(label, method, 'exact YAML ->', upstream, '; adjacent routes PASS; alias-removal rejected')
    return failures


yaml_failures = []
for name in ['Caddyfile.lan', 'Caddyfile.internet']:
    result = subprocess.run([a.caddy, 'adapt', '--adapter', 'caddyfile', '--config', str(ROOT / 'deploy/auth' / name)],
        env=dict(os.environ, PUBLIC_HOST='guest.example.test', STAFF_HOST='staff.example.test',
                 CADDY_HTTP_PORT='80', CADDY_HTTPS_PORT='443', CADDY_BIND='127.0.0.1',
                 ANONYMOUS_UPSTREAM='127.0.0.1:8080', PRIVATE_UPSTREAM='127.0.0.1:8081',
                 WEB_UPSTREAM='web:80'),
        capture_output=True, text=True, check=True)
    config = json.loads(result.stdout)
    lan = name.endswith('.lan')
    assert_contract(config, lan)
    yaml_failures.extend(assert_yaml_contract(config, name))
    mutation = copy.deepcopy(config)
    divert_module_to_backend(mutation)
    try:
        assert_contract(mutation, lan)
    except AssertionError:
        pass
    else:
        raise AssertionError('regression oracle missed a module-to-backend routing mutation')
    print(name, 'frozen Web module routing PASS; backend-diversion mutation rejected')
assert not yaml_failures, ('exact YAML routing failures', yaml_failures)
print('source/config checks only; no HTTP/auth/YAML-payload or runtime acceptance claimed')
