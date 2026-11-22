"""QuietSentry 模拟器逐页 UI 走查脚手架（黑盒：uiautomator dump + adb 输入）。

用法：
    python tools/verify/ui_walk.py pages      # 遍历"更多"菜单与设置页，产出 dump/截图/审计
    python tools/verify/ui_walk.py audit      # 只对最近一次 dump 做审计
    python tools/verify/ui_walk.py shot NAME  # 截图

依赖：E:\Android\sdk\platform-tools\adb.exe，设备 emulator-5554（1080x2400 @420dpi）。
产物：tools/verify/ui-artifacts/{ui-*.xml, shot-*.png, findings.jsonl}
"""
import json
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ADB = r"E:\Android\sdk\platform-tools\adb.exe"
SER = os.environ.get("QS_SERIAL", "emulator-5554")
HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "ui-artifacts")
PKG = "com.quietsentry.app"
DENSITY = 420 / 160.0  # 2.625
MIN_TOUCH_DP = 44.0


def adb(*args, timeout=90, binary=False):
    cmd = [ADB, "-s", SER] + list(args)
    r = subprocess.run(cmd, capture_output=True, timeout=timeout)
    if binary:
        return r.stdout
    return (r.stdout + r.stderr).decode("utf-8", "replace").strip()


def wait_boot(limit=300):
    t0 = time.time()
    while time.time() - t0 < limit:
        st = adb("get-state")
        if st == "device":
            b = adb("shell", "getprop", "sys.boot_completed")
            if b.strip() == "1":
                return True
        time.sleep(5)
    return False


def dump(name="last", retries=3):
    """返回 (ET 根节点, dict 节点列表)。失败重试。"""
    os.makedirs(OUT, exist_ok=True)
    local = os.path.join(OUT, f"ui-{name}.xml")
    for i in range(retries):
        adb("shell", "uiautomator", "dump", "/sdcard/ui.xml", timeout=60)
        xml = adb("shell", "cat", "/sdcard/ui.xml", timeout=60)
        if xml.startswith("<?xml") and "</hierarchy>" in xml:
            with open(local, "w", encoding="utf-8") as f:
                f.write(xml)
            try:
                root = ET.fromstring(xml)
                nodes = []
                for n in root.iter("node"):
                    a = n.attrib
                    b = a.get("bounds", "")
                    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", b)
                    if not m:
                        continue
                    x1, y1, x2, y2 = map(int, m.groups())
                    nodes.append({
                        "cls": a.get("class", ""),
                        "rid": a.get("resource-id", ""),
                        "text": a.get("text", ""),
                        "desc": a.get("content-desc", ""),
                        "clickable": a.get("clickable") == "true",
                        "enabled": a.get("enabled") == "true",
                        "scrollable": a.get("scrollable") == "true",
                        "x1": x1, "y1": y1, "x2": x2, "y2": y2,
                        "w": x2 - x1, "h": y2 - y1,
                        "cx": (x1 + x2) // 2, "cy": (y1 + y2) // 2,
                    })
                return root, nodes
            except ET.ParseError:
                pass
        # 上一轮 uiautomator 没收干净时会报 "UiAutomationService already registered"
        adb("shell", "pkill", "-f", "uiautomator", timeout=30)
        time.sleep(2)
    raise RuntimeError(f"dump failed: {name}")


def shot(name):
    os.makedirs(OUT, exist_ok=True)
    p = os.path.join(OUT, f"shot-{name}.png")
    data = adb("exec-out", "screencap", "-p", binary=True, timeout=90)
    if not data.startswith(b"\x89PNG"):
        raise RuntimeError("screencap not png")
    with open(p, "wb") as f:
        f.write(data)
    return p


def activity():
    out = adb("shell", "dumpsys", "activity", "activities", timeout=60)
    for pat in (r"topResumedActivity.*?u0 (\S+)/(\S+)", r"mResumedActivity.*?u0 (\S+)/(\S+)"):
        m = re.search(pat, out)
        if m:
            return f"{m.group(1)}/{m.group(2)}"
    out = adb("shell", "dumpsys", "window", "windows", timeout=60)
    m = re.search(r"mCurrentFocus=Window\{\S+ u0 (\S+)/(\S+)\}", out)
    if m:
        return f"{m.group(1)}/{m.group(2)}"
    return "?"


def tap(n):
    # 底部按钮贴着手势导航条时，正中点常常落在系统手势区被判成滑动；
    # 统一取节点偏上的位置，命中率更高
    y = n["y1"] + max(8, int(n["h"] * 0.42))
    adb("shell", "input", "tap", str(n["cx"]), str(y))
    time.sleep(1.2)


def tap_text(nodes, text, exact=False):
    for n in nodes:
        t = n["text"] or n["desc"]
        if (t == text) if exact else (text in t):
            tap(n)
            return n
    return None


def tap_rid(nodes, rid):
    for n in nodes:
        if n["rid"].endswith(rid):
            tap(n)
            return n
    return None


def swipe(x1, y1, x2, y2, ms=300):
    adb("shell", "input", "swipe", str(x1), str(y1), str(x2), str(y2), str(ms))
    time.sleep(1)


def back():
    adb("shell", "input", "keyevent", "4")
    time.sleep(1)


