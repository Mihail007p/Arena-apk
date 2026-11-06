"""Tiny local server that serves Arena.apk with the correct download filename.

    python3 tools/serve_apk.py            # http://0.0.0.0:8080/Arena.apk
    PORT=9000 python3 tools/serve_apk.py

Only for local testing / LAN installs. Do not expose to the public internet.
"""
import http.server
import os
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
APK = ROOT / "Arena.apk"
PORT = int(os.environ.get("PORT", "8080"))


class Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path.split("?")[0] in ("/", "/Arena.apk", "/arena.apk"):
            self._send(APK, "application/vnd.android.package-archive",
                       'attachment; filename="Arena.apk"')
        elif self.path == "/Arena.apk.sha256.txt":
            sha = ROOT / "Arena.apk.sha256.txt"
            self._send(sha, "text/plain", None)
        else:
            self.send_error(404)

    def _send(self, path, mime, disposition):
        if not path.is_file():
            self.send_error(404, f"{path.name} not found — run ./build.sh first")
            return
        data = path.read_bytes()
        self.send_response(200)
        self.send_header("Content-Type", mime)
        self.send_header("Content-Length", str(len(data)))
        if disposition:
            self.send_header("Content-Disposition", disposition)
        self.end_headers()
        self.wfile.write(data)


if __name__ == "__main__":
    print(f"Serving {APK} on http://0.0.0.0:{PORT}/Arena.apk")
    http.server.ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
