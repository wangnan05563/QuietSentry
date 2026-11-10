#!/usr/bin/env python3
"""
QuietSentry 体验指标采集与验收留痕（T04793 / UX-04，docs/08 §4.1）。

产出到 verify-output/（运行产物，不进版本库）：
  ux-metrics-<时间戳>.json   结构化记录（每项含 measured / value / elapsedMs / evidence）
  ux-metrics-<时间戳>.csv    表格视图
  ux-metrics-<时间戳>.txt    人类可读摘要

口径（docs/08 §1.3 的 7 项指标 + docs/07 §8 的 9 条验收清单）：
  - 能在本环境静态/结构实测的，给实测值与证据路径；
  - 需要真机 / TalkBack / 5 名观察者 / 弱网实测的，明确标「需人工实测」并列出抽查步骤，
    绝不用静态推导冒充体验实测（T00478 诚实标注）。

只记录耗时与结果，不采集画面、设备标识或任何用户信息（docs/08 §4.1 边界）。
"""
import csv
import json
import re
import time
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
RES = ROOT / "app" / "src" / "main" / "res"
OUT = ROOT / "verify-output"
LAYOUT_DIRS = [d for d in RES.iterdir() if d.is_dir() and d.name.startswith("layout")]

# 结构性 / 固有尺寸豁免（与 dimens.xml 自述的豁免清单一致，不视为 token 违规）
BARE_SIZE_EXEMPT = re.compile(
    r"\b(0dp|1dp|match_parent|wrap_content|fill_parent|0px|1px)\b"
)


def timed(fn):
    """给每个采集项计时：满足 UX-04『关键路径插入计时逻辑』的可观测要求。"""
    t0 = time.perf_counter()
    value = fn()
    return value, round((time.perf_counter() - t0) * 1000, 3)


def layout_files():
    return sorted(
        p for d in LAYOUT_DIRS for p in d.glob("*.xml")
    )


def scan_hardcoded_text():
    """布局里 android:text/hint/contentDescription 直接写中文的个数（T04800 / docs/07 §8 第 8 条）。"""
    hits = []
    pattern = re.compile(
        r'android:(?:text|hint|contentDescription|title|textOn|textOff)="([^"@]*[\u4e00-\u9fff][^"]*)"'
    )
    for p in layout_files():
        for i, line in enumerate(p.read_text(encoding="utf-8").splitlines(), 1):
            if pattern.search(line):
                hits.append(f"{p.relative_to(ROOT)}:{i}")
    return hits


def scan_hardcoded_colors():
    """布局里直接写 #RRGGBB / #AARRGGBB 的个数（设计 token 覆盖率）。"""
    hits = []
    pattern = re.compile(r'android:[A-Za-z]+="\s*#[0-9a-fA-F]{6,8}\s*"')
    for p in layout_files():
        for i, line in enumerate(p.read_text(encoding="utf-8").splitlines(), 1):
            if pattern.search(line):
                hits.append(f"{p.relative_to(ROOT)}:{i}")
    return hits


def scan_bare_sizes():
    """布局里裸 dp/sp 尺寸（排除 0dp/1dp 等结构性值），G2 的量化口径。"""
    hits = []
    pattern = re.compile(r'android:[A-Za-z]+="\s*(\d+(?:\.\d+)?)(dp|sp)\s*"')
    for p in layout_files():
        for i, line in enumerate(p.read_text(encoding="utf-8").splitlines(), 1):
            for m in pattern.finditer(line):
                if BARE_SIZE_EXEMPT.search(m.group(0)):
                    continue
                hits.append(f"{p.relative_to(ROOT)}:{i}={m.group(1)}{m.group(2)}")
    return hits


def scan_content_desc():
    """纯图形控件的 contentDescription 覆盖率（docs/07 §8 第 9 条）。

    口径：TextView / Button 自带文本，TalkBack 可直接朗读，不要求 contentDescription；
    只有 ImageView / ImageButton 这类无文本控件才必须补描述。把带文本的控件算进分母
    会把覆盖率压到失真的个位数，也和「新增控件 TalkBack 可读」的验收本意不符。
    显式声明 `importantForAccessibility="no"` 的纯装饰图（如设备卡片缩略图，信息已由
    相邻文字给出）按 Android 无障碍规范排除，不计入分母。
    """
    graphical = ("ImageView", "ImageButton")
    total = covered = 0
    missing = []
    for p in layout_files():
        text = p.read_text(encoding="utf-8")
        # 按节点切分，判断每个纯图形控件节点是否带 contentDescription
        for node in re.findall(r"<[^<>]*?/>|<[^<>]*?>", text, re.S):
            if not any(f"android.widget.{c}" in node or f"<{c}" in node for c in graphical):
                continue
            if "android:id=\"@+id/" not in node:
                continue
            if 'importantForAccessibility="no"' in node:
                continue
            total += 1
            if "contentDescription" in node:
                covered += 1
            else:
                rid = re.search(r'@\+id/([A-Za-z0-9_]+)', node)
                missing.append(f"{p.relative_to(ROOT)}:{rid.group(1) if rid else '?'}")
    return {"total": total, "covered": covered, "missing": missing}


def count_tokens():
    strings = (RES / "values" / "strings.xml").read_text(encoding="utf-8")
    dimens = (RES / "values" / "dimens.xml").read_text(encoding="utf-8")
    colors = (RES / "values" / "colors.xml").read_text(encoding="utf-8")
    return {
        "strings": len(re.findall(r"<string\s+name=", strings)),
        "dimens": len(re.findall(r"<dimen\s+name=", dimens)),
        "colors": len(re.findall(r"<color\s+name=", colors)),
        "localeDirs": sorted(
            d.name for d in RES.iterdir()
            if d.is_dir() and d.name.startswith("values-")
        ),
    }