def open_app():
    # 注意：release 包的 Activity 名与 manifest 组件路径不一致，am start 会报
    # "Activity class does not exist"；用 LAUNCHER intent 启动最稳
    adb("shell", "monkey", "-p", PKG, "-c", "android.intent.category.LAUNCHER", "1", timeout=60)
    time.sleep(3)


_SIZE = None


def screen_size():
    global _SIZE
    if _SIZE is None:
        out = adb("shell", "wm", "size", timeout=30)
        m = re.search(r"Override size: (\d+)x(\d+)", out) or re.search(r"Physical size: (\d+)x(\d+)", out)
        _SIZE = (int(m.group(1)), int(m.group(2))) if m else (1080, 2400)
    return _SIZE


def audit(name, nodes, W=None, H=None):
    W, H = W or screen_size()[0], H or screen_size()[1]
    f = []
    for n in nodes:
        dp_w, dp_h = n["w"] / DENSITY, n["h"] / DENSITY
        label = n["text"] or n["desc"] or n["rid"] or n["cls"]
        if n["clickable"] and (dp_w < MIN_TOUCH_DP or dp_h < MIN_TOUCH_DP):
            f.append(dict(page=name, code="SMALL_TOUCH",
                          detail=f"{label} {n['w']}x{n['h']}px = {dp_w:.0f}x{dp_h:.0f}dp"))
        if n["w"] <= 0 or n["h"] <= 0:
            f.append(dict(page=name, code="ZERO_SIZE", detail=str(label)))
        if n["x1"] < 0 or n["y1"] < 0 or n["x2"] > W or n["y2"] > H:
            f.append(dict(page=name, code="OUT_OF_BOUNDS", detail=f"{label} {n['x1']},{n['y1']},{n['x2']},{n['y2']}"))
        if n["cls"].endswith("ImageView") and not n["desc"] and n["w"] > 40 * DENSITY:
            f.append(dict(page=name, code="IMG_NO_DESC", detail=f"{label} {n['w']}x{n['h']}"))
        if n["text"].rstrip().endswith(("…", "...")):
            f.append(dict(page=name, code="ELLIPSIS", detail=n["text"][:60]))
    # 同屏重复文案
    seen = {}
    for n in nodes:
        t = n["text"].strip()
        if t and len(t) > 1:
            seen.setdefault(t, []).append(n)
    for t, ns in seen.items():
        if len(ns) > 1:
            f.append(dict(page=name, code="DUP_TEXT", detail=f"'{t}' x{len(ns)}"))
    return f


def save_findings(items):
    os.makedirs(OUT, exist_ok=True)
    path = os.path.join(OUT, "findings.jsonl")
    with open(path, "a", encoding="utf-8") as fh:
        for it in items:
            fh.write(json.dumps(it, ensure_ascii=False) + "\n")


def clear_anr(nodes):
    """系统 UI 的 ANR 弹窗会盖住被测应用，点 Wait 让它继续。"""
    for n in nodes:
        if "isn't responding" in n["text"] or "无响应" in n["text"]:
            for key in ("Wait", "等待"):
                w = next((x for x in nodes if x["text"] == key), None)
                if w:
                    tap(w)
                    time.sleep(3)
                    return True
    return False


def snapshot(tag, do_shot=True, do_audit=True):
    root, nodes = dump(tag)
    if clear_anr(nodes):
        root, nodes = dump(tag)
    if do_shot:
        try:
            shot(tag)
        except Exception as e:
            print(f"  ! screenshot failed: {e}")
    if do_audit:
        save_findings(audit(tag, nodes))
    txt = [n["text"] for n in nodes if n["text"].strip()]
    return nodes, txt


def dismiss_dialog(nodes):
    """尽量关闭系统/应用对话框：优先 CANCEL / 关闭 / 取消 / OK。"""
    for key in ("CANCEL", "取消", "关闭", "CLOSE", "OK", "确定", "知道了"):
        n = tap_text(nodes, key, exact=True)
        if n:
            return key
    return None


MENU_ITEMS = ["事件回看", "配对与设备", "我的设备（观看端）", "远程访问与诊断", "设置与诊断", "黑屏记录", "连续记录"]


def ensure_app():
    """镜像会周期性拉起 airmirror 抢前台：不在自家应用就删掉它并重新拉起。"""
    act = activity()
    if "BlackScreenActivity" in act:
        back()
        time.sleep(1.5)
        act = activity()
    if PKG in act:
        return True
    adb("shell", "pm", "uninstall", "io.github.wangnan05563.airmirror", timeout=60)
    adb("shell", "input", "keyevent", "3")
    time.sleep(1)
    open_app()
    time.sleep(3)
    return PKG in activity()


def recover():
    """回到首页：优先 HOME 再 am start，避免把系统设置页留在前台。"""
    for _ in range(2):
        if "MainActivity" in activity():
            return True
        adb("shell", "input", "keyevent", "3")
        time.sleep(1.5)
    open_app()
    return "MainActivity" in activity()


