"""Verification-only loopback relay: count real Ollama chat calls without logging bodies.

Used by browser-security-smoke.ps1 -Batch. Not part of the Runtime or a provider.
"""
import argparse
import http.client
import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

parser = argparse.ArgumentParser()
parser.add_argument('--port', type=int, required=True)
args = parser.parse_args()
lock = threading.Lock()
chat_calls = 0
limit = 1048576


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def do_GET(self):
        if self.path == '/_smoke/counts':
            with lock:
                body = json.dumps({'chatCalls': chat_calls}).encode()
            self.respond(200, body)
        elif self.path == '/api/tags':
            self.forward()
        else:
            self.respond(404, b'{}')

    def do_POST(self):
        global chat_calls
        if self.path != '/api/chat':
            self.respond(404, b'{}')
            return
        with lock:
            chat_calls += 1
        self.forward()

    def forward(self):
        connection = http.client.HTTPConnection('127.0.0.1', 11434, timeout=130)
        try:
            length = int(self.headers.get('Content-Length', '0'))
            if length < 0 or length > limit:
                self.respond(413, b'{}')
                return
            payload = self.rfile.read(length) if length else None
            connection.request(self.command, self.path, body=payload, headers={'Content-Type': 'application/json'})
            response = connection.getresponse()
            body = response.read(limit + 1)
            self.respond(response.status if len(body) <= limit else 502, body if len(body) <= limit else b'{}')
        except (OSError, ValueError, http.client.HTTPException):
            self.respond(503, b'{}')
        finally:
            connection.close()

    def respond(self, status, body):
        try:
            self.send_response(status)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        except OSError:
            pass


ThreadingHTTPServer(('127.0.0.1', args.port), Handler).serve_forever()
