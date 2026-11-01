#!/usr/bin/env python3
"""RTSP over TCP 拉流自检（T03972）。

为什么不用 ffmpeg/VLC 做这一步：它们会同时验证解码器、容器与网络条件，
排障时无法区分“设备没推流”和“客户端环境缺组件”。这里只做 RFC 2326 的最小握手
（OPTIONS → DESCRIBE → SETUP → PLAY），再数 RTP 包，失败信息就能直接指向设备侧。

判定：拿到 SDP 的 H264 描述、收到至少 1 个带 SSRC 的 RTP 包，退出码 0；否则 1。

用法：
  python tools/verify/rtsp-check.py --host 127.0.0.1 --port 8554 --token qc-xxxx
  python tools/verify/rtsp-check.py --host 10.0.2.15 --port 8554 --token qc-xxxx --seconds 5
"""

import argparse
import base64
import socket
import sys
import time


class Reader:
    """RTSP 响应与 RTP 数据混在同一条 TCP 流里，必须先缓存再切分，
    否则一次 recv 会把 PLAY 之后的首批 RTP 包吞进响应缓冲区。"""

    def __init__(self, sock: socket.socket):
        self.sock = sock
        self.buf = b""

    def fill(self) -> bool:
        data = self.sock.recv(8192)
        if not data:
            return False
        self.buf += data
        return True

    def read_exact(self, size: int) -> bytes:
        while len(self.buf) < size:
            if not self.fill():
                raise EOFError("连接已关闭")
        chunk, self.buf = self.buf[:size], self.buf[size:]
        return chunk

    def read_response(self) -> tuple[int, dict[str, str], str]:
        while b"\r\n\r\n" not in self.buf:
            if not self.fill():
                raise EOFError("响应不完整")
        head, _, self.buf = self.buf.partition(b"\r\n\r\n")
        lines = head.decode("utf-8", "replace").split("\r\n")
        status = int(lines[0].split(" ")[1]) if len(lines[0].split(" ")) > 1 else 0
        headers: dict[str, str] = {}
        for line in lines[1:]:
            colon = line.find(":")
            if colon > 0:
                headers[line[:colon].strip().lower()] = line[colon + 1:].strip()
        length = int(headers.get("content-length", "0") or 0)
        body = self.read_exact(length).decode("utf-8", "replace") if length else ""
        return status, headers, body


def send(sock, method: str, uri: str, cseq: int, auth: str, extra: dict[str, str] | None = None) -> None:
    lines = [f"{method} {uri} RTSP/1.0", f"CSeq: {cseq}", f"Authorization: Basic {auth}"]
    for key, value in (extra or {}).items():
        lines.append(f"{key}: {value}")
    sock.sendall(("\r\n".join(lines) + "\r\n\r\n").encode("utf-8"))


def main() -> int:
    parser = argparse.ArgumentParser(description="QuietSentry RTSP 拉流自检")
    parser.add_argument("--host", required=True)
    parser.add_argument("--port", type=int, default=8554)
    parser.add_argument("--token", required=True)
    parser.add_argument("--user", default="admin")
    parser.add_argument("--seconds", type=float, default=4)
    args = parser.parse_args()

    base = f"rtsp://{args.host}:{args.port}/stream/main"
    auth = base64.b64encode(f"{args.user}:{args.token}".encode("utf-8")).decode("ascii")
    results: list[tuple[str, bool, str]] = []

    sock = socket.create_connection((args.host, args.port), timeout=8)
    reader = Reader(sock)
    session = ""
    try:
        for index, method in enumerate(("OPTIONS", "DESCRIBE"), start=1):
            extra = {"Accept": "application/sdp"} if method == "DESCRIBE" else None
            send(sock, method, base, index, auth, extra)
            status, _, body = reader.read_response()
            ok = status == 200
            detail = f"status={status}"
            if method == "DESCRIBE" and ok:
                ok = "H264/90000" in body
                detail = "SDP 含 H264/90000" if ok else "SDP 缺少 H264 描述"
            results.append((method, ok, detail))
            if not ok:
                return report(results)

        send(sock, "SETUP", f"{base}/trackID=0", 3, auth,
             {"Transport": "RTP/AVP/TCP;unicast;interleaved=0-1"})
        status, headers, _ = reader.read_response()
        session = headers.get("session", "")
        results.append(("SETUP", status == 200, f"status={status} session={session or '-'}"))
        if status != 200:
            return report(results)

        send(sock, "PLAY", base, 4, auth, {"Session": session})
        status, _, _ = reader.read_response()
        results.append(("PLAY", status == 200, f"status={status}"))
        if status != 200:
            return report(results)

        # PLAY 之后同一条连接上混着 RTSP 响应与 '$' 前缀的 interleaved 帧，
        # 用 1 秒超时逐帧抽取：太短会把“编码器还没出帧”误判成断流。
        sock.settimeout(1.0)
        packets = 0
        payload = 0
        ssrc = 0
        payload_type = 0
        deadline = time.monotonic() + args.seconds
        while time.monotonic() < deadline:
            if b"$" not in reader.buf:
                try:
                    if not reader.fill():
                        break
                except socket.timeout:
                    continue
                continue
            reader.buf = reader.buf[reader.buf.index(b"$"):]
            if len(reader.buf) < 4:
                continue
            size = int.from_bytes(reader.buf[2:4], "big")
            if len(reader.buf) < 4 + size:
                try:
                    reader.fill()
                except socket.timeout:
                    pass
                continue
            channel = reader.buf[1]
            packet = reader.buf[4:4 + size]
            reader.buf = reader.buf[4 + size:]
            if channel != 0 or size < 12:
                continue
            packets += 1
            payload += size - 12
            payload_type = packet[1] & 0x7F
            ssrc = int.from_bytes(packet[8:12], "big")
        results.append(("RTP", packets > 0,
                        f"收到 {packets} 个包 / 负载 {payload} 字节 / PT={payload_type} / SSRC={ssrc:08X}"))
    except OSError as error:
        results.append(("连接", False, f"{type(error).__name__}: {error}"))
    finally:
        try:
            send(sock, "TEARDOWN", base, 5, auth, {"Session": session})
        except OSError:
            pass
        sock.close()
    return report(results)


def report(results: list[tuple[str, bool, str]]) -> int:
    print("\n=== RTSP 自检结果 ===")
    failures = sum(1 for _, ok, _ in results if not ok)
    for name, ok, detail in results:
        print(f"  [{'PASS' if ok else 'FAIL'}] {name}: {detail}")
    print(f"\n合计 {len(results)} 项，失败 {failures} 项")
    if failures == 0:
        print("结论：RTSP over TCP 可拉流，ONVIF GetStreamUri 返回的地址可直接给 Frigate / HA。")
        return 0
    print("结论：设备侧 RTSP 未就绪（集成开关、令牌、H264 编码或端口），先修设备再谈 NVR。")
    return 1


if __name__ == "__main__":
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except Exception:
        pass
    sys.exit(main())
