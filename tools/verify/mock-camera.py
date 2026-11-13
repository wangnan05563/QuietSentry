#!/usr/bin/env python3
"""
QuietSentry mock 相机端服务（T04893 多状态走查用）。

通过 adb reverse tcp:8443/tcp:8444 让模拟器 127.0.0.1:8443 可达。

设备 ID → 状态映射（依据 DeviceState.evaluate 优先级）：
  cam-guard    → GUARDING（mode=guard，正常在线，无告警）
  cam-alert    → ALERT（now-lastEventAt < 5min，最近 1 分钟内有事件）
  cam-lowbat   → LOW_BATTERY（battery=15，charging=false）
  cam-offline  → OFFLINE（HTTP 连接失败，模拟设备不在线）

mock 服务路由规则：
  通过 URL path 里 query param ?dev=<id> 选择返回哪台相机的状态
  /api/info?dev=cam-guard   → 返回 guard cam 的 info
  /api/status?dev=cam-guard → 返回 guard cam 的 status

  注意：实际 QuietSentry DeviceHttp 不会带 ?dev= 参数，
  所以这里也支持通过 X-Dev header 路由（mock 端识别用），
  默认无 header 时返回 cam-guard。

  cam-offline：不注册路由，直接不响应（连接超时 → OFFLINE）
  实际做法：cam-offline 的 host/port 指向 127.0.0.1:9999（无 mock 监听）

4 台测试设备（DeviceRegistry JSON）：
  [
    {"id":"cam-guard","name":"Guard Cam","room":"客厅","host":"127.0.0.1","port":8443,"tlsFingerprint":"","token":"tok-guard","order":0,"lastSeenAt":0,"lastEventAt":0},
    {"id":"cam-alert","name":"Alert Cam","room":"门口","host":"127.0.0.1","port":8443,"tlsFingerprint":"","token":"tok-alert","order":1,"lastSeenAt":0,"lastEventAt":0},
    {"id":"cam-lowbat","name":"LowBat Cam","room":"卧室","host":"127.0.0.1","port":8443,"tlsFingerprint":"","token":"tok-lowbat","order":2,"lastSeenAt":0,"lastEventAt":0},
    {"id":"cam-offline","name":"Offline Cam","room":"车库","host":"127.0.0.1","port":9999,"tlsFingerprint":"","token":"tok-offline","order":3,"lastSeenAt":0,"lastEventAt":0}
  ]

  注：cam-offline 的 port=9999（无 mock 监听），模拟离线。
  通过 X-Auth-Token 头识别设备（tok-guard/tok-alert/tok-lowbat/tok-offline）。
"""
import http.server
import json
import struct
import time
import threading
import zlib

PORT = 8443  # 通过 adb reverse tcp:8443 到宿主机 127.0.0.1:8443

# 按 token 路由
CAMERAS = {
    "tok-guard": {
        "id": "cam-guard",
        "name": "Guard Cam",
        "port": PORT,
        "mode": "guard",
        "battery": 88, "charging": True,
        "thermal": 0, "storageFreeMb": 4096,
        "watching": 1, "maxViewers": 5,
        "lastEventAt": 0,  # 10 分钟前（不在 ALERT 窗口）
        "heartbeatAgeSec": 30,
    },
    "tok-alert": {
        "id": "cam-alert",
        "name": "Alert Cam",
        "port": PORT,
        "mode": "guard",
        "battery": 95, "charging": False,
        "thermal": 0, "storageFreeMb": 2048,
        "watching": 0, "maxViewers": 5,
        "lastEventAt": -60,  # 运行时动态设为 now-60s → ALERT
        "heartbeatAgeSec": 30,
    },
    "tok-lowbat": {
        "id": "cam-lowbat",
        "name": "LowBat Cam",
        "port": PORT,
        "mode": "idle",
        "battery": 15, "charging": False,  # 15% 且不在充电 → LOW_BATTERY
        "thermal": 0, "storageFreeMb": 8192,
        "watching": 0, "maxViewers": 5,
        "lastEventAt": 0,
        "heartbeatAgeSec": 30,
    },
}
# tok-offline 不在 CAMERAS → 返回 401，模拟设备不在线

# /api/snapshot 用纯 stdlib 生成 1280x720 纯色 PNG（不引入 Pillow）：
# 宽度 > 640 才能让客户端 THUMB_TARGET_PX=320 的 inSampleSize 真正生效，
# 每台设备用不同颜色，截图上一眼能区分是哪一路快照。
SNAPSHOT_COLORS = {
    "tok-guard": (52, 120, 246),
    "tok-alert": (220, 68, 68),
    "tok-lowbat": (232, 176, 48),
}
_snapshot_cache = {}


def _solid_png(width, height, rgb):
    key = (width, height, rgb)
    if key in _snapshot_cache:
        return _snapshot_cache[key]

    def chunk(tag, data):
        return (struct.pack(">I", len(data)) + tag + data
                + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF))

    row = b"\x00" + bytes(rgb) * width  # 每行前置 filter byte 0
    body = (b"\x89PNG\r\n\x1a\n"
            + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(row * height, 6))
            + chunk(b"IEND", b""))
    _snapshot_cache[key] = body
    return body


class Handler(http.server.BaseHTTPRequestHandler):
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

        if token == "tok-offline":
            # cam-offline：返回 401 模拟设备拒连
            self._json({"error": "unauthorized"}, code=401)
            return

        if cam is None:
            self._json({"error": "unauthorized"}, code=401)
            return

        if self.path.startswith("/api/info"):
            last_event_at = now_ms + cam.get("lastEventAt", 0) * 1000  # 相对时间
            self._json({
                "id": cam["id"],
                "name": cam["name"],
                "port": PORT,
                "tlsPort": 0,
                "tlsFingerprint": "",
                "mode": cam["mode"],
                "lastEventAt": last_event_at,
            })

        elif self.path.startswith("/api/status"):
            last_event_at = now_ms + cam.get("lastEventAt", 0) * 1000
            resp = {
                "mode": cam["mode"],
                "battery": cam["battery"],
                "charging": cam["charging"],
                "thermal": cam["thermal"],
                "storageFreeMb": cam["storageFreeMb"],
                "watching": cam["watching"],
                "maxViewers": cam["maxViewers"],
                "lastEventAt": last_event_at,
                "heartbeatAt": now_ms - cam.get("heartbeatAgeSec", 30) * 1000,
                "serverTime": now_ms,
                "throttled": False,
                "paused": False,
                "masked": False,
            }
            self._json(resp)

        elif self.path.startswith("/api/events"):
            self._json({"events": []})

        elif self.path.startswith("/api/snapshot"):
            body = _solid_png(1280, 720, SNAPSHOT_COLORS.get(token, (48, 48, 48)))
            # 打出时间戳与 token，供走查脚本统计 60s 节流是否生效
            print(f"SNAPSHOT token={token} at={time.time():.3f}", flush=True)
            self.send_response(200)
            self.send_header("Content-Type", "image/png")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        else:
            self._json({"error": "not_found"}, code=404)

    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        _ = self.rfile.read(length) if length else b""
        if self.path.startswith("/api/"):
            self._json({"ok": True})
        else:
            self._json({"error": "not_found"}, code=404)


def main():
    server = http.server.ThreadingHTTPServer(("127.0.0.1", PORT), Handler)
    print(f"QuietSentry mock camera listening on 127.0.0.1:{PORT}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
