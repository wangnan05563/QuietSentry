#!/usr/bin/env python3
"""
QuietSentry mock 相机端服务（T04759 多设备 2×2 网格走查用）。

在 mock-camera.py 基础上增强：
  1. 新增第 4 台在线设备 cam-night（tok-night），凑齐 4 路并发拉流。
  2. 新增 /api/live MJPEG 端点：每台设备返回一个纯色 JPEG 帧流（颜色不同，
     走查时可直接从画面颜色分辨 4 个格子是否各拉到了各自的流，避免张冠李戴）。

设备 ID → 状态/颜色映射：
  cam-guard    → GUARDING（mode=guard）    → 深蓝帧
  cam-alert    → ALERT（最近 1 分钟有事件） → 红色帧
  cam-lowbat   → LOW_BATTERY（battery=15）  → 黄色帧
  cam-night    → GUARDING（mode=guard）    → 绿色帧（第 4 台在线，凑 2×2）

MJPEG 帧协议（对齐 MjpegClient.readFrame）：
  --frame\r\n
  Content-Type: image/jpeg\r\n
  Content-Length: <N>\r\n
  X-Timestamp: <ms>\r\n
  \r\n
  <N 字节 JPEG>

  帧率约 2fps，足够网格走查看清 4 路并发与聚焦/返回。

通过 adb reverse tcp:8443 让模拟器 127.0.0.1:8443 可达。
"""
import http.server
import io
import json
import time
import threading
import socketserver

from PIL import Image

PORT = 8443  # 通过 adb reverse tcp:8443 到宿主机 127.0.0.1:8443

# 每台在线设备一个帧颜色（RGB）
COLORS = {
    "tok-guard": (30, 60, 140),    # 深蓝
    "tok-alert": (200, 40, 40),    # 红
    "tok-lowbat": (220, 180, 40),  # 黄
    "tok-night": (40, 160, 80),    # 绿
}

# 按 token 路由（与 mock-camera.py 一致，新增 tok-night）
CAMERAS = {
    "tok-guard": {
        "id": "cam-guard", "name": "Guard Cam", "port": PORT,
        "mode": "guard", "battery": 88, "charging": True,
        "thermal": 0, "storageFreeMb": 4096,
        "watching": 1, "maxViewers": 5,
        "lastEventAt": 0, "heartbeatAgeSec": 30,
    },
    "tok-alert": {
        "id": "cam-alert", "name": "Alert Cam", "port": PORT,
        "mode": "guard", "battery": 95, "charging": False,
        "thermal": 0, "storageFreeMb": 2048,
        "watching": 0, "maxViewers": 5,
        "lastEventAt": -60, "heartbeatAgeSec": 30,
    },
    "tok-lowbat": {
        "id": "cam-lowbat", "name": "LowBat Cam", "port": PORT,
        "mode": "idle", "battery": 15, "charging": False,
        "thermal": 0, "storageFreeMb": 8192,
        "watching": 0, "maxViewers": 5,
        "lastEventAt": 0, "heartbeatAgeSec": 30,
    },
    "tok-night": {
        "id": "cam-night", "name": "Night Cam", "port": PORT,
        "mode": "guard", "battery": 70, "charging": True,
        "thermal": 0, "storageFreeMb": 3072,
        "watching": 1, "maxViewers": 5,
        "lastEventAt": 0, "heartbeatAgeSec": 30,
    },
}


def make_frame(rgb, size=(128, 96)):
    """生成一张纯色 JPEG 帧字节。"""
    img = Image.new("RGB", size, rgb)
    buf = io.BytesIO()
    img.save(buf, format="JPEG", quality=70)
    return buf.getvalue()


class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        print(f"[{self.command}] {fmt % args}", flush=True)

    def _json(self, obj, code=200):
        body = json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _token(self):
        return self.headers.get("X-Auth-Token", "").strip()

    def _camera(self):
        return CAMERAS.get(self._token())

    def do_GET(self):
        token = self._token()
        now_ms = int(time.time() * 1000)
        cam = CAMERAS.get(token)

        # /api/live：MJPEG 帧流（走查网格 4 路并发）
        if self.path.startswith("/api/live"):
            if cam is None:
                self._json({"error": "unauthorized"}, code=401)
                return
            self._stream_live(cam, now_ms)
            return

        if cam is None:
            self._json({"error": "unauthorized"}, code=401)
            return

        if self.path.startswith("/api/info"):
            last_event_at = now_ms + cam.get("lastEventAt", 0) * 1000
            self._json({
                "id": cam["id"], "name": cam["name"], "port": PORT,
                "tlsPort": 0, "tlsFingerprint": "",
                "mode": cam["mode"], "lastEventAt": last_event_at,
            })

        elif self.path.startswith("/api/status"):
            last_event_at = now_ms + cam.get("lastEventAt", 0) * 1000
            resp = {
                "mode": cam["mode"], "battery": cam["battery"],
                "charging": cam["charging"], "thermal": cam["thermal"],
                "storageFreeMb": cam["storageFreeMb"],
                "watching": cam["watching"], "maxViewers": cam["maxViewers"],
                "lastEventAt": last_event_at,
                "heartbeatAt": now_ms - cam.get("heartbeatAgeSec", 30) * 1000,
                "serverTime": now_ms, "throttled": False,
                "paused": False, "masked": False,
            }
            self._json(resp)

        elif self.path.startswith("/api/events"):
            self._json({"events": []})

        else:
            self._json({"error": "not_found"}, code=404)

    def _stream_live(self, cam, now_ms):
        """推 MJPEG 帧流：约 2fps，推 60 帧后断开让客户端进入 RETRY 再重连。"""
        frame = make_frame(COLORS.get(self._token(), (100, 100, 100)))
        self.send_response(200)
        self.send_header("Content-Type", "multipart/x-mixed-replace; boundary=frame")
        self.end_headers()
        try:
            for i in range(120):
                self.wfile.write(b"--frame\r\n")
                self.wfile.write(b"Content-Type: image/jpeg\r\n")
                self.wfile.write(f"Content-Length: {len(frame)}\r\n".encode())
                self.wfile.write(f"X-Timestamp: {now_ms + i * 500}\r\n".encode())
                self.wfile.write(b"\r\n")
                self.wfile.write(frame)
                self.wfile.write(b"\r\n")
                self.wfile.flush()
                time.sleep(0.5)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        _ = self.rfile.read(length) if length else b""
        if self.path.startswith("/api/"):
            self._json({"ok": True})
        else:
            self._json({"error": "not_found"}, code=404)


class ThreadingHTTPServer(socketserver.ThreadingMixIn, http.server.HTTPServer):
    daemon_threads = True


def main():
    server = ThreadingHTTPServer(("127.0.0.1", PORT), Handler)
    print(f"QuietSentry mock camera (grid) listening on 127.0.0.1:{PORT}", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
