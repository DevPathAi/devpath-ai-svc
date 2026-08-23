#!/usr/bin/env python3
"""Loopback-only TLS reverse proxy for the protected Ollama release evaluator."""

from __future__ import annotations

import argparse
import http.client
import json
import ssl
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Mapping
from urllib.parse import urlsplit


LOOPBACK_ADDRESS = "127.0.0.1"
MAX_REQUEST_BODY_BYTES = 1_048_576
ALLOWED_ROUTES = frozenset({("GET", "/api/tags"), ("POST", "/api/chat")})


def validate_route(method: str, target: str) -> str:
    parsed = urlsplit(target)
    if (
        parsed.scheme
        or parsed.netloc
        or parsed.query
        or parsed.fragment
        or (method, parsed.path) not in ALLOWED_ROUTES
    ):
        raise ValueError("request route is not allowed")
    return parsed.path


def parse_content_length(method: str, headers: Mapping[str, str]) -> int:
    normalized = {key.lower(): value for key, value in headers.items()}
    if normalized.get("transfer-encoding"):
        raise ValueError("transfer encoding is not allowed")
    raw_length = normalized.get("content-length")
    if method == "POST" and raw_length is None:
        raise ValueError("content length is required")
    if raw_length is None:
        return 0
    try:
        length = int(raw_length, 10)
    except ValueError as failure:
        raise ValueError("content length is invalid") from failure
    if length < 0 or length > MAX_REQUEST_BODY_BYTES:
        raise ValueError("content length is outside the allowed range")
    if method != "POST" and length != 0:
        raise ValueError("request body is not allowed")
    return length


def validate_server_identity(
    host: str, port: int, upstream_host: str, upstream_port: int
) -> None:
    if host != LOOPBACK_ADDRESS or upstream_host != LOOPBACK_ADDRESS:
        raise ValueError("listener and upstream must use the IPv4 loopback address")
    if not (1 <= port <= 65_535 and 1 <= upstream_port <= 65_535):
        raise ValueError("listener and upstream ports must be valid")
    if port == upstream_port:
        raise ValueError("listener and upstream ports must be distinct")


class OllamaTlsProxyHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    upstream_host = LOOPBACK_ADDRESS
    upstream_port = 11434

    def do_GET(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler API
        self._proxy("GET")

    def do_POST(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler API
        self._proxy("POST")

    def _proxy(self, method: str) -> None:
        try:
            path = validate_route(method, self.path)
            length = parse_content_length(method, self.headers)
            body = self.rfile.read(length) if length else None
            if body is not None and len(body) != length:
                raise ValueError("request body ended before its declared length")
        except ValueError:
            self._send_json_error(400, "invalid request")
            return

        headers = {"Accept": self.headers.get("Accept", "application/json")}
        if body is not None:
            headers["Content-Type"] = self.headers.get(
                "Content-Type", "application/json"
            )

        connection = http.client.HTTPConnection(
            self.upstream_host, self.upstream_port, timeout=130
        )
        response_started = False
        try:
            connection.request(method, path, body=body, headers=headers)
            response = connection.getresponse()
            self.send_response(response.status)
            content_type = response.getheader("Content-Type")
            if content_type:
                self.send_header("Content-Type", content_type)
            self.send_header("Connection", "close")
            self.end_headers()
            response_started = True
            while True:
                chunk = response.read(65_536)
                if not chunk:
                    break
                self.wfile.write(chunk)
                self.wfile.flush()
        except (OSError, http.client.HTTPException):
            if not response_started:
                self._send_json_error(502, "upstream unavailable")
        finally:
            connection.close()
            self.close_connection = True

    def _send_json_error(self, status: int, message: str) -> None:
        payload = json.dumps({"error": message}, separators=(",", ":")).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(payload)
        self.close_connection = True

    def log_message(self, format: str, *args: object) -> None:
        return


def serve(
    host: str,
    port: int,
    upstream_host: str,
    upstream_port: int,
    certificate: Path,
    private_key: Path,
) -> None:
    validate_server_identity(host, port, upstream_host, upstream_port)
    if not certificate.is_file() or not private_key.is_file():
        raise ValueError("TLS certificate and private key must be regular files")

    handler = type(
        "ConfiguredOllamaTlsProxyHandler",
        (OllamaTlsProxyHandler,),
        {"upstream_host": upstream_host, "upstream_port": upstream_port},
    )
    server = ThreadingHTTPServer((host, port), handler)
    server.daemon_threads = True
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.minimum_version = ssl.TLSVersion.TLSv1_2
    context.load_cert_chain(certificate, private_key)
    server.socket = context.wrap_socket(server.socket, server_side=True)
    server.serve_forever()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", required=True)
    parser.add_argument("--port", required=True, type=int)
    parser.add_argument("--upstream-host", required=True)
    parser.add_argument("--upstream-port", required=True, type=int)
    parser.add_argument("--certificate", required=True, type=Path)
    parser.add_argument("--private-key", required=True, type=Path)
    arguments = parser.parse_args()
    serve(
        arguments.host,
        arguments.port,
        arguments.upstream_host,
        arguments.upstream_port,
        arguments.certificate,
        arguments.private_key,
    )


if __name__ == "__main__":
    main()
