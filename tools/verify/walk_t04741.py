#!/usr/bin/env python3
"""导航 5554（手机竖屏）到设备列表，抽取每张设备卡片的文本，验证 T04741 告警叠加标识。

用法：python tools/verify/walk_t04741.py
依赖：adb 在 PATH（E:/Android/sdk/platform-tools），设备 emulator-5554 在线且已注入 registry。
"""
import subprocess, re, sys, time, xml.etree.ElementTree as ET, os

ADB = r"E:/Android/sdk/platform-tools/adb.exe"
SER = "emulator-5554"
OUT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", "verify-output"))
os.makedirs(OUT, exist_ok=True)
UI = os.path.join(OUT, "ui-walk-5554.xml")

def run(*args):
    return subprocess.run([ADB, "-s", SER, *args], capture_output=True, text=True, timeout=60)

def dump():
    run("shell", "uiautomator", "dump", "/data/local/tmp/ui.xml")
    run("pull", "/data/local/tmp/ui.xml", UI)
    return open(UI, encoding="utf-8").read()

def tap_center(rid=None, text=None):
    d = dump()
    if rid:
        m = re.search(r'resource-id="%s"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"' % re.escape(rid), d)
    else:
        m = re.search(r'text="%s"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"' % re.escape(text), d)
    if not m:
        # 反向：bounds 在 resource-id/text 之前
        if rid:
            m = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"[^>]*resource-id="%s"' % re.escape(rid), d)
        else:
            m = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"[^>]*text="%s"' % re.escape(text), d)
    if not m:
        print("  [tap] NOMATCH rid=%s text=%s" % (rid, text)); return False
    x1,y1,x2,y2 = map(int, m.groups())
    cx, cy = (x1+x2)//2, (y1+y2)//2
    run("shell", "input", "tap", str(cx), str(cy))
    print("  [tap] %s -> (%d,%d)" % (rid or text, cx, cy))
    return True

def top():
    r = run("shell", "dumpsys", "activity", "activities")
    m = re.search(r"topResumedActivity=\S*?(\S+)", r.stdout)
    return m.group(1) if m else "?"

def extract_cards():
    """从 uiautomator XML 抽取每张卡片的 name/meta/detail/state 文本。"""
    d = dump()
    # 简化：用正则按文档顺序抓各 resource-id 的 text
    def texts(rid):
        return [m.group(1) for m in re.finditer(r'resource-id="%s"[^>]*text="([^"]*)"' % re.escape(rid), d)]
    names = texts("com.quietsentry.app:id/device_name")
    metas = texts("com.quietsentry.app:id/device_meta")
    details = texts("com.quietsentry.app:id/device_detail")
    states = texts("com.quietsentry.app:id/device_state")
    n = max(len(names), len(metas), len(details), len(states))
    cards = []
    for i in range(n):
        cards.append({
            "name": names[i] if i < len(names) else "",
            "meta": metas[i] if i < len(metas) else "",
            "detail": details[i] if i < len(details) else "",
            "state": states[i] if i < len(states) else "",
        })
    return cards

print("== boot app ==")
run("shell", "am", "start", "-n", "com.quietsentry.app/.MainActivity", "--activity-clear-top")
time.sleep(4)
print("  top:", top())
print("== tap more_button ==")
tap_center(rid="com.quietsentry.app:id/more_button")
time.sleep(2)
print("== tap 我的设备（观看端） ==")
tap_center(text="我的设备（观看端）")
time.sleep(6)
print("  top:", top())
print("== extract cards ==")
cards = extract_cards()
for c in cards:
    print("  CARD name=%-12s | meta=%-20s | state=%-6s | detail=%s" % (c["name"], c["meta"], c["state"], c["detail"]))
