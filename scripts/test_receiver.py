#!/usr/bin/env python3
"""Loopback-only HTTPS fixture for the Android emulator integration tests."""
import hashlib
import json
import ssl
import time
from threading import Thread
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        pass

    def reply(self, code, data):
        payload = json.dumps(data).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def do_POST(self):
        expected = None if self.server.server_port == 18770 else "Bearer test-fixture-only"
        if self.headers.get("Authorization") != expected:
            return self.reply(401, {"error": "unauthorized"})
        try:
            size = int(self.headers.get("Content-Length", ""))
        except ValueError:
            return self.reply(400, {"error": "expected_length"})
        if not 0 < size <= 1024 * 1024:
            return self.reply(413, {"error": "invalid_size"})
        data = self.rfile.read(size)
        digest = hashlib.sha256(data).hexdigest()
        ident = self.headers.get("Idempotency-Key", "")
        if not ident or len(data) != size or self.headers.get("X-Audio-SHA256") != digest:
            return self.reply(400, {"error": "invalid_recording"})
        if self.path == "/temporary-error":
            return self.reply(503, {"error": "fixture_temporary_failure"})
        if self.path == "/deleted":
            return self.reply(410, {"error": "recording_deleted", "import_key": ident, "audio_sha256": digest})
        if self.path == "/slow-ack":
            time.sleep(2)
        elif self.path not in ("/v1/recordings", "/wrong-id", "/wrong-ack", "/queued-ack"):
            return self.reply(404, {"error": "unknown_route"})
        self.reply(202 if self.path == "/queued-ack" else 201, {
            "status": "accepted",
            "import_key": "wrong-id" if self.path == "/wrong-id" else ident,
            "audio_sha256": "0" * 64 if self.path == "/wrong-ack" else digest,
        })


if __name__ == "__main__":
    tls_dir = Path(__file__).resolve().parents[1] / ".local" / "tls"
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.load_cert_chain(tls_dir / "cert.pem", tls_dir / "key.pem")
    with (ThreadingHTTPServer(("127.0.0.1", 18769), Handler) as primary,
          ThreadingHTTPServer(("127.0.0.1", 18770), Handler) as secondary):
        for server in (primary, secondary):
            server.socket = context.wrap_socket(server.socket, server_side=True)
        Thread(target=secondary.serve_forever, daemon=True).start()
        try:
            print("HTTPS test receivers ready on loopback.", flush=True)
            primary.serve_forever()
        finally:
            secondary.shutdown()
