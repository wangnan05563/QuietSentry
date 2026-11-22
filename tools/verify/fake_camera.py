"""假相机：给观看端 LiveView 提供一个可预测的 MJPEG 源，用于无真机条件下的 UI 验证。

用法：python tools/verify/fake_camera.py [port]
- /api/live       multipart/x-mixed-replace，2fps，640x480，画面带帧号
- /api/snapshot   单帧 JPEG
- /api/pause、/api/siren、/api/torch、/api/talk 等返回 200 JSON
- 令牌固定 test-token；不匹配返回 401，用于验证“令牌失效”状态
"""
import io
import json
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from PIL import Image, ImageDraw

TOKEN = "test-token"
PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 18080

_lock = threading.Lock()
_frame_no = 0


def make_frame(no: int) -> bytes:
    img = Image.new("RGB", (640, 480), (18, 24, 32))
    draw = ImageDraw.Draw(img)
    for y in range(0, 480, 40):
        draw.line([(0, y), (640, y)], fill=(30, 40, 52))
    draw.ellipse([(200, 90), (440, 330)], outline=(76, 217, 100), width=6)
    draw.text((24, 24), f"FAKE CAMERA  frame {no}", fill=(230, 235, 240))
    draw.text((24, 440), time.strftime("%H:%M:%S"), fill=(153, 170, 187))
    buf = io.BytesIO()
    img.save(buf, "JPEG", quality=80)
    return buf.getvalue()


def jpeg_now() -> bytes:
    global _frame_no
    with _lock:
        _frame_no += 1
        return make_frame(_frame_no)


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        print("[fake] " + fmt % args, flush=True)

    def _authed(self) -> bool:
        if self.headers.get("X-Auth-Token") == TOKEN:
            return True
        body = json.dumps({"error": "unauthorized"}).encode()
        self.send_response(401)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)
        return False

    def _json(self, obj, code=200):
        body = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path.startswith("/api/live"):
            if not self._authed():
                return
            self.send_response(200)
            self.send_header("Content-Type", "multipart/x-mixed-replace; boundary=frame")
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            try:
                while True:
                    data = jpeg_now()
                    self.wfile.write(b"--frame\r\n")
                    self.wfile.write(b"Content-Type: image/jpeg\r\n")
                    self.wfile.write(f"Content-Length: {len(data)}\r\n\r\n".encode())
                    self.wfile.write(data)
                    self.wfile.write(b"\r\n")
                    self.wfile.flush()
                    time.sleep(0.5)
            except (BrokenPipeError, ConnectionResetError):
                return
        elif self.path.startswith("/api/snapshot"):
            if not self._authed():
                return
            data = jpeg_now()
            self.send_response(200)
            self.send_header("Content-Type", "image/jpeg")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
            return
        elif self.path.startswith("/api/info"):
            self._json({"name": "Fake Camera", "model": "fake", "version": "0.1"})
        elif self.path.startswith("/api/status"):
            if not self._authed():
                return
            self._json({"running": True, "battery": 80, "charging": False, "watching": 1, "maxViewers": 5})
        elif self.path.startswith("/api/events"):
            if not self._authed():
                return
            self._json([])
        else:
            self._json({"ok": True})

    def do_POST(self):
        length = int(self.headers.get("Content-Length") or 0)
        if length:
            self.rfile.read(length)
        if not self._authed():
            return
        if self.path.startswith("/api/pause"):
            self._json({"ok": True})
        elif self.path.startswith(("/api/siren", "/api/torch")):
            self._json({"ok": True, "durationMs": 10000})
        elif self.path.startswith("/api/talk"):
            self._json({"ok": True})
        else:
            self._json({"ok": True})


if __name__ == "__main__":
    print(f"[fake] listening on 0.0.0.0:{PORT}, token={TOKEN}", flush=True)
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
