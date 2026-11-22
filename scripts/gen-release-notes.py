#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成 QuietSentry 的 GitHub Release 发布记录（UTF-8 落盘）。

为什么用 python 而不是在 PowerShell 里拼字符串：PowerShell 5.1 捕获原生命令输出时按
控制台代码页解码（中文 Windows 是 GBK），git log 里的中文提交说明会先变成乱码，
再被原样发到 GitHub，GitHub 上就是一大片问号/方块。python 直接用 UTF-8 读 git 输出、
以 UTF-8 写文件，整条链路只剩一种编码。

用法：python gen-release-notes.py <tag> <output_md> [apk_name] [aab_name]
"""

import os
import subprocess
import sys


def find_repo_root():
    """从脚本位置向上找最近的 .git，避免把盘符写死。"""
    d = os.path.dirname(os.path.abspath(__file__))
    for _ in range(10):
        if os.path.isdir(os.path.join(d, ".git")):
            return d
        parent = os.path.dirname(d)
        if parent == d:
            break
        d = parent
    return os.getcwd()


ROOT = find_repo_root()


def sh(cmd):
    """跑一条命令并按 UTF-8 解码输出；失败返回空串而不是抛出。"""
    try:
        out = subprocess.run(
            cmd,
            shell=True,
            capture_output=True,
            text=True,
            timeout=60,
            cwd=ROOT,
            encoding="utf-8",
            errors="replace",
        )
        return (out.stdout or "").strip()
    except Exception:
        return ""


def main():
    if len(sys.argv) < 3:
        sys.exit("usage: gen-release-notes.py <tag> <output_md> [apk_name] [aab_name]")

    tag = sys.argv[1]
    out_path = sys.argv[2]
    apk_name = sys.argv[3] if len(sys.argv) > 3 else ""
    aab_name = sys.argv[4] if len(sys.argv) > 4 else ""

    repo = sh("git remote get-url origin") or "(unknown)"
    branch = sh("git rev-parse --abbrev-ref HEAD") or "master"

    # 有上一个 tag 就只看它之后的提交，否则退回最近 20 条：发布记录只讲本次变化，
    # 不搬全量历史。
    since = sh("git describe --tags --abbrev=0 2>nul")
    log = sh("git log " + since + "..HEAD --pretty=format:\"- %s\"") if since else ""
    if not log:
        log = sh("git log -20 --pretty=format:\"- %s\"")
    if not log:
        log = "- （没有可展示的提交记录）"

    assets = []
    if apk_name:
        # 名称里带 -debug 就是联调包；一键发布只会传 release 包，这里按名称兜底，避免记录写错
        if "-debug" in apk_name:
            assets.append(
                "- `" + apk_name + "` — Android 安装包（debug 变体，调试密钥签名，仅联调用）\n"
            )
        else:
            assets.append(
                "- `" + apk_name + "` — Android 安装包（release 变体，release.jks 签名）\n"
            )
    if aab_name:
        assets.append("- `" + aab_name + "` — Google Play 上架包（AAB）\n")
    if not assets:
        assets.append("- （本次未记录附件名）\n")

    notes = (
        "# QuietSentry " + tag + "\n\n"
        "由「一键发布」自动生成；提交说明原样取自 git log，UTF-8 写入后直接发布。\n\n"
        "- 分支：" + branch + "\n"
        "- 仓库：" + repo + "\n\n"
        "## 发布产物\n\n"
        + "".join(assets) +
        "\n"
        "## 变更\n\n"
        + log + "\n"
    )

    # newline="\n"：固定 LF，避免 CRLF 与平台相关的差异混进 Markdown
    with open(out_path, "w", encoding="utf-8", newline="\n") as f:
        f.write(notes)
    print("release notes -> " + out_path)
    return 0


if __name__ == "__main__":
    sys.exit(main())