def cmd_prep():
    """首次启动把合规页/引导页/权限弹窗走完，落到 MainActivity。"""
    if not wait_boot():
        print("BOOT TIMEOUT")
        return 2
    open_app()
    for i in range(30):
        act = activity()
        if "MainActivity" in act:
            print(f"[{i}] reached MainActivity")
            return 0
        _, nodes = dump(f"prep{i}", retries=2) if i else dump("prep0")
        texts = [n["text"] for n in nodes if n["text"].strip()]
        descs = [n["desc"] for n in nodes if n["desc"].strip()]
        print(f"[{i}] {act} texts={texts[:8]}")
        if tap_rid(nodes, "consent_checkbox"):
            time.sleep(0.6)
            tap_rid(nodes, "consent_action")
            continue
        for key in ("While using the app", "Allow", "允许", "下一步", "跳过", "开始使用", "Wait", "知道了"):
            if tap_text(nodes, key, exact=True):
                break
        else:
            if tap_rid(nodes, "consent_action"):
                continue
            print("  no actionable node; stopping")
            return 1
        time.sleep(1.2)
    print("prep timeout")
    return 1


def cmd_regress():
    """针对本轮 5 项 P1 修复的定向回归：滑动/跳过/状态分片数/电池说明/底部弹层。"""
    if not wait_boot():
        print("BOOT TIMEOUT")
        return 2
    adb("shell", "pm", "uninstall", "io.github.wangnan05563.airmirror", timeout=60)
    adb("shell", "pm", "clear", PKG, timeout=60)
    adb("shell", "input", "keyevent", "3")
    time.sleep(2)
    open_app()
    time.sleep(4)
    # 合规三页
    for i in range(8):
        ensure_app()
        _, nodes = dump(f"rg-consent{i}")
        if clear_anr(nodes):
            _, nodes = dump(f"rg-consent{i}b")
        if any("这是什么" in n["text"] or "数据去哪" in n["text"] for n in nodes):
            if tap_text(nodes, "下一步", exact=True):
                time.sleep(2)
                continue
        if any("不能用来做什么" in n["text"] for n in nodes):
            tap_rid(nodes, "consent_checkbox")
            time.sleep(1)
            _, nodes = dump(f"rg-consent{i}c")
            tap_rid(nodes, "consent_action")
            time.sleep(2.5)
            break
    ensure_app()
    time.sleep(2)
    _, nodes = dump("rg-onb1")
    if clear_anr(nodes):
        _, nodes = dump("rg-onb1")
    idx1 = next((n["text"] for n in nodes if n["text"] in ("01", "02", "03")), "?")
    skip = next((n for n in nodes if n["text"] == "跳过"), None)
    print(f"RESULT onboarding_page={idx1} skip={skip['w'] if skip else 0}x{skip['h'] if skip else 0}px clickable={skip['clickable'] if skip else None}")

    W, H = screen_size()
    for step in (1, 2):
        ensure_app()
        swipe(int(W * 0.86), int(H * 0.55), int(W * 0.12), int(H * 0.55), 220)
        time.sleep(1)
        _, nodes = dump(f"rg-swipe{step}")
        idx = next((n["text"] for n in nodes if n["text"] in ("01", "02", "03")), "?")
        print(f"RESULT swipe{step} -> page {idx}")

    _, nodes = dump("rg-onbend")
    if not tap_text(nodes, "开始使用", exact=True):
        tap_text(nodes, "跳过", exact=True)
    time.sleep(2)
    adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity", timeout=60)
    time.sleep(3)
    for i in range(3):
        _, nodes = dump(f"rg-perm{i}")
        if clear_anr(nodes):
            _, nodes = dump(f"rg-perm{i}b")
        if not tap_text(nodes, "While using the app", exact=True) and not tap_text(nodes, "Allow", exact=True):
            break
        time.sleep(1.5)
    time.sleep(1)
    print("RESULT activity_after_onboarding", activity())

    _, nodes = dump("rg-main")
    if clear_anr(nodes):
        _, nodes = dump("rg-main")
    print("RESULT main_texts", [n["text"] for n in nodes if n["text"]][:6])
    tap_text(nodes, "开始值守", exact=True)
    time.sleep(2.5)
    _, nodes = dump("rg-battery")
    if clear_anr(nodes):
        _, nodes = dump("rg-battery")
    has_explain = any("让值守在后台持续运行" in n["text"] for n in nodes)
    print("RESULT battery_explain_dialog", has_explain, [n["text"] for n in nodes if n["text"]][:6])
    if has_explain:
        tap_text(nodes, "去授权", exact=True)
        time.sleep(3)
        print("RESULT after_allow ->", activity())
        back()
        time.sleep(2)
    time.sleep(35)
    _, nodes = dump("rg-status")
    status = next((n["text"] for n in nodes if "值守中" in n["text"] or "待机" in n["text"]), "?")
    print("RESULT status_line", status)

    recover()
    _, nodes = dump("rg-home2")
    tap_text(nodes, "更多", exact=True)
    time.sleep(2)
    _, nodes = dump("rg-sheet")
    texts = [n["text"] for n in nodes if n["text"]]
    print("RESULT more_sheet", texts[:14])
    print("RESULT sheet_is_dialog", any("更多功能" in t for t in texts))
    return 0


