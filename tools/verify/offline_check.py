"""断网态巡检：关闭 Wi-Fi / 数据后看远程访问页的地址、检测结果与提示。

用法：python tools/verify/offline_check.py
"""
import time

import ui_walk as u


def svc(name, action):
    u.adb("shell", "svc", name, action)


def main():
    try:
        svc("wifi", "disable")
        svc("data", "disable")
        time.sleep(4)
        u.adb("shell", "am", "force-stop", "com.quietsentry.app")
        time.sleep(1)
        u.ensure_app()
        time.sleep(2)
        _, nodes = u.dump("off-home")
        print("RESULT off_home", [n["text"][:30] for n in nodes if n["text"]][:6])
        u.tap_text(nodes, "更多", exact=True)
        time.sleep(1.5)
        _, nodes = u.dump("off-menu")
        u.tap_text(nodes, "远程访问与诊断", exact=True)
        time.sleep(2)
        _, nodes = u.dump("off-remote")
        net = next((n["text"] for n in nodes if n["rid"].endswith("remote_network")), "")
        target = next((n for n in nodes if n["rid"].endswith("remote_target")), None)
        print("RESULT off_network", repr(net))
        print("RESULT off_target", repr(target["text"]) if target else "MISSING")
        box = next((n for n in nodes if n["rid"].endswith("remote_target")), None)
        if box:
            u.tap(box)
            time.sleep(0.6)
            u.adb("shell", "input", "text", "192.168.1.5:8080")
            time.sleep(0.5)
            u.adb("shell", "input", "keyevent", "4")
            time.sleep(0.8)
        _, nodes = u.dump("off-remote2")
        probe = next((n for n in nodes if n["rid"].endswith("remote_probe")), None)
        if probe:
            u.tap(probe)
            time.sleep(5)
            _, nodes = u.dump("off-remote3")
        print("RESULT off_result", [n["text"][:60] for n in nodes if n["text"]][:10])
        u.shot("off-remote")
        u.back()
    finally:
        svc("wifi", "enable")
        svc("data", "enable")
        time.sleep(5)
        print("RESULT restored_wifi", u.adb("shell", "dumpsys", "wifi", "|", "grep", "Wi-Fi is").strip()[:120])


if __name__ == "__main__":
    main()
