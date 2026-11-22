"""断点与大字体巡检：改 wm size / font_scale 后逐页看是否有越界、省略号、触控区过小。

用法：python tools/verify/layout_matrix.py
产物：tools/verify/ui-artifacts/layout-<tag>-*.xml / .png
"""
import time
import xml.etree.ElementTree as ET

import ui_walk as u

CASES = [
    ("600dp", 1200, 2133, 320, 1.0),
    ("840dp", 1680, 2987, 320, 1.0),
    ("font130", 720, 1280, 320, 1.3),
]


def set_display(w, h, density):
    u.adb("shell", "wm", "size", f"{w}x{h}")
    u.adb("shell", "wm", "density", str(density))
    time.sleep(2)


def set_font(scale):
    u.adb("shell", "settings", "put", "system", "font_scale", str(scale))
    time.sleep(3)


def open_page(entry_text, dump_tag):
    _, nodes = u.dump(f"{dump_tag}-home")
    u.tap_text(nodes, "更多", exact=True)
    time.sleep(1.5)
    _, nodes = u.dump(f"{dump_tag}-menu")
    if not u.tap_text(nodes, entry_text, exact=True):
        print(f"RESULT {dump_tag}_entry MISSING {entry_text}")
        return None
    time.sleep(2.0)
    _, nodes = u.dump(dump_tag)
    return nodes


def collect_settings(tag, w, h):
    """连续上滑收集设置页 10 个分组标题，并记录是否出现省略号。"""
    known = {
        "镜头与画质", "检测与计划", "通知与免打扰", "提醒级别", "电源与保活",
        "安全与隐私", "第三方集成", "健康检查", "版本与更新", "快速开始",
    }
    titles, ellipsis = [], []
    for i in range(9):
        _, nodes = u.dump(f"{tag}-s{i}")
        for n in nodes:
            if n["text"] in known and n["text"] not in titles:
                titles.append(n["text"])
            if n["text"].rstrip().endswith(("…", "...")):
                ellipsis.append(n["text"][:50])
        u.swipe(w // 2, int(h * 0.78), w // 2, int(h * 0.28), 400)
        time.sleep(1)
    print(f"RESULT {tag}_settings_sections {titles}")
    print(f"RESULT {tag}_settings_ellipsis {len(ellipsis)}")


def run_case(tag, w, h, density, font):
    print(f"\n===== {tag} {w}x{h} @{density} font={font} =====")
    set_display(w, h, density)
    set_font(font)
    u.adb("shell", "am", "force-stop", "com.quietsentry.app")
    time.sleep(1)
    u.ensure_app()
    time.sleep(3)
    u.shot(f"layout-{tag}-home")
    _, home = u.dump(f"layout-{tag}-home")
    issues = []
    for n in home:
        label = n["text"] or n["desc"] or n["rid"] or n["cls"]
        if n["x2"] > w + 1 or n["x1"] < 0 or n["y2"] > h + 1:
            issues.append(f"OUT {label[:30]} {n['x1']},{n['y1']},{n['x2']},{n['y2']}")
        if n["text"].rstrip().endswith(("…", "...")):
            issues.append(f"ELLIPSIS {n['text'][:50]}")
        if n["clickable"] and n["h"] / 2 < 44:
            issues.append(f"SMALL {label[:30]} h={n['h']}px")
    for it in issues:
        print("  ISSUE", it)
    print(f"RESULT {tag}_home_issues {len(issues)}")
    print(f"RESULT {tag}_home_texts {[n['text'][:24] for n in home if n['text']][:8]}")

    settings = open_page("设置与诊断", f"layout-{tag}-settings")
    if settings:
        u.shot(f"layout-{tag}-settings")
        collect_settings(tag, w, h)
        u.back()
    time.sleep(1)

    remote = open_page("远程访问与诊断", f"layout-{tag}-remote")
    if remote:
        u.shot(f"layout-{tag}-remote")
        target = next((n for n in remote if n["rid"].endswith("remote_target")), None)
        copy_btn = next((n for n in remote if n["rid"].endswith("remote_copy")), None)
        print(f"RESULT {tag}_remote_target {target['w']}x{target['h']} text={target['text']!r}" if target else f"RESULT {tag}_remote_target MISSING")
        print(f"RESULT {tag}_remote_copy {copy_btn['w']}x{copy_btn['h']}" if copy_btn else f"RESULT {tag}_remote_copy MISSING")
        u.back()


def main():
    try:
        for case in CASES:
            run_case(*case)
    finally:
        print("\n===== restore =====")
        set_display(720, 1280, 320)
        set_font(1.0)
        u.adb("shell", "am", "force-stop", "com.quietsentry.app")
        time.sleep(1)
        u.ensure_app()
        print("RESULT restored", u.activity())


if __name__ == "__main__":
    main()
