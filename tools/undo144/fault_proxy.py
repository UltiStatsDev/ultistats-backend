"""Synthetic loopback-only response-loss control. Each incoming POST forwards once.

Drops/truncates the first real upstream successful response AFTER fully reading it.
No retry loop and no fabricated successful API responses. The fixture file scopes writes.
"""
import argparse
import hashlib
import http.client
import http.server
import json
import pathlib
import socket
import threading
import time
from urllib.parse import urlsplit

parser = argparse.ArgumentParser()
parser.add_argument('--fixture', required=True)
parser.add_argument('--out', required=True)
parser.add_argument('--mode', choices=['headerless', 'partial'], required=True)
parser.add_argument('--api-port', type=int, default=19844)
parser.add_argument('--port', type=int, default=19845)
args = parser.parse_args()
fixture = json.loads(pathlib.Path(args.fixture).read_text())
out = pathlib.Path(args.out)
out.mkdir(parents=True, exist_ok=True)
undo = f"/api/v2/matches/{fixture['matchId']}/events/undo"
mutex = threading.Lock()
dropped = False


class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'

    def log_message(self, *_):
        pass

    def record(self, value):
        value.update(clientPort=self.client_address[1], at=time.time(), requestVersion=self.request_version)
        with mutex:
            with (out / 'incoming.jsonl').open('a') as stream:
                stream.write(json.dumps(value) + '\n')

    def run_request(self):
        global dropped
        path = urlsplit(self.path).path
        if self.command == 'GET' and path == '/control':
            html = pathlib.Path(__file__).with_name('native-fetch.html').read_text()
            data = html.replace('__FIXTURE__', json.dumps(fixture)).encode()
            self.send_response(200)
            self.send_header('Content-Type', 'text/html; charset=utf-8')
            self.send_header('Content-Length', str(len(data)))
            self.end_headers()
            self.wfile.write(data)
            return
        body = self.rfile.read(int(self.headers.get('Content-Length', '0')))
        if self.command != 'GET' and (self.command != 'POST' or path != undo or json.loads(body) != fixture['command']):
            self.send_error(403, 'Only registered synthetic undo envelope may mutate')
            return
        connection = http.client.HTTPConnection('127.0.0.1', args.api_port, timeout=15)
        try:
            headers = {'Content-Type': 'application/json'} if body else {}
            connection.request(self.command, self.path, body=body or None, headers=headers)
            response = connection.getresponse()
            data = response.read()
            entry = dict(method=self.command, path=self.path, status=response.status,
                         responseSha256=hashlib.sha256(data).hexdigest())
            if body:
                entry['requestBody'] = json.loads(body)
            with mutex:
                inject = self.command == 'POST' and path == undo and response.status == 200 and not dropped
                if inject:
                    dropped = True
            if inject:
                (out / 'real-committed-outcome.json').write_bytes(data)
                entry['injected'] = args.mode
                if args.mode == 'partial':
                    self.send_response(response.status)
                    self.send_header('Content-Type', response.getheader('Content-Type'))
                    self.send_header('Content-Length', str(len(data)))
                    self.end_headers()
                    self.wfile.write(data[:max(1, len(data) // 2)])
                    self.wfile.flush()
                    time.sleep(.05)
                self.record(entry)
                self.close_connection = True
                self.connection.shutdown(socket.SHUT_RDWR)
                self.connection.close()
                return
            self.send_response(response.status)
            for name in ['Content-Type', 'Cache-Control']:
                if response.getheader(name):
                    self.send_header(name, response.getheader(name))
            self.send_header('Content-Length', str(len(data)))
            self.end_headers()
            self.wfile.write(data)
            self.record(entry)
        except (BrokenPipeError, ConnectionResetError):
            pass
        finally:
            connection.close()

    do_GET = do_POST = do_PUT = do_PATCH = do_DELETE = run_request


server = http.server.ThreadingHTTPServer(('127.0.0.1', args.port), Handler)
print(json.dumps(dict(port=args.port, ownedMatch=fixture['matchId'], mode=args.mode)), flush=True)
server.serve_forever()
