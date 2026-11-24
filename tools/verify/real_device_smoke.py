#!/usr/bin/env python3
"""QuietSentry 真机基线冒烟（黑盒：adb + uiautomator dump）。

用途：
    把“8 品牌矩阵”里每台机器都要重复的基线动作固化成一次可复跑的证据采集：
    安装/启动、合规与引导、授权、开始值守、锁屏 5 分钟、停止并保存、通知清理、
    设置页镜头摘要与一键诊断。结果同时写 JSON/CSV/Markdown，截图与 UI dump 留档。

用法：
    python tools/verify/real_device_smoke.py --serial <serial> --apk <apk> --label "小米/Redmi"
    python tools/verify/real_device_smoke.py --serial <serial> --duration-min 1 --no-screen-off
    python tools/verify/real_device_smoke.py --all --apk <apk> --include-emulators

说明：
    - 默认锁屏跑满 --duration-min（矩阵要求 5 分钟），每 30 秒采一次 pid/服务/通知/温度。
    - 真机 release 包不能用 run-as 读私有目录，本脚本用相册导出目录与界面状态计数；
      7 天长期采样请用 stability-7d.ps1（已对 release 包做了相册回退）。
    - 本脚本只覆盖“无后置镜头 / 录制档位回退 / 相机被占用 / 强杀恢复 / 白名单引导”
      中可由基线链路观察到的部分；特殊分支仍需按 docs/05 手工补测，不能拿本脚本冒充全矩阵。
"""

import argparse
import csv
import json
import os
import re
import shutil
import subprocess
import sys
import time
import traceback
from datetime import datetime

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
sys.path.insert(0, HERE)
import ui_walk  # noqa: E402  (复用已验证的 dump/tap/open_app 实现)

PKG = "com.quietsentry.app"
DEFAULT_OUT = os.path.join(ROOT, "verify-output", "real-device")


def resolve_adb():
    candidate = os.environ.get("QS_ADB", "")
    if candidate and os.path.isfile(candidate):
        return candidate
    found = shutil.which("adb")
    if found:
        return found
    for path in (
        r"E:\Android\sdk\platform-tools\adb.exe",
        r"C:\Android\platform-tools\adb.exe",
        r"C:\platform-tools\adb.exe",
    ):
        if os.path.isfile(path):
            return path
    return "adb"


ADB = resolve_adb()
STAMP = datetime.now().strftime("%Y%m%d-%H%M%S")


def safe_name(text):
    return re.sub(r"[^0-9A-Za-z._-]+", "-", text).strip("-") or "device"


def parse_devices():
    try:
        out = subprocess.run(
            [ADB, "devices"], capture_output=True, text=True, timeout=30
        ).stdout
    except Exception as exc:
        raise RuntimeError(f"adb devices 失败：{exc}") from exc
    serials = []
    for line in out.splitlines()[1:]:
        parts = line.split()
        if len(parts) >= 2 and parts[1] == "device":
            serials.append(parts[0])
    return serials