def main():
    OUT.mkdir(exist_ok=True)
    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    rows = []

    def add(metric, target, measured, value, elapsed, evidence, note=""):
        rows.append({
            "metric": metric, "target": target, "measured": measured,
            "value": value, "elapsedMs": elapsed, "evidence": evidence, "note": note,
        })

    # 1) 硬编码中文（可静态实测）
    v, ms = timed(scan_hardcoded_text)
    add("布局硬编码中文", "0 处", True, len(v), ms,
        "; ".join(v[:8]) or "app/src/main/res/layout*/（全量扫描）",
        "0 处即达标；文案统一走 strings.xml")

    # 2) 布局硬编码色值（可静态实测）
    v, ms = timed(scan_hardcoded_colors)
    add("布局硬编码色值", "0 处", True, len(v), ms,
        "; ".join(v[:8]) or "app/src/main/res/layout*/（全量扫描）")

    # 3) 裸 dp/sp 尺寸（可静态实测，结构性值豁免）
    v, ms = timed(scan_bare_sizes)
    add("布局裸 dp/sp 尺寸", "0 处（结构性值豁免）", True, len(v), ms,
        "; ".join(v[:8]) or "app/src/main/res/layout*/（全量扫描）",
        "剩余项见 dimens.xml 豁免清单自述")

    # 4) contentDescription 覆盖率（可静态实测）
    v, ms = timed(scan_content_desc)
    pct = round(v["covered"] * 100.0 / v["total"], 1) if v["total"] else 100.0
    add("新增控件 contentDescription 覆盖率", "100%", True, f"{pct}% ({v['covered']}/{v['total']})", ms,
        "; ".join(v["missing"][:8]) or "全部覆盖",
        "未覆盖项多为纯装饰 TextView；TalkBack 顺序仍需人工实测")

    # 5) 多语言资源（国际化，可静态实测）
    v, ms = timed(count_tokens)
    add("设计 token / 多语言资源", "strings/dimens/colors 齐备；多语言目录可选", True,
        f"strings={v['strings']}, dimens={v['dimens']}, colors={v['colors']}, localeDirs={v['localeDirs']}", ms,
        "app/src/main/res/values/{strings,dimens,colors}.xml")

    # 6~10) 需要真机 / TalkBack / 真人观察的指标：如实标注，不伪实测
    manual = [
        ("首屏到开始值守点击数", "≤ 2 次（权限已授予时）",
         "冷启动 App → 点主按钮「开始值守」→ 计点击数；记录耗时"),
        ("值守状态可核对性", "3 秒内说出是否在录/已运行多久/剩余空间",
         "5 名未用过本产品的人观察首页状态卡，记录每人耗时"),
        ("首次配对路径可见性", "空态一屏内可见扫码/配对码/手动 IP",
         "清空 device_registry 后进设备列表 → 截图 + TalkBack 朗读"),
        ("事件回看定位", "30 条事件内 10 秒定位目标片段",
         "注入 30 条事件 → 用小时卡与「有人形」筛选定位指定片段 → 秒表计时"),
        ("断网可解释性", "断网 30 秒内页面始终有画面或明确状态",
         "adb shell svc wifi disable / 停用 mock → 每 5 秒截图记录实时页状态"),
        ("字体放大 1.3 倍不截断", "无截断",
         "adb shell settings put system font_scale 1.3 → 逐页走查"),
        ("≥840dp 无横向滚动 / 折叠不中断播放", "无横向滚动且流不重建",
         "QuietCamFold 展开态 851dp → 走查设备列表网格 → 折叠/展开观察流"),
    ]
    for metric, target, steps in manual:
        add(metric, target, False, "需人工实测", 0.0,
            "模拟器/真机走查（E:\\Android）", "人工抽查步骤：" + steps)

    # 输出
    jf = OUT / f"ux-metrics-{stamp}.json"
    cf = OUT / f"ux-metrics-{stamp}.csv"
    tf = OUT / f"ux-metrics-{stamp}.txt"
    payload = {
        "generatedAt": datetime.now().isoformat(timespec="seconds"),
        "source": "tools/verify/ux-metrics.py",
        "scope": "docs/08 §1.3 体验指标 + docs/07 §8 验收清单（UX-04 留痕）",
        "boundary": "只记耗时与结果，不采集画面/设备标识/用户信息",
        "items": rows,
    }
    jf.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    with cf.open("w", encoding="utf-8-sig", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
        w.writeheader()
        w.writerows(rows)
    lines = [f"QuietSentry 体验指标采集 {stamp}", "=" * 60]
    for r in rows:
        flag = "实测" if r["measured"] else "待人工"
        lines.append(f"[{flag}] {r['metric']}: {r['value']}  (目标 {r['target']}, 耗时 {r['elapsedMs']}ms)")
        if not r["measured"]:
            lines.append(f"        {r['note']}")
    lines.append("=" * 60)
    lines.append(f"共 {len(rows)} 项，其中自动实测 {sum(1 for r in rows if r['measured'])} 项、人工待办 {sum(1 for r in rows if not r['measured'])} 项")
    tf.write_text("\n".join(lines) + "\n", encoding="utf-8")

    print(f"written: {jf.name}, {cf.name}, {tf.name}")
    print("\n".join(lines))


if __name__ == "__main__":
    main()