def cmd_regress2():
    """从首页继续：电池说明弹窗 → 状态行分片数 → 更多底部弹层。"""
    if not wait_boot():
        print("BOOT TIMEOUT")
        return 2
    for _ in range(3):
        if "MainActivity" in activity():
            break
        back()
    open_app()
    time.sleep(3)
    # 首次进首页会连弹三个权限
    for i in range(4):
        _, nodes = dump(f"r2-perm{i}")
        if not tap_text(nodes, "While using the app", exact=True) and not tap_text(nodes, "Allow", exact=True):
            break
        time.sleep(1.5)
    time.sleep(2)
    print("RESULT main_activity", activity())
    _, nodes = dump("r2-main")
    if clear_anr(nodes):
        _, nodes = dump("r2-main")
    print("RESULT main_texts", [n["text"] for n in nodes if n["text"]][:6])
    if not tap_text(nodes, "开始值守", exact=True):
        print("RESULT no_start_button")
        return 1
    time.sleep(2.5)
    _, nodes = dump("r2-battery")
    if clear_anr(nodes):
        _, nodes = dump("r2-battery")
    texts = [n["text"] for n in nodes if n["text"]]
    print("RESULT battery_dialog", any("让值守在后台持续运行" in t for t in texts), texts[:6])
    if any("让值守在后台持续运行" in t for t in texts):
        tap_text(nodes, "去授权", exact=True)
        time.sleep(3)
        print("RESULT system_page", activity())
        back()
        time.sleep(2)
    time.sleep(40)
    _, nodes = dump("r2-status")
    print("RESULT status_line", next((n["text"] for n in nodes if "值守" in n["text"]), "?"))
    print("RESULT status_has_segment", any("已录" in n["text"] for n in nodes))
    time.sleep(1)
    _, nodes = dump("r2-home")
    if not tap_text(nodes, "更多", exact=True):
        recover()
        _, nodes = dump("r2-home2")
        tap_text(nodes, "更多", exact=True)
    time.sleep(2)
    _, nodes = dump("r2-sheet")
    texts = [n["text"] for n in nodes if n["text"]]
    print("RESULT more_sheet", texts[:12])
    print("RESULT sheet_ok", any("更多功能" in t for t in texts), any("值守之外的七个入口" in t for t in texts))
    return 0


def cmd_regress3():
    """只验证两件事：启动值守前的电池说明弹窗、值守状态行里的已录分片数。"""
    if not wait_boot():
        print("BOOT TIMEOUT")
        return 2
    adb("shell", "pm", "uninstall", "io.github.wangnan05563.airmirror", timeout=60)
    adb("shell", "nohup", "sh", "-c",
        "'while true; do pm disable-user --user 0 io.github.wangnan05563.airmirror >/dev/null 2>&1; sleep 2; done' "
        ">/dev/null 2>&1 &", timeout=30)
    open_app()
    for i in range(4):
        _, nodes = dump(f"r3-perm{i}")
        if not tap_text(nodes, "While using the app", exact=True) and not tap_text(nodes, "Allow", exact=True):
            break
        time.sleep(1.5)
    time.sleep(1)
    _, nodes = dump("r3-main")
    print("RESULT foreground", activity())
    print("RESULT main", [n["text"] for n in nodes if n["text"]][:4])
    tap_text(nodes, "开始值守", exact=True)
    time.sleep(3)
    _, nodes = dump("r3-battery")
    texts = [n["text"] for n in nodes if n["text"]]
    print("RESULT battery_dialog", any("让值守在后台持续运行" in t for t in texts), texts[:5])
    if any("让值守在后台持续运行" in t for t in texts):
        tap_text(nodes, "去授权", exact=True)
        time.sleep(3)
        print("RESULT system_page", activity())
        back()
        time.sleep(2)
    for wait in (20, 30):
        time.sleep(wait)
        _, nodes = dump(f"r3-status{wait}")
        line = next((n["text"] for n in nodes if "值守" in n["text"] or "待机" in n["text"]), "?")
        print(f"RESULT status@{wait}s", line)
    return 0


