#!/usr/bin/env python3
"""Snapshot annotated application routes and packaged static resources; no server needed."""
import argparse
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[2]
TARGET = ROOT / 'docs/api/auth-surfaces-v1.json'


def inventory():
    routes = []
    for source in sorted((ROOT / 'src/main/kotlin').rglob('*.kt')):
        text = source.read_text()
        if not re.search(r'@(RestController|Controller)\b', text):
            continue
        base_match = re.search(r'@RequestMapping\("([^"]*)"\)', text)
        base = base_match.group(1) if base_match else ''
        for mapping in re.finditer(r'@(Get|Post|Put|Patch|Delete)Mapping\b(?:\(([^\n]*))?', text):
            method = mapping.group(1).upper()
            suffix_match = re.match(r'"([^"]*)"', mapping.group(2) or '')
            path = base + (suffix_match.group(1) if suffix_match else '')
            owner = ('core' if path.startswith('/api/v1/auth/') or path.startswith('/api/v1/accounts') else
                     'ingress' if path in ['/api/v1/installation', '/health/ready'] else 'enforcement')
            if owner == 'core':
                permission = 'CORE'
            elif path.startswith('/swagger-ui'):
                permission = 'API_DOCS'
            elif method != 'GET':
                permission = ('TRANSFER' if path in ['/api/v1/matches/export', '/api/v1/matches/import']
                              else 'OBS_ADMIN' if path.startswith('/api/v1/obs/') else 'SPORTS_WRITE')
            elif '/statistics' in path:
                permission = 'FINISHED_OR_STATISTICS_READ_ALL'
            elif '/events/undo/' in path:
                permission = 'SPORTS_WRITE'
            else:
                permission = 'PUBLIC'
            routes.append(dict(method=method, path=path, policy=permission, owner=owner,
                               source=str(source.relative_to(ROOT)),
                               line=text.count('\n', 0, mapping.start()) + 1))
    static = []
    for source in sorted((ROOT / 'src/main/resources/static').rglob('*')):
        if source.is_file():
            path = '/' + str(source.relative_to(ROOT / 'src/main/resources/static'))
            policy = ('API_DOCS' if path.startswith('/auth/swagger') else
                      'OBS_ADMIN' if path in ['/obs-admin.html', '/obs/admin.mjs', '/obs/scoreboard-admin.mjs', '/auth/obs-session.mjs', '/auth/staff-session.mjs'] else 'PUBLIC')
            static.append(dict(path=path, policy=policy))
    return dict(inventoryVersion=1, baseline='5c9781f2895141ca7a430588873c764d8c1e2dd6',
                scope='Existing sports/OBS application mappings and packaged static resources; generated framework routes documented separately.',
                routes=routes, static=static)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    result = inventory()
    if args.check:
        if json.loads(TARGET.read_text()) != result:
            raise SystemExit('Inventory differs from source; review and regenerate explicitly.')
        print(f"Inventory matches source: {len(result['routes'])} method/path mappings, {len(result['static'])} static files")
    else:
        TARGET.write_text(json.dumps(result, indent=2) + '\n')
