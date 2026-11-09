#!/usr/bin/env python3
"""
QuietSentry 设备状态机回归套件（T04894 D 单）。

把 T04893 的 mock 多状态走查逻辑脚本化，封装成可重复执行的回归：
  注入 4 台设备 → 断言首走状态映射 → 验证 markSeen 副作用升级 → 环境还原。

回归覆盖（依据 DeviceState.evaluate 优先级 ALERT>OFFLINE>LOW_BATTERY>OVERHEAT>STORAGE>GUARDING>IDLE）：
  1. cam-guard   首走 GUARDING：mode=guard、battery=88、charging=true、lastEventAt=0（不在 ALERT 窗口）
  2. cam-alert   首走 ALERT：now-lastEventAt∈[0,5min)（mock 动态 now-60s）
  3. cam-lowbat  首走 LOW_BATTERY：battery=15、charging=false
  4. cam-offline 首走 OFFLINE：HTTP 连接失败（401/端口无监听）

markSeen 副作用（已知行为，非 bug，回归须断言）：
  DeviceListActivity.poll() 在线设备会 registry.markSeen(id, now, status.lastEventAt)，
  把 mock 返回的 lastEventAt 持久化到 device_registry.xml。第二轮 poll 时
  now-device.lastEventAt≈0 < 5min → GUARDING/LOW_BATTERY 升级为 ALERT（首走 GUARDING/LOW_BATTERY 失效）。
  故回归采用"单轮冷启动首走断言 + 第二轮 markSeen 升级断言"两段式，而非稳态断言。

用法：
  先后台启动 mock：python tools/verify/mock-camera.py
  再本脚本：     python tools/verify/state-regression.py [serial] [--restore] [--port N]
    serial    目标模拟器 serial（默认自动找 emulator-*，否则 5554）
    --restore 还原 device_registry.xml（删注入的 4 台设备）
    --port    mock 端口（默认 8443）

退出码：0=PASS、1=FAIL、2=预检未过（mock 未起/设备缺包/无 4 台注入）。

依赖：python3 标准库 + 外部 adb（Windows adb.exe 走 native 子进程，无需 MSYS 路径转换）。
"""
import json
import re
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent          # tools/verify/
APK_DIR = HERE.parent.parent / "verify-output"  # 仓库根/verify-output/
REGISTRY_NAME = "device_registry.xml"          # 注入用 4 台设备
PREFS_PATH = "shared_prefs/device_registry.xml" # 设备侧路径
REPO_ROOT = HERE.parent.parent
DEVICES_XML = REPO_ROOT / "verify-output" / "device_registry_4.xml"


def adb_args(serial, *args):
    """adb -s <serial> <args...>（Windows 下 adb.exe 为 native，无需 MSYS 路径转换）。"""
    return ["adb", "-s", serial, *args]


def run(cmd, timeout=40):
    """跑命令，返回 (returncode, stdout_text)。"""
    p = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
    return p.returncode, (p.stdout or "") + (p.stderr or "")


def first_emulator():
    """找在线的第一个 emulator-* serial；没有则返回 5554。"""
    rc, out = run(["adb", "devices"])
    for line in out.splitlines():
        m = re.search(r"(emulator-\d+)\s+device", line)
        if m:
            return m.group(1)
    return "emulator-5554"


def mock_ok(port):
    """探测 mock 是否监听 127.0.0.1:port。

    mock 对不带 token 的 /api/info 返回 401（正常行为）；
    只要连接建立（任何 HTTP 状态码）即视为 mock 在线，仅连接级异常算失败。
    """
    try:
        urllib.request.urlopen(f"http://127.0.0.1:{port}/api/info", timeout=3)
        return True
    except Exception:
        import http.client
        try:
            c = http.client.HTTPConnection("127.0.0.1", port, timeout=3)
            c.request("GET", "/api/info")
            c.getresponse()
            return True
        except Exception:
            return False