class DeviceRunner:
    def __init__(self, serial, out_root, label="", apk="", fresh=False, skip_install=False, expect_version=""):
        self.serial = serial
        self.label = label or serial
        self.apk = apk
        self.fresh = fresh
        self.skip_install = skip_install
        self.expect_version = (expect_version or "").strip()
        self.out = os.path.join(out_root, safe_name(serial), STAMP)
        self.ui = os.path.join(self.out, "ui")
        self.shots = os.path.join(self.out, "screenshots")
        self.logs = os.path.join(self.out, "logs")
        for path in (self.out, self.ui, self.shots, self.logs):
            os.makedirs(path, exist_ok=True)
        ui_walk.ADB = ADB
        ui_walk.SER = serial
        ui_walk.OUT = self.ui
        ui_walk._SIZE = None
        self.info = {}
        self.samples = []
        self.diag = {}
        self.notes = []
        self.fatal_lines = []
        self.error = ""
        self.original_settings = None
        self.exported_before = None

    # ---------- adb / UI 基元 ----------
    def adb(self, *args, timeout=90, binary=False):
        return ui_walk.adb(*args, timeout=timeout, binary=binary)

    def shell(self, *args, timeout=90, binary=False):
        return self.adb("shell", *args, timeout=timeout, binary=binary)

    def dump(self, name, retries=2):
        try:
            # 部分 ROM/模拟器会把“保持唤醒”忽略掉，每次抓 UI 前确认屏幕是亮的；
            # 否则 dump 能读到界面但 input tap 全部落空，表现为“点了没反应”。
            if self.power_state() != "Awake":
                self.wake()
            _, nodes = ui_walk.dump(name, retries=retries)
            return nodes
        except Exception as exc:
            self.notes.append(f"dump {name} 失败：{exc}")
            return []

    def shot(self, name):
        # ui_walk.shot 固定写到 ui_walk.OUT；这里写到 screenshots/ 下，便于交付
        try:
            data = self.adb("exec-out", "screencap", "-p", binary=True, timeout=90)
            if not data.startswith(b"\x89PNG"):
                raise RuntimeError("screencap not png")
            path = os.path.join(self.shots, f"{name}.png")
            with open(path, "wb") as handle:
                handle.write(data)
            return path
        except Exception as exc:
            self.notes.append(f"截图 {name} 失败：{exc}")
            return ""

    def activity(self):
        return ui_walk.activity()

    def open_app(self):
        ui_walk.open_app()

    def power_state(self):
        out = self.shell("dumpsys", "power", timeout=15)
        match = re.search(r"mWakefulness=(\w+)", out)
        return match.group(1) if match else "?"

    def wake(self):
        # KEYCODE_WAKEUP(224) 在部分模拟器/ROM 上无效，统一用电源键切换：
        # 先读 mWakefulness，只有 Asleep 才按 26，避免把已经亮着的屏幕按灭。
        # 唤醒/解锁不是关键路径：adb 在模拟器上偶尔会卡在 dismiss-keyguard，
        # 这里只记 note，不让整个 arm 因一个 30s 超时而失败。
        try:
            state = self.power_state()
        except Exception as exc:
            self.notes.append(f"读取电源状态失败：{exc}")
            state = "?"
        if state != "Awake":
            try:
                self.shell("input", "keyevent", "26", timeout=15)
            except Exception as exc:
                self.notes.append(f"按电源键失败：{exc}")
            time.sleep(1)
        for args in (("wm", "dismiss-keyguard"), ("input", "keyevent", "82")):
            try:
                self.shell(*args, timeout=10)
            except Exception as exc:
                self.notes.append(f"{' '.join(args)} 失败：{exc}")
        time.sleep(1)

    def sleep_screen(self):
        if self.power_state() == "Awake":
            self.shell("input", "keyevent", "26", timeout=30)
            time.sleep(2)

    def _get_setting(self, namespace, key):
        try:
            value = self.shell("settings", "get", namespace, key, timeout=15).strip()
        except Exception:
            return ""
        return "" if value in ("null", "None") else value

    def _capture_settings(self):
        if self.original_settings is not None:
            return
        self.original_settings = {
            ("system", "screen_off_timeout"): self._get_setting("system", "screen_off_timeout"),
            ("global", "stay_on_while_plugged_in"): self._get_setting("global", "stay_on_while_plugged_in"),
            ("global", "window_animation_scale"): self._get_setting("global", "window_animation_scale"),
            ("global", "transition_animation_scale"): self._get_setting("global", "transition_animation_scale"),
            ("global", "animator_duration_scale"): self._get_setting("global", "animator_duration_scale"),
        }

    def restore_settings(self):
        """恢复运行前的设备设置；这些是系统级持久设置，不能只改不还。"""
        if not self.original_settings:
            return
        for (namespace, key), value in self.original_settings.items():
            try:
                if value:
                    self.shell("settings", "put", namespace, key, value, timeout=15)
                else:
                    # 原值为空/null 时不能跳过：跳过等于把本次写入的 7/1800000/0 永久留下。
                    self.shell("settings", "delete", namespace, key, timeout=15)
            except Exception as exc:
                self.notes.append(f"恢复 {namespace}/{key} 失败：{exc}")
        self.original_settings = None

    def keep_awake(self, enabled):
        if not enabled:
            self.restore_settings()
            return
        self._capture_settings()
        for args in (
            ("settings", "put", "global", "stay_on_while_plugged_in", "7"),
            ("settings", "put", "system", "screen_off_timeout", "1800000"),
            ("settings", "put", "global", "window_animation_scale", "0"),
            ("settings", "put", "global", "transition_animation_scale", "0"),
            ("settings", "put", "global", "animator_duration_scale", "0"),
        ):
            try:
                self.shell(*args, timeout=15)
            except Exception as exc:
                self.notes.append(f"keep_awake(true) 失败：{exc}")

    # ---------- 设备信息 ----------
    def prop(self, key):
        return self.shell("getprop", key, timeout=30).strip()

    def collect_info(self):
        self.info = {
            "serial": self.serial,
            "label": self.label,
            "manufacturer": self.prop("ro.product.manufacturer"),
            "brand": self.prop("ro.product.brand"),
            "model": self.prop("ro.product.model"),
            "device": self.prop("ro.product.device"),
            "android": self.prop("ro.build.version.release"),
            "sdk": self.prop("ro.build.version.sdk"),
            "securityPatch": self.prop("ro.build.version.security_patch"),
            "abi": self.prop("ro.product.cpu.abi"),
            "fingerprint": self.prop("ro.build.fingerprint"),
        }
        wm = self.shell("wm", "size", timeout=30).strip()
        density = self.shell("wm", "density", timeout=30).strip()
        self.info["wmSize"] = wm
        self.info["wmDensity"] = density
        package = self.shell("dumpsys", "package", PKG, timeout=60)
        match = re.search(r"versionName=([^\s]+)", package)
        if match:
            self.info["versionName"] = match.group(1)
        match = re.search(r"versionCode=(\d+)", package)
        if match:
            self.info["versionCode"] = match.group(1)
        self.info["deviceLine"] = next(
            (line for line in self.adb("devices", "-l", timeout=30).splitlines() if self.serial in line),
            "",
        )
        self.write_log("media-camera.txt", self.shell("dumpsys", "media.camera", timeout=90))
        self.write_log("package.txt", package)
        return self.info

    def write_log(self, name, content):
        path = os.path.join(self.logs, name)
        with open(path, "w", encoding="utf-8") as handle:
            handle.write(content or "")

    def verify_expected_version(self):
        if not self.expect_version:
            return
        actual = (self.info.get("versionName") or "").strip()
        if actual != self.expect_version:
            raise RuntimeError(
                f"版本不符：设备为 {actual or '?'}，要求 {self.expect_version}；"
                "请用 --apk 安装当前包，或修正 --expect-version"
            )

    # ---------- 安装与准备 ----------
    def ensure_online(self):
        """先确认设备在线；ADB 掉线不能被误报成“未安装”。"""
        state = (self.adb("get-state", timeout=20) or "").strip()
        if state != "device":
            raise RuntimeError(
                f"{self.serial} 设备不可用（adb get-state={state or '空'}）；"
                "请检查数据线、USB 调试授权和 adb devices"
            )

    def install_if_needed(self):
        self.ensure_online()
        pm_out = self.shell("pm", "path", PKG, timeout=30)
        if re.search(r"(?im)^(?:adb(?:\.exe)?:\s*)?(?:error:\s*)?(?:device .*not found|device offline|device unauthorized|no devices/emulators found|cannot connect to|more than one device/emulator)", pm_out):
            raise RuntimeError(f"{self.serial} ADB 连接失败：{pm_out.strip()}")
        installed = pm_out.strip().startswith("package:")
        if self.apk and not self.skip_install:
            if not os.path.isfile(self.apk):
                raise RuntimeError(f"APK 不存在：{self.apk}")
            out = self.adb("install", "-r", "-d", self.apk, timeout=300)
            if "Success" not in out:
                raise RuntimeError(f"安装失败：{out.strip()}")
            installed = True
        if not installed:
            raise RuntimeError(f"{self.serial} 未安装 {PKG}，请用 --apk 指定 APK")
        if self.fresh:
            out = self.shell("pm", "clear", PKG, timeout=60)
            if "Success" not in out:
                raise RuntimeError(f"pm clear 失败：{out.strip()}")
        # 清空崩溃缓冲区，保证最后统计的是本轮而非上一次录制留下的 FATAL
        self.shell("logcat", "-b", "crash", "-c", timeout=30)
        self.shell("logcat", "-c", timeout=30)
        # 先授权再进入，减少系统权限弹窗对 dump 的干扰；失败不阻塞（旧系统可能没有该权限）
        for permission in (
            "android.permission.CAMERA",
            "android.permission.RECORD_AUDIO",
            "android.permission.POST_NOTIFICATIONS",
        ):
            self.shell("pm", "grant", PKG, permission, timeout=30)
        # 首次合规/引导页可能停留超过 30 秒，准备阶段先保持亮屏；进 monitor 前再放开。
        # keep_awake(true) 会同时记录并临时改动画缩放，结束时统一恢复。
        self.keep_awake(True)
        self.wake()

    def back_to_main(self):
        for _ in range(5):
            act = self.activity()
            if "MainActivity" in act:
                return True
            if not act or act == "?":
                break
            ui_walk.back()
            time.sleep(1)
        self.open_app()
        time.sleep(2)
        return "MainActivity" in self.activity()

    def prepare_ui(self):
        """走完合规三页/引导页/权限弹窗，落到 MainActivity。"""
        self.keep_awake(True)
        self.wake()
        # 上一轮可能停在设置/黑屏页；先退回首页，再决定是否走首次引导
        for _ in range(4):
            act = self.activity()
            if "SettingsActivity" in act or "BlackScreenActivity" in act:
                ui_walk.back()
                time.sleep(1)
            else:
                break
        self.open_app()
        empty_streak = 0
        for index in range(40):
            if "MainActivity" in self.activity():
                return True
            nodes = self.dump(f"prep{index}")
            if not nodes:
                empty_streak += 1
                if empty_streak >= 3:
                    raise RuntimeError("连续 3 次 UI dump 为空；ADB 或前台 Activity 异常，停止盲重试")
                time.sleep(1.5)
                self.open_app()
                continue
            empty_streak = 0
            # 第三页是“勾选确认 + 开始使用”，先勾再点
            if self.tap_rid(nodes, "consent_checkbox"):
                time.sleep(0.8)
                nodes = self.dump(f"prep{index}-checked")
                if self.tap_rid(nodes, "consent_action"):
                    time.sleep(1.2)
                    continue
            for key in (
                "下一步", "跳过", "开始使用", "同意并继续", "我已知晓", "知道了",
                "仅在使用中允许", "使用应用时允许", "While using the app", "Allow", "允许",
            ):
                if self.tap_text(nodes, key, exact=True):
                    time.sleep(1.2)
                    break
            else:
                # 引导页偶尔需要滑动；滑不动就再等一帧，避免误判为完成
                width, height = ui_walk.screen_size()
                ui_walk.swipe(int(width * 0.86), int(height * 0.55), int(width * 0.12), int(height * 0.55), 220)
                time.sleep(1)
        return "MainActivity" in self.activity()

    # ---------- 值守 ----------
    def text_nodes(self, nodes):
        return [n["text"].strip() for n in nodes if n["text"].strip()]

    def find_text(self, nodes, text, exact=False):
        for node in nodes:
            value = (node["text"] or node["desc"]).strip()
            if (value == text) if exact else (text in value):
                return node
        return None

    def tap_text(self, nodes, text, exact=False):
        node = self.find_text(nodes, text, exact=exact)
        if node:
            ui_walk.tap(node)
            return node
        return None

    def tap_label(self, nodes, text, exact=True):
        """菜单项的文字节点本身常不可点，向上找包含它中心的最小可点父节点。"""
        node = self.find_text(nodes, text, exact=exact)
        if not node:
            return None
        if node["clickable"]:
            ui_walk.tap(node)
            return node
        candidates = [
            n for n in nodes
            if n["clickable"]
            and n["x1"] <= node["cx"] <= n["x2"]
            and n["y1"] <= node["cy"] <= n["y2"]
        ]
        target = min(candidates, key=lambda n: n["w"] * n["h"]) if candidates else node
        ui_walk.tap(target)
        return node

    def tap_rid(self, nodes, suffix):
        for node in nodes:
            if node["rid"].endswith(suffix):
                ui_walk.tap(node)
                return node
        return None

    def close_overlays(self):
        """首页的“更多功能”是同一 Activity 内的底部弹层，不是新 Activity；先按返回收起。"""
        for _ in range(3):
            nodes = self.dump("overlay-check")
            if self.find_text(nodes, "更多功能", exact=True) or self.find_text(nodes, "关闭", exact=True):
                ui_walk.back()
                time.sleep(1)
                continue
            break

    def guard_button(self, nodes=None):
        nodes = nodes if nodes is not None else self.dump("guard-button")
        return self.find_text(nodes, "停止值守", exact=True)

    def start_guard(self):
        self.keep_awake(True)
        self.wake()
        self.back_to_main()
        self.close_overlays()
        for _ in range(2):
            if "MainActivity" not in self.activity():
                self.open_app()
                time.sleep(2)
        nodes = self.dump("guard-main")
        # 上一轮可能停在“未连接电源”确认框；先点“先接电源”取消这次待确认的启动，
        # 回到首页后再重新点“开始值守”，避免把旧对话框当成首页。
        if self.find_text(nodes, "未连接电源", exact=True):
            self.tap_text(nodes, "先接电源", exact=True)
            time.sleep(1)
            nodes = self.dump("guard-main")
        if self.find_text(nodes, "停止值守", exact=True):
            return True
        if not self.tap_text(nodes, "开始值守", exact=True):
            self.notes.append("首页没有找到“开始值守”")
            return False
        time.sleep(2)
        for _ in range(10):
            nodes = self.dump("guard-dialog")
            # “仅充电时值守”默认开启但测试机没接电源时，App 会弹二次确认；
            # 不处理就会卡在这里，表现为“开始值守后未出现停止值守”。
            if self.find_text(nodes, "未连接电源", exact=True):
                if self.tap_text(nodes, "仍然开始", exact=True):
                    self.notes.append("电源提示：未连接电源，已选择“仍然开始”（App 限时 2 小时后自动停止）")
                    time.sleep(2)
                    continue
                if self.tap_text(nodes, "先接电源", exact=True):
                    self.notes.append("电源提示：未连接电源，已选择“先接电源”，请接电后重跑")
                    return False
            if self.tap_text(nodes, "暂不授权，直接开始", exact=True):
                time.sleep(2)
                continue
            if self.tap_text(nodes, "直接开始", exact=False):
                time.sleep(2)
                continue
            if self.find_text(nodes, "去授权", exact=True):
                # 不跳系统设置：返回对话框后继续点“暂不授权”
                ui_walk.back()
                time.sleep(2)
                continue
            for key in ("仅在使用中允许", "使用应用时允许", "While using the app", "Allow", "允许"):
                if self.tap_text(nodes, key, exact=True):
                    time.sleep(1.5)
                    break
            if self.find_text(nodes, "停止值守", exact=True):
                break
            time.sleep(2)
        nodes = self.dump("guard-running")
        running = bool(self.find_text(nodes, "停止值守", exact=True))
        self.shot("guard-running")
        if not running:
            self.notes.append("开始值守后未出现“停止值守”")
        return running

    def service_state(self):
        services = self.shell("dumpsys", "activity", "services", PKG, timeout=60)
        block = re.search(
            r"(?s)ServiceRecord\{[^\n]*com\.quietsentry\.app/\.CaptureService.*?(?=ServiceRecord\{|\Z)",
            services,
        )
        foreground = bool(block and re.search(r"isForeground=true", block.group(0)))
        return services, foreground

    def notification_present(self):
        # 不看正文；dumpsys notification 默认脱敏即可匹配 pkg=，避免把用户通知写进证据。
        dump = self.shell("dumpsys", "notification", timeout=90)
        active = re.split(r"Notification History:|Archive:", dump, maxsplit=1)[0]
        return bool(re.search(r"NotificationRecord\([^\n]*pkg=com\.quietsentry\.app", active))

    def sample(self, screen_off=False):
        now = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
        pid_text = self.shell("pidof", PKG, timeout=30)
        pid_match = re.search(r"(\d+)", pid_text)
        pid = int(pid_match.group(1)) if pid_match else 0
        services, foreground = self.service_state()
        notification = self.notification_present()
        battery = self.shell("dumpsys", "battery", timeout=60)
        level_match = re.search(r"level:\s*(\d+)", battery)
        temp_match = re.search(r"temperature:\s*(\d+)", battery)
        level = int(level_match.group(1)) if level_match else -1
        temp = (int(temp_match.group(1)) / 10.0) if temp_match else -1.0
        thermal = self.shell("dumpsys", "thermalservice", timeout=60)
        thermal_match = re.search(r"Thermal Status:\s*(\d+)", thermal)
        thermal_status = int(thermal_match.group(1)) if thermal_match else -1
        power = self.shell("dumpsys", "power", timeout=60)
        wake_match = re.search(r"mWakefulness=(\w+)", power)
        wakefulness = wake_match.group(1) if wake_match else "?"
        return {
            "time": now,
            "pid": pid,
            "serviceRunning": foreground,
            "notification": notification,
            "batteryPct": level,
            "batteryTempC": temp,
            "thermalStatus": thermal_status,
            "wakefulness": wakefulness,
        }

    def monitor(self, duration_min, screen_off):
        total_seconds = max(1.0, duration_min * 60.0)
        interval = 30.0 if total_seconds >= 120 else max(5.0, total_seconds / 4.0)
        count = max(3, int(round(total_seconds / interval)) + 1)
        if screen_off:
            self.keep_awake(False)
            self.sleep_screen()
        start = time.time()
        prev_pid = None
        pid_interruptions = 0
        service_off = 0
        notif_missing = 0
        asleep_samples = 0
        for index in range(count):
            sample = self.sample(screen_off=screen_off)
            # 只在上一点确实有进程时比较；100 -> 0 或 100 -> 200 都算一次中断，
            # 0 -> 100 是首次启动/人工重开，不是中断。
            if prev_pid is not None and prev_pid > 0 and sample["pid"] != prev_pid:
                pid_interruptions += 1
            prev_pid = sample["pid"]
            if not sample["serviceRunning"]:
                service_off += 1
            if sample["serviceRunning"] and not sample["notification"]:
                notif_missing += 1
            if sample["wakefulness"] == "Asleep":
                asleep_samples += 1
            self.samples.append(sample)
            remaining = total_seconds - (time.time() - start)
            if index < count - 1:
                if remaining <= 0:
                    break
                time.sleep(max(0.5, min(interval, remaining)))
        if screen_off:
            self.wake()
        return {
            "samples": len(self.samples),
            "elapsedSec": round(time.time() - start, 1),
            "pidInterruptions": pid_interruptions,
            "serviceOff": service_off,
            "notificationMissing": notif_missing,
            "asleepSamples": asleep_samples,
            "screenOffRequested": screen_off,
        }

    def status_summary(self):
        self.close_overlays()
        nodes = self.dump("status-after-wake")
        texts = self.text_nodes(nodes)
        line = next(
            (text for text in texts if re.search(r"(值守中|待机|已运行|记录\s*\d+\s*段)", text)),
            "",
        )
        running = bool(self.find_text(nodes, "停止值守", exact=True))
        record_match = re.search(r"(?:记录|已录)\s*(\d+)\s*段", line)
        return {
            "line": line,
            "guardRunning": running,
            "uiClipCount": int(record_match.group(1)) if record_match else None,
        }

    def stop_guard(self):
        self.keep_awake(True)
        self.wake()
        self.back_to_main()
        self.close_overlays()
        for _ in range(2):
            if "MainActivity" not in self.activity():
                self.open_app()
                time.sleep(2)
        nodes = self.dump("stop-main")
        if not self.tap_text(nodes, "停止值守", exact=True):
            return {"stopped": False, "reason": "没有找到停止按钮"}
        time.sleep(2)
        nodes = self.dump("stop-dialog")
        self.shot("stop-dialog")
        if self.tap_text(nodes, "停止", exact=True):
            pass
        elif self.tap_text(nodes, "确认停止", exact=True):
            pass
        else:
            self.notes.append("停止确认框没有找到“停止”")
        # FGS 通知和导出都是异步的，固定 8 秒容易偶发 PARTIAL；最多等 20 秒。
        deadline = time.time() + 20
        notification_gone = False
        while time.time() < deadline:
            notification_gone = not self.notification_present()
            if notification_gone:
                break
            time.sleep(2)
        time.sleep(2)
        nodes = self.dump("stop-after")
        self.shot("stop-after")
        texts = self.text_nodes(nodes)
        stopped = not self.find_text(nodes, "停止值守", exact=True)
        start_visible = bool(self.find_text(nodes, "开始值守", exact=True))
        saving = any(
            re.search(r"正在保存|保存中|已保存|保存到相册", text) and "保存失败" not in text
            for text in texts
        )
        # 保存可能异步；再等 10 秒补一张
        if saving:
            time.sleep(10)
            nodes = self.dump("stop-after-save")
            self.shot("stop-after-save")
            saving = any(
                re.search(r"正在保存|保存中|已保存|保存到相册", text) and "保存失败" not in text
                for text in self.text_nodes(nodes)
            )
        exported = self.exported_clip_count()
        before = self.exported_before
        # before=-1 表示基线读取失败；不能把当前总数当成新增，直接判不成立。
        delta = (exported - before) if (before is not None and before >= 0) else -1
        return {
            "stopped": stopped,
            "startVisible": start_visible,
            "savingVisible": saving,
            "notificationGone": notification_gone,
            "exportedClips": exported,
            "exportedBefore": before,
            "exportedDelta": delta,
        }

    def exported_clip_count(self):
        listing = self.shell("ls", "-1", "/sdcard/Movies/QuietSentry", timeout=30)
        files = [line.strip() for line in listing.splitlines() if line.strip().lower().endswith(".mp4")]
        content = self.shell(
            "content",
            "query",
            "--uri",
            "content://media/external/video/media",
            "--projection",
            "_display_name:relative_path",
            "--where",
            "relative_path LIKE 'Movies/QuietSentry%'",
            timeout=60,
        )
        self.write_log("media-store.txt", content)
        return max(len(files), content.count("QuietSentry"))

    # ---------- 设置页 / 诊断 ----------
    def node_by_rid(self, nodes, suffix):
        return next((node for node in nodes if node["rid"].endswith(suffix)), None)

    def scroll_to_rid(self, suffix, max_scrolls=8):
        width, height = ui_walk.screen_size()
        for index in range(max_scrolls + 1):
            nodes = self.dump(f"settings-scroll-{index}")
            node = self.node_by_rid(nodes, suffix)
            # 底部按钮常被导航条裁掉一截，要求完整落在安全区内再点
            if node and node["y1"] >= 130 and node["y2"] <= height - 100:
                return nodes, node
            ui_walk.swipe(width // 2, int(height * 0.78), width // 2, int(height * 0.38))
            time.sleep(0.8)
        return nodes, None

    def collect_diagnostics(self, enabled=True):
        if not enabled:
            self.diag = {"skipped": True}
            return self.diag
        self.keep_awake(True)
        try:
            return self._collect_diagnostics_body()
        finally:
            # 任何提前 return 或异常都必须恢复常亮/动画设置。
            self.keep_awake(False)

    def _collect_diagnostics_body(self):
        self.wake()
        self.back_to_main()
        self.close_overlays()
        for _ in range(2):
            if "MainActivity" not in self.activity():
                self.open_app()
                time.sleep(2)
        nodes = self.dump("diag-home")
        if not self.tap_text(nodes, "更多", exact=True):
            self.diag = {"error": "首页没有“更多”"}
            return self.diag
        time.sleep(1.5)
        nodes = self.dump("diag-menu")
        if not self.tap_label(nodes, "设置与诊断", exact=True):
            self.diag = {"error": "菜单没有“设置与诊断”"}
            self.back_to_main()
            return self.diag
        time.sleep(2.5)
        nodes = self.dump("diag-settings")
        lens = self.node_by_rid(nodes, "settings_lens_summary")
        diag_summary = self.node_by_rid(nodes, "settings_diag_summary")
        self.diag = {
            "activity": self.activity(),
            "lensSummary": (lens["text"].strip() if lens else ""),
            "diagSummaryBefore": (diag_summary["text"].strip() if diag_summary else ""),
        }
        self.shot("settings")
        nodes, run = self.scroll_to_rid("settings_diag_run")
        if not run:
            self.diag["error"] = "设置页未找到“一键诊断”，可能未滚到底"
            self.back_to_main()
            return self.diag
        self.wake()
        ui_walk.tap(run)
        time.sleep(10)
        self.wake()
        nodes = self.dump("diag-result")
        summary = self.node_by_rid(nodes, "settings_diag_summary")
        result_texts = [
            node["text"].strip()
            for node in nodes
            if node["rid"].endswith("settings_diag_result") or "通过" in node["text"] or "可优化" in node["text"] or "阻塞" in node["text"]
        ]
        self.diag["diagSummaryAfter"] = summary["text"].strip() if summary else ""
        self.diag["resultTexts"] = result_texts[:80]
        self.shot("settings-diagnostics")
        self.back_to_main()
        return self.diag

    # ---------- 日志与结论 ----------
    def collect_logs(self):
        crash = self.shell("logcat", "-b", "crash", "-d", timeout=90)
        crash_lines = crash.splitlines()
        relevant_crash = set()
        for index, line in enumerate(crash_lines):
            window = "\n".join(crash_lines[index:index + 10])
            if "FATAL EXCEPTION" in line and PKG in window:
                self.fatal_lines.append(line.strip())
                # 只保留当前 FATAL 块，遇到下一条 FATAL 立刻截断；
                # 否则 10 行窗口会把下一个应用的崩溃也带进证据文件。
                for offset in range(10):
                    current = index + offset
                    if current >= len(crash_lines):
                        break
                    if offset > 0 and "FATAL EXCEPTION" in crash_lines[current]:
                        break
                    relevant_crash.add(current)
            elif re.search(r"ANR in\s+" + re.escape(PKG), line):
                self.fatal_lines.append(line.strip())
                relevant_crash.add(index)
        # 崩溃缓冲区是全设备共享的，不能整段落盘：只保留 QuietSentry 的 FATAL/ANR 上下文。
        filtered_crash = "\n".join(crash_lines[i] for i in sorted(relevant_crash))
        self.write_log("logcat-crash.txt", filtered_crash)
        main = self.shell("logcat", "-d", "-t", "4000", timeout=120)
        # 不全量落盘：只保留 QuietSentry 相关行，避免把其他 App 的 token/URL 带进证据包。
        app_lines = [
            line for line in main.splitlines()
            if "com.quietsentry.app" in line or "QuietSentry" in line
        ]
        self.write_log("logcat.txt", "\n".join(app_lines))
        self.write_log("activity-services.txt", self.shell("dumpsys", "activity", "services", PKG, timeout=60))
        self.write_log("notification.txt", self.shell("dumpsys", "notification", timeout=90))

    def verdict(self, checks):
        if not checks.get("main"):
            return "FAIL"
        if not checks.get("guardStarted"):
            return "FAIL"
        if checks.get("screenOffRequested") and checks.get("asleepSamples", 0) == 0:
            return "PARTIAL"
        if checks.get("pidInterruptions", 0) > 0 or checks.get("serviceOff", 0) > 0:
            return "FAIL"
        if checks.get("notificationMissing", 0) > 0:
            return "FAIL"
        if checks.get("exportedDelta", 0) <= 0:
            return "FAIL"
        if not checks.get("fgsNotification"):
            return "PARTIAL"
        if not checks.get("stopped") or not checks.get("notificationGone"):
            return "PARTIAL"
        if self.fatal_lines:
            return "FAIL"
        return "PASS"

    def arm(self):
        """只准备并启动值守，不采样/不停止；给 D3 强杀注入做“重新武装”用。"""
        try:
            self.install_if_needed()
            self.collect_info()
            self.verify_expected_version()
            main_ok = self.prepare_ui()
            guard_ok = self.start_guard() if main_ok else False
            services, foreground = self.service_state()
            notification = self.notification_present()
            self.write_log("service-arm.txt", services)
            self.collect_logs()
            data = {
                "serial": self.serial,
                "label": self.label,
                "at": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
                "device": self.info,
                "checks": {
                    "main": main_ok,
                    "guardStarted": guard_ok,
                    "foregroundService": foreground,
                    "fgsNotification": notification,
                },
                "notes": self.notes,
                "artifacts": {"root": self.out, "screenshots": self.shots, "logs": self.logs},
            }
            with open(os.path.join(self.out, "arm.json"), "w", encoding="utf-8") as handle:
                json.dump(data, handle, ensure_ascii=False, indent=2)
            return data
        except Exception as exc:
            self.notes.append(f"arm 失败：{exc}")
            data = {
                "serial": self.serial,
                "label": self.label,
                "at": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
                "device": self.info,
                "checks": {"main": False, "guardStarted": False, "foregroundService": False, "fgsNotification": False},
                "notes": self.notes,
                "error": f"{exc}\n{traceback.format_exc()}",
                "artifacts": {"root": self.out, "screenshots": self.shots, "logs": self.logs},
            }
            try:
                with open(os.path.join(self.out, "arm.json"), "w", encoding="utf-8") as handle:
                    json.dump(data, handle, ensure_ascii=False, indent=2)
            except Exception:
                pass
            return data
        finally:
            self.keep_awake(False)

    def run(self, duration_min=5, screen_off=True, diagnostics=True):
        try:
            self.collect_info()
            self.install_if_needed()
            main_ok = self.prepare_ui()
            self.collect_info()
            self.verify_expected_version()
            try:
                self.exported_before = self.exported_clip_count()
            except Exception as exc:
                self.notes.append(f"读取相册基线失败：{exc}")
                # 不能把失败当成 0：0 会让旧片段全部算成本次新增，直接造成假 PASS。
                self.exported_before = -1
            guard_ok = self.start_guard()
            services, foreground = self.service_state()
            fgs_notification = self.notification_present()
            self.write_log("service-guard.txt", services)
            screen = self.monitor(duration_min, screen_off) if guard_ok else {
                "samples": 0, "elapsedSec": 0, "pidInterruptions": 0, "serviceOff": 0,
                "notificationMissing": 0, "asleepSamples": 0, "screenOffRequested": screen_off
            }
            status = self.status_summary() if guard_ok else {"line": "", "guardRunning": False, "uiClipCount": None}
            stop = self.stop_guard() if guard_ok else {
                "stopped": False, "startVisible": False, "savingVisible": False,
                "notificationGone": False, "exportedClips": 0,
                "exportedBefore": self.exported_before, "exportedDelta": 0
            }
            self.collect_diagnostics(diagnostics)
            self.collect_logs()
            checks = {
                "main": main_ok,
                "guardStarted": guard_ok,
                "foregroundService": foreground,
                "fgsNotification": fgs_notification,
                **screen,
                **stop,
                "uiStatusLine": status.get("line", ""),
                "uiClipCount": status.get("uiClipCount"),
                "fatalAnrLines": len(self.fatal_lines),
            }
            verdict = self.verdict(checks)
            summary = {
                "serial": self.serial,
                "label": self.label,
                "startedAt": STAMP,
                "finishedAt": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
                "verdict": verdict,
                "device": self.info,
                "checks": checks,
                "diagnostics": self.diag,
                "notes": self.notes,
                "samples": self.samples,
                "fatalLines": self.fatal_lines[:20],
                "artifacts": {
                    "root": self.out,
                    "screenshots": self.shots,
                    "ui": self.ui,
                    "logs": self.logs,
                },
            }
            self.write_summary(summary)
            return summary
        except Exception as exc:
            self.error = f"{exc}\n{traceback.format_exc()}"
            self.notes.append(f"运行失败：{exc}")
            summary = {
                "serial": self.serial,
                "label": self.label,
                "startedAt": STAMP,
                "finishedAt": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
                "verdict": "FAIL",
                "device": self.info,
                "checks": {},
                "diagnostics": self.diag,
                "notes": self.notes,
                "samples": self.samples,
                "fatalLines": self.fatal_lines,
                "error": self.error,
                "artifacts": {"root": self.out, "screenshots": self.shots, "ui": self.ui, "logs": self.logs},
            }
            try:
                self.write_summary(summary)
            except Exception:
                pass
            return summary
        finally:
            self.keep_awake(False)

    def write_summary(self, summary):
        json_path = os.path.join(self.out, "summary.json")
        with open(json_path, "w", encoding="utf-8") as handle:
            json.dump(summary, handle, ensure_ascii=False, indent=2)
        csv_path = os.path.join(self.out, "samples.csv")
        with open(csv_path, "w", encoding="utf-8", newline="") as handle:
            writer = csv.DictWriter(
                handle,
                fieldnames=["time", "pid", "serviceRunning", "notification", "batteryPct", "batteryTempC", "thermalStatus", "wakefulness"],
            )
            writer.writeheader()
            writer.writerows(self.samples)
        device = summary.get("device", {})
        checks = summary.get("checks", {})
        diag = summary.get("diagnostics", {})
        lines = [
            f"# QuietSentry 真机基线 — {summary.get('label', self.serial)}",
            "",
            f"- 设备：{device.get('manufacturer', '?')} {device.get('model', '?')} / Android {device.get('android', '?')} (API {device.get('sdk', '?')})",
            f"- 序列号：`{self.serial}`",
            f"- 版本：`{device.get('versionName', '?')}` / versionCode `{device.get('versionCode', '?')}`",
            f"- 结论：**{summary.get('verdict', '?')}**",
            f"- 开始 / 结束：{summary.get('startedAt', '?')} / {summary.get('finishedAt', '?')}",
            "",
            "## 检查项",
            "",
            "| 检查 | 结果 |",
            "| --- | --- |",
            f"| 启动进入首页 | {'PASS' if checks.get('main') else 'FAIL'} |",
            f"| 开始值守 | {'PASS' if checks.get('guardStarted') else 'FAIL'} |",
            f"| 前台服务 / camera 类型 | {'PASS' if checks.get('foregroundService') else 'FAIL'} |",
            f"| 值守通知 | {'PASS' if checks.get('fgsNotification') else 'FAIL'} |",
            f"| 锁屏采样 | {checks.get('samples', 0)} 点；Asleep {checks.get('asleepSamples', 0)} 点；进程中断 {checks.get('pidInterruptions', 0)} 次；服务非运行 {checks.get('serviceOff', 0)} 次 |",
            f"| 停止并保存 | {'PASS' if checks.get('stopped') else 'FAIL'}；保存提示：{'有' if checks.get('savingVisible') else '未见'}；本次新增 {checks.get('exportedDelta', 0)} 个 mp4（运行前 {checks.get('exportedBefore', '?')}，停止后 {checks.get('exportedClips', 0)}） |",
            f"| 停止后通知清理 | {'PASS' if checks.get('notificationGone') else 'FAIL'} |",
            f"| 崩溃 / ANR | {checks.get('fatalAnrLines', 0)} 行 |",
            "",
            "## 设备与镜头",
            "",
            f"- 厂商 / 型号：{device.get('manufacturer', '?')} / {device.get('model', '?')}",
            f"- 屏幕：{device.get('wmSize', '?')}；{device.get('wmDensity', '?')}",
            f"- 指纹：`{device.get('fingerprint', '?')}`",
            f"- 设置页镜头摘要：{diag.get('lensSummary', '（未采集）')}",
            f"- 一键诊断摘要：{diag.get('diagSummaryAfter') or diag.get('diagSummaryBefore') or '（未采集）'}",
            "",
        ]
        if diag.get("resultTexts"):
            lines += ["诊断明细：", ""]
            lines += [f"- {text}" for text in diag["resultTexts"][:12]]
            lines += [""]
        if self.notes:
            lines += ["## 备注", ""] + [f"- {note}" for note in self.notes] + [""]
        if self.fatal_lines:
            lines += ["## FATAL / ANR 片段", "", "```text"] + self.fatal_lines[:10] + ["```", ""]
        lines += [
            "## 证据",
            "",
            f"- 截图：`{self.shots}`",
            f"- UI dump：`{self.ui}`",
            f"- 日志：`{self.logs}`",
            f"- 采样 CSV：`{csv_path}`",
            "",
            "> 本文件只覆盖基线链路；相机占用重绑、无后置镜头、录制档位回退、白名单引导等分支需按 `docs/05-兼容性与稳定性验证矩阵.md` 手工补测，不能据此单独判整机 PASS。",
        ]
        with open(os.path.join(self.out, "SUMMARY.md"), "w", encoding="utf-8") as handle:
            handle.write("\n".join(lines) + "\n")
        return json_path


def matrix_row(summary):
    device = summary.get("device", {})
    checks = summary.get("checks", {})
    return {
        "serial": summary.get("serial", ""),
        "label": summary.get("label", ""),
        "manufacturer": device.get("manufacturer", ""),
        "model": device.get("model", ""),
        "android": device.get("android", ""),
        "sdk": device.get("sdk", ""),
        "verdict": summary.get("verdict", ""),
        "main": checks.get("main", ""),
        "guardStarted": checks.get("guardStarted", ""),
        "fgsNotification": checks.get("fgsNotification", ""),
        "screenOffSamples": checks.get("samples", ""),
        "elapsedSec": checks.get("elapsedSec", ""),
        "pidInterruptions": checks.get("pidInterruptions", ""),
        "serviceOff": checks.get("serviceOff", ""),
        "exportedClips": checks.get("exportedClips", ""),
        "exportedDelta": checks.get("exportedDelta", ""),
        "fatalAnrLines": checks.get("fatalAnrLines", ""),
        "evidence": summary.get("artifacts", {}).get("root", ""),
        "notes": " | ".join(summary.get("notes", [])[:3]),
    }


def write_matrix(out_root, summaries):
    rows = [matrix_row(summary) for summary in summaries]
    if not rows:
        return "", ""
    csv_path = os.path.join(out_root, "matrix.csv")
    with open(csv_path, "w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(rows[0].keys()))
        writer.writeheader()
        writer.writerows(rows)
    md_path = os.path.join(out_root, "matrix.md")
    lines = [
        "# QuietSentry 真机基线矩阵（自动采集部分）",
        "",
        f"生成时间：{datetime.now().strftime('%Y-%m-%d %H:%M:%S')}",
        "",
        "| # | 设备 | Android | 结论 | 值守 | 通知 | 进程中断 | 服务非运行 | 崩溃/ANR | 证据 |",
        "| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |",
    ]
    for index, row in enumerate(rows, 1):
        lines.append(
            f"| {index} | {row['manufacturer']} {row['model']} | {row['android']} | **{row['verdict']}** | "
            f"{'PASS' if row['guardStarted'] else 'FAIL'} | {'PASS' if row['fgsNotification'] else 'FAIL'} | "
            f"{row['pidInterruptions']} | {row['serviceOff']} | {row['fatalAnrLines']} | `{row['evidence']}` |"
        )
    lines += [
        "",
        "> 自动采集只覆盖基线链路。八品牌六个必测项中的特殊分支（无后置镜头、录制档位回退、相机被占用重绑、强杀恢复提示、白名单引导）仍需按 `docs/05-兼容性与稳定性验证矩阵.md` 逐项补测并填 PASS/FAIL/N/A。",
    ]
    with open(md_path, "w", encoding="utf-8") as handle:
        handle.write("\n".join(lines) + "\n")
    return csv_path, md_path


def main():
    parser = argparse.ArgumentParser(description="QuietSentry 真机基线冒烟与证据采集")
    parser.add_argument("--serial", default="", help="单台设备序列号；不填则配合 --all 或要求唯一在线设备")
    parser.add_argument("--all", action="store_true", help="遍历所有在线设备")
    parser.add_argument("--include-emulators", action="store_true", help="--all 时也包含 emulator-*（仅用于工具自测）")
    parser.add_argument("--arm", action="store_true", help="只启动值守并留后台运行，不采样/不停止（D3 重新武装）")
    parser.add_argument("--apk", default=os.environ.get("QS_APK", ""), help="要安装的 release APK；不填则要求已安装")
    parser.add_argument("--label", default="", help="品牌/机型标签，例如 小米/Redmi Note")
    parser.add_argument("--duration-min", type=float, default=5.0, help="锁屏连续采集时长，矩阵要求 5 分钟")
    parser.add_argument("--no-screen-off", action="store_true", help="不锁屏（仅工具自测；矩阵必须锁屏）")
    parser.add_argument("--no-diagnostics", action="store_true", help="跳过设置页一键诊断")
    parser.add_argument("--fresh", action="store_true", help="安装后 pm clear，走首次启动合规流程")
    parser.add_argument("--skip-install", action="store_true", help="不重新安装，只跑已安装版本")
    parser.add_argument("--expect-version", default=os.environ.get("QS_EXPECT_VERSION", ""),
                        help="要求设备上的 versionName 与此值一致；用于 --skip-install 的证据校验")
    parser.add_argument("--out", default=DEFAULT_OUT, help="证据根目录")
    args = parser.parse_args()

    if args.skip_install and not args.expect_version:
        print("--skip-install 必须同时指定 --expect-version，否则无法证明测的是当前构建包", file=sys.stderr)
        return 2
    if not os.path.isfile(ADB) and shutil.which(ADB) is None:
        print(f"未找到 adb：{ADB}；可用 QS_ADB 指定路径", file=sys.stderr)
        return 2

    out_root = os.path.abspath(args.out)
    os.makedirs(out_root, exist_ok=True)

    serials = [args.serial] if args.serial else []
    explicit_serial = bool(args.serial)
    if args.all or not serials:
        serials = parse_devices()
    if not serials:
        print("未发现在线设备。真机请打开 USB 调试并授权；模拟器自测可加 --include-emulators --all。", file=sys.stderr)
        return 2
    if not args.include_emulators and not explicit_serial:
        serials = [serial for serial in serials if not serial.startswith("emulator-")]
        if not serials:
            print("只发现模拟器；真机矩阵请连接真机。若只是自测工具，加 --include-emulators。", file=sys.stderr)
            return 2
    if len(serials) > 1 and args.label:
        print("--label 只适用于单台设备；多台请省略，脚本会用序列号作标签。", file=sys.stderr)
        return 2
    if args.arm and len(serials) > 1:
        print("--arm 只适用于单台设备；多台请用 --serial 指定。", file=sys.stderr)
        return 2

    summaries = []
    for serial in serials:
        label = args.label or serial
        print(f"\n=== {serial} ({label}) ===")
        runner = DeviceRunner(
            serial=serial,
            out_root=out_root,
            label=label,
            apk=args.apk,
            fresh=args.fresh,
            skip_install=args.skip_install,
            expect_version=args.expect_version,
        )
        if args.arm:
            data = runner.arm()
            checks = data.get("checks", {})
            ok = all(checks.get(key) for key in ("guardStarted", "foregroundService", "fgsNotification"))
            print(
                f"{serial}: ARM {'PASS' if ok else 'FAIL'} "
                f"(guardStarted={checks.get('guardStarted')}, "
                f"foregroundService={checks.get('foregroundService')}, "
                f"fgsNotification={checks.get('fgsNotification')}) -> "
                f"{data.get('artifacts', {}).get('root')}"
            )
            return 0 if ok else 1
        summary = runner.run(
            duration_min=args.duration_min,
            screen_off=not args.no_screen_off,
            diagnostics=not args.no_diagnostics,
        )
        summaries.append(summary)
        print(f"{serial}: {summary.get('verdict')} -> {summary.get('artifacts', {}).get('root')}")

    if args.all and summaries:
        csv_path, md_path = write_matrix(out_root, summaries)
        print(f"\n矩阵 CSV: {csv_path}")
        print(f"矩阵 Markdown: {md_path}")
    print("\n完成。逐台 SUMMARY.md 在各自证据目录下。")
    return 0 if all(summary.get("verdict") == "PASS" for summary in summaries) else 1


if __name__ == "__main__":
    sys.exit(main())
