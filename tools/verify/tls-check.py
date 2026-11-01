#!/usr/bin/env python3
"""局域网 TLS 自检（T03960）。

验证三件事：8443 真的在提供 TLS、`/api/info` 上报的指纹与证书公钥一致
（观看端 pin 依据的就是它）、配对请求在 HTTPS 内完成而不是退回明文。

为什么指纹比对要自己算一遍：客户端只看“指纹字符串相等”只能证明两端拼接一致，
重算证书公钥摘要才能排除“上报值与实际证书不是同一张”的配置错位。

用法：
  python tools/verify/tls-check.py --host 127.0.0.1 --http-port 28080 --https-port 28443
  python tools/verify/tls-check.py --host 127.0.0.1 --http-port 28080 --https-port 28443 --code 123456
"""

import argparse
import base64
import hashlib
import http.client
import json
import ssl
import sys
import urllib.request


def public_key_fingerprint(der: bytes) -> str:
    """按 TlsIdentity/CryptoBox 的口径：公钥 DER 的 SHA-256 前 8 字节，大写冒号分隔。"""
    from cryptography import x509
    from cryptography.hazmat.primitives import serialization

    cert = x509.load_der_x509_certificate(der)
    pub = cert.public_key().public_bytes(
        serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo
    )
    return ":".join(f"{b:02X}" for b in hashlib.sha256(pub).digest()[:8])


def main() -> int:
    parser = argparse.ArgumentParser(description="QuietSentry 局域网 TLS 自检")
    parser.add_argument("--host", required=True)
    parser.add_argument("--http-port", type=int, default=8080)
    parser.add_argument("--https-port", type=int, default=8443)
    parser.add_argument("--code", default="", help="可选：用该配对码做一次 HTTPS 配对")
    args = parser.parse_args()

    results: list[tuple[str, bool, str]] = []
    info = json.load(urllib.request.urlopen(f"http://{args.host}:{args.http_port}/api/info", timeout=8))
    expected = str(info.get("tlsFingerprint", ""))
    results.append(("info.tlsFingerprint", bool(expected), expected or "(空)"))

    # 校验时不校验证书链（自签），主机名也交给指纹；这正是观看端 pin 的做法
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    context.check_hostname = False
    context.verify_mode = ssl.CERT_NONE

    conn = http.client.HTTPSConnection(args.host, args.https_port, context=context, timeout=10)
    try:
        # 必须先 connect 再取 sock：服务端返回 Connection: close，读完响应后 sock 已置 None
        conn.connect()
        der = conn.sock.getpeercert(binary_form=True)
        conn.request("GET", "/api/info")
        response = conn.getresponse()
        response.read()
        actual = public_key_fingerprint(der)
        results.append(("TLS 握手", response.status == 200, f"status={response.status}"))
        results.append(("证书公钥指纹", actual == expected, f"实际 {actual} / 上报 {expected}"))
    finally:
        conn.close()

    if args.code:
        # 配对要求合法 EC 公钥（secp256r1 SPKI Base64）：服务端先解码公钥，解不开直接 invalid_code，
        # 所以这里生成一次性的临时密钥对，只用于证明“码 + HTTPS 链路”可用
        from cryptography.hazmat.primitives import serialization
        from cryptography.hazmat.primitives.asymmetric import ec

        key = ec.generate_private_key(ec.SECP256R1())
        public_key = base64.b64encode(
            key.public_key().public_bytes(
                serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo
            )
        ).decode("ascii")
        body = json.dumps({"publicKey": public_key, "code": args.code}).encode("utf-8")
        conn = http.client.HTTPSConnection(args.host, args.https_port, context=context, timeout=15)
        try:
            conn.request("POST", "/api/pair", body, {"Content-Type": "application/json"})
            response = conn.getresponse()
            text = response.read().decode("utf-8", "replace")
            results.append(("HTTPS 配对", response.status == 200, f"status={response.status} {text[:160]}"))
        finally:
            conn.close()

    print("\n=== TLS 自检结果 ===")
    failures = sum(1 for _, ok, _ in results if not ok)
    for name, ok, detail in results:
        print(f"  [{'PASS' if ok else 'FAIL'}] {name}: {detail}")
    print(f"\n合计 {len(results)} 项，失败 {failures} 项")
    return 0 if failures == 0 else 1


if __name__ == "__main__":
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except Exception:
        pass
    sys.exit(main())
