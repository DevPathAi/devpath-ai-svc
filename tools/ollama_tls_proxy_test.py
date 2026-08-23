import unittest
from http.client import HTTPConnection
from http.server import ThreadingHTTPServer
from threading import Thread
from unittest.mock import patch

from tools.ollama_tls_proxy import (
    MAX_REQUEST_BODY_BYTES,
    OllamaTlsProxyHandler,
    parse_content_length,
    validate_route,
    validate_server_identity,
)


class OllamaTlsProxyTest(unittest.TestCase):
    def test_only_release_evaluation_routes_are_allowed(self):
        self.assertEqual("/api/tags", validate_route("GET", "/api/tags"))
        self.assertEqual("/api/chat", validate_route("POST", "/api/chat"))

        for method, target in (
            ("POST", "/api/pull"),
            ("GET", "/api/tags?detail=true"),
            ("GET", "http://127.0.0.1:11434/api/tags"),
            ("POST", "/api/chat/../pull"),
        ):
            with self.subTest(method=method, target=target):
                with self.assertRaises(ValueError):
                    validate_route(method, target)

    def test_request_body_is_length_delimited_and_bounded(self):
        self.assertEqual(0, parse_content_length("GET", {}))
        self.assertEqual(18, parse_content_length("POST", {"Content-Length": "18"}))

        invalid_headers = (
            {},
            {"Content-Length": "-1"},
            {"Content-Length": "not-a-number"},
            {"Content-Length": str(MAX_REQUEST_BODY_BYTES + 1)},
            {"Content-Length": "1", "Transfer-Encoding": "chunked"},
        )
        for headers in invalid_headers:
            with self.subTest(headers=headers):
                with self.assertRaises(ValueError):
                    parse_content_length("POST", headers)

    def test_listener_and_upstream_are_loopback_only_and_distinct(self):
        self.assertIsNone(
            validate_server_identity("127.0.0.1", 11435, "127.0.0.1", 11434)
        )

        for values in (
            ("0.0.0.0", 11435, "127.0.0.1", 11434),
            ("127.0.0.1", 11435, "localhost", 11434),
            ("127.0.0.1", 11434, "127.0.0.1", 11434),
        ):
            with self.subTest(values=values):
                with self.assertRaises(ValueError):
                    validate_server_identity(*values)

    def test_upstream_failure_returns_sanitized_bad_gateway(self):
        server = ThreadingHTTPServer(("127.0.0.1", 0), OllamaTlsProxyHandler)
        thread = Thread(target=server.serve_forever, daemon=True)
        with patch("tools.ollama_tls_proxy.http.client.HTTPConnection") as upstream:
            upstream.return_value.request.side_effect = OSError(
                "sensitive upstream detail"
            )
            thread.start()
            connection = HTTPConnection("127.0.0.1", server.server_port, timeout=2)
            try:
                connection.request("GET", "/api/tags")
                response = connection.getresponse()
                payload = response.read()
            finally:
                connection.close()
                server.shutdown()
                server.server_close()
                thread.join(timeout=2)

        self.assertEqual(502, response.status)
        self.assertEqual(b'{"error":"upstream unavailable"}', payload)
        self.assertNotIn(b"sensitive upstream detail", payload)


if __name__ == "__main__":
    unittest.main()