def cmd_black():
    """黑屏记录页：进入、单击拍照、双击切镜头、长按退出。"""
    if not wait_boot():
        print("BOOT TIMEOUT")
        return 2
    ensure_app()
    _, nodes = dump("bk-home")
    if any(n["text"] == "停止值守" for n in nodes):
        tap_text(nodes, "停止值守", exact=True)
        time.sleep(2)
        _, nodes = dump("bk-stopdlg")
        tap_text(nodes, "停止", exact=True)
        time.sleep(25)
        _, nodes = dump("bk-home2")
    if not tap_text(nodes, "更多", exact=True):
        print("RESULT no_more")
        return 1
    time.sleep(1.5)
    _, nodes = dump("bk-menu")
    tap_text(nodes, "黑屏记录", exact=True)
    time.sleep(3)
    _, nodes = dump("bk-page")
    print("RESULT activity", activity())
    print("RESULT texts", [n["text"] for n in nodes if n["text"]][:6])
    W, H = screen_size()
    # 双击必须在同一次 shell 会话里发两次 tap：分两次 adb 调用间隔会超过双击判定窗口
    adb("shell", f"input tap {W // 2} {H // 2}; sleep 0.12; input tap {W // 2} {H // 2}")
    time.sleep(2)
    _, nodes = dump("bk-lens")
    print("RESULT after_double", [n["text"] for n in nodes if n["text"]][:4])
    time.sleep(2)
    adb("shell", "input", "tap", str(W // 2), str(H // 2))
    time.sleep(2.5)
    _, nodes = dump("bk-photo")
    print("RESULT after_tap", [n["text"] for n in nodes if n["text"]][:4])
    adb("shell", "input", "swipe", str(W // 2), str(H // 2), str(W // 2), str(H // 2), "1200")
    time.sleep(2)
    _, nodes = dump("bk-exit")
    print("RESULT after_longpress", activity(), [n["text"] for n in nodes if n["text"]][:4])
    return 0


def cmd_scan():
    """观看端添加流程：扫码页 / 手动输入配对码弹窗。"""
    if not wait_boot():
        print("BOOT TIMEOUT")
        return 2
    ensure_app()
    _, nodes = dump("sc-home")
    tap_text(nodes, "更多", exact=True)
    time.sleep(1.5)
    _, nodes = dump("sc-menu")
    tap_text(nodes, "我的设备（观看端）", exact=True)
    time.sleep(2.5)
    _, nodes = dump("sc-list")
    tap_text(nodes, "扫码添加", exact=True)
    time.sleep(2.5)
    _, nodes = dump("sc-scan")
    print("RESULT scan_activity", activity())
    print("RESULT scan_texts", [n["text"] for n in nodes if n["text"]][:8])
    back()
    time.sleep(1.5)
    _, nodes = dump("sc-list2")
    tap_text(nodes, "输入配对码", exact=True)
    time.sleep(2)
    _, nodes = dump("sc-code")
    print("RESULT code_dialog", [n["text"] for n in nodes if n["text"]][:8])
    return 0


def cmd_regress4():
    """第 4 轮 P2 定向回归：设置页镜头组/分组顺序、事件空态筛选、设备空态添加入口。"""
    if not wait_boot():
        print("BOOT TIMEOUT")
        return 2
    adb("shell", "pm", "uninstall", "io.github.wangnan05563.airmirror", timeout=60)
    recover()
    time.sleep(2)
    _, nodes = dump("r4-home")
    if clear_anr(nodes):
        _, nodes = dump("r4-home")

    def open_more(label):
        nonlocal nodes
        if not tap_text(nodes, "更多", exact=True):
            recover()
            _, nodes = dump("r4-home2")
            tap_text(nodes, "更多", exact=True)
        time.sleep(1.5)
        _, nodes = dump("r4-menu")
        tap_text(nodes, label, exact=True)
        time.sleep(2.5)
        return dump("r4-page")

    # 1) 设置页
    _, nodes = open_more("设置与诊断")
    texts = [n["text"] for n in nodes if n["text"]]
    sections = [t for t in texts if t in (
        "镜头与画质", "检测与计划", "通知与免打扰", "提醒级别",
        "电源与保活", "安全与隐私", "第三方集成", "健康检查", "版本与更新", "快速开始")]
    lens = next((n for n in nodes if n["rid"].endswith("settings_lens_summary")), None)
    print("RESULT settings_activity", activity())
    print("RESULT settings_sections", sections)
    print("RESULT lens_summary", lens["text"] if lens else "MISSING")
    print("RESULT lens_first", bool(sections) and sections[0] == "镜头与画质")
    W, H = screen_size()
    swipe(W // 2, int(H * 0.78), W // 2, int(H * 0.32))
    _, nodes = dump("r4-settings-s2")
    print("RESULT settings_s2_head", [n["text"] for n in nodes if n["text"]][:8])
    for _ in range(3):
        if "MainActivity" in activity():
            break
        back()

    # 2) 事件回看空态
    _, nodes = open_more("事件回看")
    texts = [n["text"] for n in nodes if n["text"]]
    filter_ids = [n["rid"] for n in nodes if n["rid"].endswith(("type_spinner", "unread_check", "locked_check"))]
    print("RESULT timeline_activity", activity())
    print("RESULT timeline_texts", texts[:8])
    print("RESULT timeline_filters_visible", len(filter_ids))
    for _ in range(3):
        if "MainActivity" in activity():
            break
        back()

    # 3) 设备列表空态
    _, nodes = open_more("我的设备（观看端）")
    texts = [n["text"] for n in nodes if n["text"]]
    add = next((n for n in nodes if n["rid"].endswith("device_list_add")), None)
    empties = [t for t in texts if t in ("扫码添加", "输入配对码", "手动输入地址")]
    print("RESULT device_activity", activity())
    print("RESULT device_texts", texts[:10])
    print("RESULT device_add_visible", bool(add and add["w"] > 0))
    print("RESULT device_empty_entries", empties)
    recover()
    return 0


def cmd_regress5():
    """补充确认：设置页整页分组顺序、首页状态行色点、事件页筛选可恢复。"""
    if not wait_boot():
        print("BOOT TIMEOUT")
        return 2
    recover()
    time.sleep(2)
    ensure_app()
    time.sleep(2)
    _, nodes = dump("r5-home")
    if clear_anr(nodes):
        _, nodes = dump("r5-home")
    status = next((n["text"] for n in nodes if "待机" in n["text"] or "值守" in n["text"]), "?")
    print("RESULT status_line", repr(status))
    print("RESULT status_has_dot", status.startswith("●"))
    tap_text(nodes, "更多", exact=True)
    time.sleep(1.5)
    _, nodes = dump("r5-menu")
    tap_text(nodes, "设置与诊断", exact=True)
    time.sleep(2.5)
    W, H = screen_size()
    seen = []
    for i in range(7):
        _, nodes = dump(f"r5-settings{i}")
        for n in nodes:
            if n["text"] in ("镜头与画质", "检测与计划", "通知与免打扰", "提醒级别", "电源与保活",
                             "安全与隐私", "第三方集成", "健康检查", "版本与更新", "快速开始"):
                if n["text"] not in seen:
                    seen.append(n["text"])
        swipe(W // 2, int(H * 0.78), W // 2, int(H * 0.30))
        time.sleep(1)
    print("RESULT settings_order", seen)
    for _ in range(3):
        if "MainActivity" in activity():
            break
        back()
    recover()
    return 0


def cmd_regress6():
    """事件页：用户主动选筛选后结果为空，筛选条必须还在（否则用户改不回来）。"""
    if not wait_boot():
        print("BOOT TIMEOUT")
        return 2
    recover()
    time.sleep(2)
    ensure_app()
    _, nodes = dump("r6-home")
    tap_text(nodes, "更多", exact=True)
    time.sleep(1.5)
    _, nodes = dump("r6-menu")
    tap_text(nodes, "事件回看", exact=True)
    time.sleep(2.5)
    _, nodes = dump("r6-empty")
    print("RESULT empty_filters", len([n for n in nodes if n["rid"].endswith(("type_spinner", "unread_check", "locked_check"))]))
    spinner = next((n for n in nodes if n["rid"].endswith("type_spinner")), None)
    if spinner:
        tap(spinner)
        time.sleep(1.5)
        _, nodes = dump("r6-spinner")
        tap_text(nodes, "仅运动", exact=True)
        time.sleep(2)
        _, nodes = dump("r6-filtered")
        print("RESULT filtered_filters", len([n for n in nodes if n["rid"].endswith(("type_spinner", "unread_check", "locked_check"))]))
        print("RESULT filtered_texts", [n["text"] for n in nodes if n["text"]][:5])
    recover()
    return 0


def cmd_regress7():
    """第 5 轮：首页最近事件空态、配对码倒计时。"""
    if not wait_boot():
        print("BOOT TIMEOUT")
        return 2
    recover()
    time.sleep(2)
    ensure_app()
    _, nodes = dump("r7-home")
    recent = next((n for n in nodes if n["rid"].endswith("recent_event")), None)
    print("RESULT recent_event", recent["text"] if recent else "MISSING",
          "clickable=", recent["clickable"] if recent else None)
    tap_text(nodes, "更多", exact=True)
    time.sleep(1.5)
    _, nodes = dump("r7-menu")
    tap_text(nodes, "配对与设备", exact=True)
    time.sleep(2.5)
    _, nodes = dump("r7-pairing")
    code = next((n for n in nodes if n["rid"].endswith("pairing_code")), None)
    ttl1 = next((n for n in nodes if n["rid"].endswith("pairing_code_ttl")), None)
    print("RESULT pairing_activity", activity())
    print("RESULT pairing_code", code["text"] if code else "MISSING")
    print("RESULT ttl1", ttl1["text"] if ttl1 else "MISSING")
    time.sleep(8)
    _, nodes = dump("r7-pairing2")
    ttl2 = next((n for n in nodes if n["rid"].endswith("pairing_code_ttl")), None)
    print("RESULT ttl2", ttl2["text"] if ttl2 else "MISSING")
    back()
    recover()
    return 0


def cmd_regress8():
    """第 6 轮：同意页指示器是否随页跳动、远程访问组网指南默认收起/可展开。"""
    if not wait_boot():
        print("BOOT TIMEOUT")
        return 2
    adb("shell", "pm", "clear", PKG, timeout=60)
    adb("shell", "input", "keyevent", "3")
    time.sleep(2)
    open_app()
    time.sleep(4)
    for i in range(3):
        _, nodes = dump(f"r8-consent{i}")
        prog = next((n for n in nodes if n["rid"].endswith("consent_progress")), None)
        print(f"RESULT consent_page{i + 1}_progress", prog["y1"] if prog else "MISSING",
              prog["text"] if prog else "")
        if i < 2:
            tap_text(nodes, "下一步", exact=True)
            time.sleep(2)
        else:
            tap_rid(nodes, "consent_checkbox")
            time.sleep(0.8)
            _, nodes = dump("r8-consent3b")
            prog = next((n for n in nodes if n["rid"].endswith("consent_progress")), None)
            print("RESULT consent_page3_checked_progress", prog["y1"] if prog else "MISSING")
            tap_rid(nodes, "consent_action")
            time.sleep(2.5)
    # 走完引导与权限，落首页
    for i in range(10):
        _, nodes = dump(f"r8-flow{i}")
        if "MainActivity" in activity():
            break
        for key in ("跳过", "开始使用", "While using the app", "Allow", "下一步"):
            if tap_text(nodes, key, exact=True):
                break
        time.sleep(1.5)
    ensure_app()
    time.sleep(2)
    # 首次进首页会连弹相机/麦克风/通知三个权限，先逐个放行，否则后面的点击全打在系统弹窗上
    for i in range(4):
        _, nodes = dump(f"r8-perm{i}")
        if not tap_text(nodes, "While using the app", exact=True) and not tap_text(nodes, "Allow", exact=True):
            break
        time.sleep(1.5)
    time.sleep(1)
    _, nodes = dump("r8-home")
    tap_text(nodes, "更多", exact=True)
    time.sleep(1.5)
    _, nodes = dump("r8-menu")
    tap_text(nodes, "远程访问与诊断", exact=True)
    time.sleep(2.5)
    _, nodes = dump("r8-remote")
    toggle = next((n for n in nodes if n["rid"].endswith("remote_guide_toggle")), None)
    guides = [n["rid"] for n in nodes if n["rid"].endswith(("remote_guide_tailscale", "remote_guide_wireguard"))]
    print("RESULT remote_toggle", toggle["text"] if toggle else "MISSING")
    print("RESULT guides_visible_before", len(guides))
    if toggle:
        tap(toggle)
        time.sleep(1.5)
        _, nodes = dump("r8-remote-open")
        guides2 = [n["rid"] for n in nodes if n["rid"].endswith(("remote_guide_tailscale", "remote_guide_wireguard"))]
        toggle2 = next((n for n in nodes if n["rid"].endswith("remote_guide_toggle")), None)
        print("RESULT guides_visible_after", len(guides2))
        print("RESULT remote_toggle_after", toggle2["text"] if toggle2 else "MISSING")
        W, H = screen_size()
        swipe(W // 2, int(H * 0.78), W // 2, int(H * 0.30))
        _, nodes = dump("r8-remote-scrolled")
        guides3 = [n["rid"] for n in nodes if n["rid"].endswith(("remote_guide_tailscale", "remote_guide_wireguard"))]
        print("RESULT guides_visible_scrolled", guides3)
    back()
    recover()
    return 0


def cmd_regress9():
    """第 6 轮收尾：远程页按钮文案不再被强制大写。"""
    if not wait_boot():
        print("BOOT TIMEOUT")
        return 2
    recover()
    time.sleep(2)
    ensure_app()
    _, nodes = dump("r9-home")
    tap_text(nodes, "更多", exact=True)
    time.sleep(1.5)
    _, nodes = dump("r9-menu")
    tap_text(nodes, "远程访问与诊断", exact=True)
    time.sleep(2.5)
    _, nodes = dump("r9-remote")
    toggle = next((n for n in nodes if n["rid"].endswith("remote_guide_toggle")), None)
    print("RESULT toggle_text", repr(toggle["text"]) if toggle else "MISSING")
    copy_btn = next((n for n in nodes if n["rid"].endswith("remote_copy")), None)
    print("RESULT copy_button", repr(copy_btn["text"]) if copy_btn else "MISSING",
          "clickable=", copy_btn["clickable"] if copy_btn else None)
    if copy_btn:
        # 空态下点复制只会提示“地址为空”，先真的填一个地址再点，才能验证剪贴板写入
        target_box = next((n for n in nodes if n["rid"].endswith("remote_target")), None)
        if target_box and not (target_box["text"] or "").strip():
            # 空态下点复制只会提示“地址为空”，先真的填一个地址再点，才能验证剪贴板写入
            tap(target_box)
            time.sleep(0.8)
            adb("-s", SER, "shell", "input", "text", "192.168.1.5:8080")
            time.sleep(0.6)
            adb("-s", SER, "shell", "input", "keyevent", "4")
            time.sleep(1.0)
        print("RESULT target_text", repr(target_box["text"]) if target_box else "MISSING")
        tap(copy_btn)
        time.sleep(1.2)
        print("RESULT after_copy_tap", activity())
        log = adb("-s", SER, "logcat", "-d", "-t", "200")
        line = next((l for l in log.splitlines() if "remote address copied" in l), "")
        print("RESULT copy_log", line.strip()[-60:] if line else "MISSING")
    if toggle:
        tap(toggle)
        time.sleep(1.5)
        _, nodes = dump("r9-open")
        toggle2 = next((n for n in nodes if n["rid"].endswith("remote_guide_toggle")), None)
        print("RESULT toggle_text_after", repr(toggle2["text"]) if toggle2 else "MISSING")
    back()
    recover()
    return 0


def cmd_stopguard():
    """收尾：把正在运行的值守停掉，确认导出回执，避免模拟器一直录制。"""
    if not wait_boot():
        print("BOOT TIMEOUT")
        return 2
    recover()
    time.sleep(2)
    ensure_app()
    _, nodes = dump("st-home")
    if not tap_text(nodes, "停止值守", exact=True):
        print("RESULT no_guard_running")
        return 0
    time.sleep(2)
    _, nodes = dump("st-confirm")
    tap_text(nodes, "停止", exact=True)
    time.sleep(25)
    _, nodes = dump("st-after")
    print("RESULT after_stop", [n["text"] for n in nodes if n["text"]][:4])
    return 0


def cmd_regress10():
    """P2-17 收尾：等满 5 分钟，确认配对码到点自动换新、倒计时重置。"""
    if not wait_boot():
        print("BOOT TIMEOUT")
        return 2
    recover()
    time.sleep(2)
    ensure_app()
    _, nodes = dump("r10-home")
    tap_text(nodes, "更多", exact=True)
    time.sleep(1.5)
    _, nodes = dump("r10-menu")
    tap_text(nodes, "配对与设备", exact=True)
    time.sleep(2.5)
    _, nodes = dump("r10-t0")
    code1 = next((n for n in nodes if n["rid"].endswith("pairing_code")), None)
    ttl1 = next((n for n in nodes if n["rid"].endswith("pairing_code_ttl")), None)
    print("RESULT code_t0", code1["text"] if code1 else "MISSING")
    print("RESULT ttl_t0", ttl1["text"] if ttl1 else "MISSING")
    time.sleep(310)
    _, nodes = dump("r10-t1")
    code2 = next((n for n in nodes if n["rid"].endswith("pairing_code")), None)
    ttl2 = next((n for n in nodes if n["rid"].endswith("pairing_code_ttl")), None)
    print("RESULT code_t1", code2["text"] if code2 else "MISSING")
    print("RESULT ttl_t1", ttl2["text"] if ttl2 else "MISSING")
    print("RESULT code_changed",
          bool(code1 and code2 and code1["text"] != code2["text"]))
    back()
    recover()
    return 0


def cmd_regress11():
    """P2-10 收尾：撤销相机权限并连续拒绝，确认横幅能把人送到应用详情页。"""
    if not wait_boot():
        print("BOOT TIMEOUT")
        return 2
    adb("shell", "pm", "revoke", PKG, "android.permission.CAMERA", timeout=60)
    adb("shell", "am", "force-stop", PKG, timeout=60)
    time.sleep(1)
    open_app()
    time.sleep(3)
    for i in range(3):
        _, nodes = dump(f"r11-perm{i}")
        if not tap_text(nodes, "Don’t allow", exact=True):
            tap_text(nodes, "Don't allow", exact=True)
        time.sleep(1.5)
    _, nodes = dump("r11-main")
    banner = next((n for n in nodes if n["rid"].endswith("banner_permission")), None)
    print("RESULT camera_banner", banner["text"] if banner else "MISSING")
    if banner:
        tap(banner)
        time.sleep(3)
        print("RESULT banner_target", activity())
        back()
        time.sleep(2)
    adb("shell", "pm", "grant", PKG, "android.permission.CAMERA", timeout=60)
    adb("shell", "am", "force-stop", PKG, timeout=60)
    time.sleep(1)
    open_app()
    return 0


def cmd_pages():
    if not wait_boot():
        print("BOOT TIMEOUT")
        return 2
    print("boot ok, focus:", activity())
    # 这套系统镜像每次冷启动都会自动装 airmirror 并抢前台，先清掉
    adb("shell", "pm", "uninstall", "io.github.wangnan05563.airmirror", timeout=60)
    open_app()
    print("activity:", activity())
    _, nodes = dump("main")
    if clear_anr(nodes):
        _, nodes = dump("main")
    if not tap_text(nodes, "更多", exact=True):
        print("no 更多 button; current:", [n["text"] for n in nodes if n["text"]][:20])
        return 1
    time.sleep(1.5)
    _, nodes = dump("more")
    items = [n["text"] for n in nodes if n["text"].strip()]
    print("more menu:", items)

    results = {}
    for i, label in enumerate(MENU_ITEMS):
        tag = f"m{i}"
        # 重新回到首页再进菜单，保证入口干净
        recover()
        _, nodes = dump(tag + "-home")
        if clear_anr(nodes):
            _, nodes = dump(tag + "-home")
        if not tap_text(nodes, "更多", exact=True):
            recover()
            _, nodes = dump(tag + "-home2")
            if not tap_text(nodes, "更多", exact=True):
                print(f"{label}: cannot reopen menu")
                continue
        time.sleep(1.5)
        _, nodes = dump(tag + "-menu")
        entry = tap_text(nodes, label, exact=True)
        if not entry:
            print(f"{label}: no entry")
            continue
        time.sleep(2.5)
        act = activity()
        print(f"{label} -> {act}")
        nodes, txt = snapshot(tag)
        results[label] = dict(activity=act, texts=txt[:80],
                              clickable=[n["text"] or n["desc"] for n in nodes if n["clickable"] and (n["text"] or n["desc"])][:40])
        # 尝试滚动一屏，看是否有更多内容
        if any(n["scrollable"] for n in nodes):
            W, H = screen_size()
            swipe(W // 2, int(H * 0.78), W // 2, int(H * 0.38))
            nodes, txt2 = snapshot(tag + "-s2")
            results[label]["texts_s2"] = [t for t in txt2 if t not in txt][:40]
        # 退出该页
        for _ in range(3):
            if "MainActivity" in activity():
                break
            back()
    with open(os.path.join(OUT, "pages.json"), "w", encoding="utf-8") as f:
        json.dump(results, f, ensure_ascii=False, indent=1)
    print("done; artifacts in", OUT)
    return 0


def cmd_audit():
    _, nodes = dump("last")
    for it in audit("last", nodes):
        print(it["code"], it["detail"])
    return 0


def cmd_probe():
    print("activity:", activity())
    _, nodes = dump("probe")
    for n in nodes:
        if n["text"] or n["clickable"]:
            print(f"{n['text'][:60]!r:70} click={n['clickable']} {n['x1']},{n['y1']} {n['w']}x{n['h']} rid={n['rid']}")
    return 0


def cmd_shot(name="manual"):
    print(shot(name))
    return 0


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    cmd = sys.argv[1] if len(sys.argv) > 1 else "pages"
    sys.exit({"pages": cmd_pages, "prep": cmd_prep, "regress": cmd_regress, "regress2": cmd_regress2,
              "regress3": cmd_regress3, "regress4": cmd_regress4, "regress5": cmd_regress5,
              "regress6": cmd_regress6, "regress7": cmd_regress7, "regress8": cmd_regress8,
              "regress9": cmd_regress9, "regress10": cmd_regress10, "regress11": cmd_regress11,
              "stopguard": cmd_stopguard, "black": cmd_black, "scan": cmd_scan,
              "audit": cmd_audit, "probe": cmd_probe, "shot": cmd_shot}[cmd]())