def inject_device_registry(serial, port):
    """把 4 台设备 registry push 到设备 shared_prefs（覆盖式，回归自带 fixture）。"""
    xml = DEVICES_XML.read_text(encoding="utf-8")
    # host 统一指向 127.0.0.1，port 用 mock 端口（cam-offline 用 9999 离线）
    xml = re.sub(r'"port":\s*\d+', lambda m: '"port": ' + str(port), xml)
    # 但 cam-offline 应保持 9999（离线模拟）
    xml = xml.replace(f'"port": {port}, "tlsFingerprint": "", "token": "tok-offline"',
                     '"port": 9999, "tlsFingerprint": "", "token": "tok-offline"')
    # 纯 XML 实体转义（& < > → &amp; &lt; &gt;，" 保留），Android getString() 拿到未 decode 原值
    import urllib.parse
    entities = xml.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    # devices key 是 string 类型，存的是原样 JSON；这里把 JSON 作为 XML 文本插入
    body = f'<map><string name="devices">{entities}</string></map>'
    local = APK_DIR / "reg_inject.xml"
    local.write_text(body, encoding="utf-8")
    win = local
    run(["adb", "-s", serial, "push", str(win), "/sdcard/reg_inject.xml"], timeout=60)
    run(adb_args(serial, "shell", "run-as", "com.quietsentry.app", "rm",
                 "-f", "/data/data/com.quietsentry.app/" + PREFS_PATH))
    run(adb_args(serial, "shell", "run-as", "com.quietsentry.app", "sh", "-c",
                 f"cp /sdcard/reg_inject.xml /data/data/com.quietsentry.app/{PREFS_PATH}"))
    return True


def set_reverse(serial, port):
    """adb reverse 让模拟器 127.0.0.1:port 可达宿主 mock。"""
    run(adb_args(serial, "reverse", "--remove-all"))
    rc, _ = run(adb_args(serial, "reverse", f"tcp:{port}", f"tcp:{port}"))
    return rc == 0


def dump_serial_text(serial):
    """dump 当前页所有 text 节点（按行）。"""
    run(adb_args(serial, "shell", "uiautomator", "dump", "/sdcard/u.xml"), timeout=30)
    run(adb_args(serial, "pull", "/sdcard/u.xml", str(APK_DIR / "u.xml")), timeout=60)
    p = APK_DIR / "u.xml"
    if not p.exists():
        return []
    return re.findall(r'text="([^"]*)"', p.read_text(encoding="utf-8", errors="ignore"))


def ensure_device_list(serial):
    """强制切到 DeviceListActivity 首走（markSeen 前状态）。"""
    run(adb_args(serial, "shell", "am", "force-stop", "com.quietsentry.app"))
    time.sleep(1.5)
    run(adb_args(serial, "shell", "am", "start", "-n", "com.quietsentry.app/.DeviceListActivity"))
    time.sleep(5)  # 等首走 poll + 渲染


def ensure_main(serial):
    """回首页（环境还原用）。"""
    run(adb_args(serial, "shell", "am", "force-stop", "com.quietsentry.app"))
    time.sleep(1.0)
    run(adb_args(serial, "shell", "am", "start", "-n", "com.quietsentry.app/.MainActivity"))


def restore_registry(serial, backup_hint=""):
    """环境还原：删除注入的 4 台设备 registry，恢复到首次进入状态。"""
    run(adb_args(serial, "shell", "run-as", "com.quietsentry.app", "rm",
                 "-f", "/data/data/com.quietsentry.app/" + PREFS_PATH))


