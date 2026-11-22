"""LiveView 基础巡检：设备列表 → 实时画面 → 画质切换 → 截图 → 返回。

前置：设备列表里已存在一台可达的相机（真机或 tools/verify/fake_camera.py）。
用法：python tools/verify/live_view_check.py [设备名]
"""
import sys
import time

import ui_walk as u

NAME = sys.argv[1] if len(sys.argv) > 1 else "测试相机"


def main():
    u.recover()
    # 上一次巡检可能停在 LiveView：先退到首页，否则“更多”入口根本不存在
    for _ in range(4):
        if u.activity().endswith(".MainActivity"):
            break
        u.back()
        time.sleep(1)
    u.ensure_app()
    _, nodes = u.dump("lv-pre")
    # 上一次异常退出可能把“更多”弹层留在首页，先关掉，否则下面再点“更多”会把它收起
    if any(n["text"] == "更多功能" for n in nodes):
        u.back()
        time.sleep(1)
    _, nodes = u.dump("lv-home")
    u.tap_text(nodes, "更多", exact=True)
    time.sleep(1.5)
    _, nodes = u.dump("lv-menu")
    if not u.tap_text(nodes, "我的设备（观看端）", exact=True):
        print("RESULT device_entry MISSING")
        return 1
    time.sleep(2.5)
    _, nodes = u.dump("lv-list")
    print("RESULT list_texts", [n["text"] for n in nodes if n["text"]][:8])
    if not u.tap_text(nodes, NAME):
        print(f"RESULT device MISSING {NAME}")
        return 1
    time.sleep(4)
    print("RESULT activity", u.activity())
    _, nodes = u.dump("lv-live")
    counter = next((n["text"] for n in nodes if n["rid"].endswith("live_counter")), "")
    print("RESULT live_counter", repr(counter))
    print("RESULT controls", [n["rid"].split("/")[-1] for n in nodes if n["rid"].startswith("com.quietsentry.app:id/live_")])

    smooth = next((n for n in nodes if n["rid"].endswith("live_quality_smooth")), None)
    if smooth:
        u.tap(smooth)
        time.sleep(4)
        _, nodes = u.dump("lv-smooth")
        print("RESULT after_smooth", next((n["text"] for n in nodes if n["rid"].endswith("live_counter")), ""))

    snap = next((n for n in nodes if n["rid"].endswith("live_snapshot")), None)
    if snap:
        u.tap(snap)
        time.sleep(1.5)
        print("RESULT snapshot_tapped", u.adb("shell", "ls", "-t", "/sdcard/Pictures/QuietSentry").splitlines()[:1])

    u.shot("lv-final")
    u.back()
    return 0


if __name__ == "__main__":
    sys.exit(main())