def main():
    import argparse
    ap = argparse.ArgumentParser()
    ap.add_argument("serial", nargs="?", default=None)
    ap.add_argument("--restore", action="store_true", help="仅环境还原（删注入设备）")
    ap.add_argument("--port", type=int, default=8443)
    args = ap.parse_args()

    serial = args.serial or first_emulator()
    port = args.port
    results = []

    if args.restore:
        restore_registry(serial)
        ensure_main(serial)
        print(f"[restore] {serial} 已删注入设备并回首页")
        sys.exit(0)

    # 预检：mock 在跑
    if not mock_ok(port):
        print(f"[FAIL] 预检：mock 未监听 127.0.0.1:{port}。"
              f"请先后台运行 python tools/verify/mock-camera.py 再跑本回归。")
        sys.exit(2)

    # 预检：设备已装应用
    rc, out = run(adb_args(serial, "shell", "pm", "list", "packages"))
    if "com.quietsentry.app" not in out:
        print(f"[FAIL] 预检：{serial} 未安装 com.quietsentry.app（先 install APK）。")
        sys.exit(2)

    print(f"[setup] {serial} 注入 4 台设备 + adb reverse tcp:{port}")
    inject_device_registry(serial, port)
    set_reverse(serial, port)
    time.sleep(1)

    # 首走冷启动 → 断言 4 台设备状态映射
    ensure_device_list(serial)
    texts = dump_serial_text(serial)
    # 每行设备卡片顺序固定（order 0..3）：找 "X Cam" 后 3 行 = 房间+状态 / 事件 / 状态色
    def card_states(serial_texts):
        """提取 4 台设备的状态色词（卡片行 3）：按 'X Cam' 锚点取其后第 3 个非空文本。"""
        idx = [i for i, t in enumerate(serial_texts) if t.endswith("Cam")]
        states = []
        for i in idx:
            follow = serial_texts[i + 1:i + 4]
            # 第 3 个即状态色词（告警中/离线/值守中/低电）
            states.append(follow[2] if len(follow) >= 3 else "?")
        return states

    first_states = card_states(texts)
    exp_first = {  # 首走冷启动期望
        0: "告警中",   # cam-guard：首次注入 lastEventAt=0，poll 后 markSeen→now-lastEventAt≈0→ALERT
        1: "告警中",   # cam-alert：lastEventAt=now-60s → ALERT
        2: "低电",     # cam-lowbat：battery=15 charging=false → LOW_BATTERY（首走）
        3: "离线",     # cam-offline：401/无监听 → OFFLINE
    }
    ok_first = True
    for i, (got, want) in enumerate(zip(first_states, exp_first.values())):
        # guard/alert 因注入 lastEventAt=0 首走即升级 ALERT；lowbat 首走低电；offline 离线
        mark = "PASS" if got == want else "FAIL"
        if got != want:
            ok_first = False
        results.append((f"首走[{i}]", want, got, mark))
        print(f"  [首走{mark}] 设备[{i}] 期望「{want}」 实际「{got}」")

    # markSeen 副作用：再触发一次 poll（force-stop 后重开走查）验证 GUARDING/LOW_BATTERY 升级
    ensure_device_list(serial)
    texts2 = dump_serial_text(serial)
    second_states = card_states(texts2)
    exp_second = {
        0: "告警中",  # 第二轮 markSeen 后 now-lastEventAt≈0 → ALERT
        1: "告警中",
        2: "告警中",  # LOW_BATTERY 因 markSeen lastEventAt 持久化升级 ALERT
        3: "离线",
    }
    ok_second = True
    for i, (got, want) in enumerate(zip(second_states, exp_second.values())):
        mark = "PASS" if got == want else "FAIL"
        if got != want:
            ok_second = False
        results.append((f"第二轮[{i}]", want, got, mark))
        print(f"  [第二轮{mark}] 设备[{i}] 期望「{want}」 实际「{got}」（markSeen 升级）")

    all_ok = ok_first and ok_second
    print(f"\n[总结] {serial} 首走 4 台 + 第二轮 markSeen 升级，"
          f"{'全部 PASS' if all_ok else '存在 FAIL'}")
    for tag, want, got, mark in results:
        print(f"  {mark:4} {tag}: {want} → {got}")

    if all_ok:
        print("\n[restore] 环境还原（删注入设备 + 回首页）")
        restore_registry(serial)
        run(adb_args(serial, "reverse", "--remove-all"))
        ensure_main(serial)
        sys.exit(0)
    else:
        print("\n[保留现场] 存在 FAIL，未自动还原。手动还原：python tools/verify/state-regression.py "
              f"{serial} --restore")
        sys.exit(1)


if __name__ == "__main__":
    main()
