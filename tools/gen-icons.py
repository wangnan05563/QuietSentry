#!/usr/bin/env python3
"""生成静哨的矢量风格品牌图标、Android 各密度启动图标与 GitHub 社交预览图。

为什么用脚本而不是设计稿导出：图标要同时覆盖 5 个密度、圆形图标、自适应图标前景
以及 1280x640 社交预览，手工导出容易漏尺寸；品牌色调整后也需要一键重生成。
"""

import os

from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(ROOT, "assets", "brand")
RES = os.path.join(ROOT, "app", "src", "main", "res")

INK = (5, 16, 14, 255)
TEAL = (0, 191, 166, 255)
WHITE = (255, 255, 255, 255)

SS = 4  # 超采样倍数：Pillow 没有抗锯齿多边形，先画大再缩


def qbez(p0, p1, p2, steps=36):
    """二次贝塞尔采样。盾牌肩部的收腰曲线用它描点，避免手写一堆三角函数。"""
    points = []
    for i in range(steps + 1):
        t = i / steps
        x = (1 - t) ** 2 * p0[0] + 2 * (1 - t) * t * p1[0] + t * t * p2[0]
        y = (1 - t) ** 2 * p0[1] + 2 * (1 - t) * t * p1[1] + t * t * p2[1]
        points.append((x, y))
    return points


def shield_polygon(x0, y0, x1, y1):
    """盾牌轮廓：上方圆角矩形肩线，下方收成尖角，是“值守/防护”的表意主体。"""
    w = x1 - x0
    h = y1 - y0
    r = w * 0.20
    cx = (x0 + x1) / 2
    pts = [(x0 + r, y0), (x1 - r, y0)]
    pts += qbez((x1 - r, y0), (x1, y0), (x1, y0 + r))
    pts.append((x1, y0 + h * 0.56))
    pts += qbez((x1, y0 + h * 0.56), (x1, y0 + h * 0.84), (cx, y1))
    pts += qbez((cx, y1), (x0, y0 + h * 0.84), (x0, y0 + h * 0.56))
    pts.append((x0, y0 + r))
    pts += qbez((x0, y0 + r), (x0, y0), (x0 + r, y0))
    return pts


def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(len(a)))


def draw_mark(img, center_x, center_y, mark_width):
    """在已有画布上画“盾 + 镜头”标记；自适应前景与社交图都复用同一套几何。"""
    d = ImageDraw.Draw(img)
    mark_height = mark_width * 1.16
    x0 = center_x - mark_width / 2
    x1 = center_x + mark_width / 2
    y0 = center_y - mark_height / 2
    y1 = center_y + mark_height / 2

    d.polygon(shield_polygon(x0, y0, x1, y1), fill=TEAL)

    # 镜头环：盾内的“观看”意象。环宽按盾宽比例给，缩小到 48px 仍能分辨
    lens_center = (center_x, y0 + mark_height * 0.46)
    ring_r = mark_width * 0.30
    ring_w = max(1, int(mark_width * 0.085))
    d.ellipse(
        (lens_center[0] - ring_r, lens_center[1] - ring_r,
         lens_center[0] + ring_r, lens_center[1] + ring_r),
        outline=WHITE,
        width=ring_w,
    )
    dot_r = mark_width * 0.105
    d.ellipse(
        (lens_center[0] - dot_r, lens_center[1] - dot_r,
         lens_center[0] + dot_r, lens_center[1] + dot_r),
        fill=WHITE,
    )


def render_icon(size, background=True, rounded=True, circle=False, mark_scale=0.58, ss=SS):
    w = size * ss
    canvas = Image.new("RGBA", (w, w), (0, 0, 0, 0))

    if background:
        d = ImageDraw.Draw(canvas)
        radius = int(w * 0.22)
        if circle:
            d.ellipse((0, 0, w - 1, w - 1), fill=INK)
        elif rounded:
            d.rounded_rectangle((0, 0, w - 1, w - 1), radius=radius, fill=INK)
        else:
            d.rectangle((0, 0, w - 1, w - 1), fill=INK)

        # 中心偏下的青色光晕：纯平底色在小尺寸上显得死板，一点内发光能把盾牌托起来
        glow = Image.new("RGBA", (w, w), (0, 0, 0, 0))
        gd = ImageDraw.Draw(glow)
        for i in range(16, 0, -1):
            alpha = int(1.6 * i * i / 16)
            r = w * 0.44 * (i / 16)
            gd.ellipse(
                (w / 2 - r, w / 2 - r + w * 0.06, w / 2 + r, w / 2 + r + w * 0.06),
                fill=(0, 191, 166, alpha),
            )
        canvas = Image.alpha_composite(canvas, glow)

    draw_mark(canvas, w / 2, w / 2, w * mark_scale)
    return canvas.resize((size, size), Image.LANCZOS)


def save(image, path):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    image.save(path, optimize=True)
    print(f"  {os.path.relpath(path, ROOT)}  {image.size[0]}x{image.size[1]}")


def font(path, size):
    try:
        return ImageFont.truetype(path, size)
    except Exception:
        return ImageFont.load_default()


def text(draw, xy, content, font_obj, fill, anchor="la"):
    draw.text(xy, content, font=font_obj, fill=fill, anchor=anchor)


def render_social_preview():
    """GitHub 社交预览：1280x640，左侧品牌与卖点，右侧一台正在值守的手机示意。"""
    w, h = 1280, 640
    canvas = Image.new("RGBA", (w, h), INK)
    d = ImageDraw.Draw(canvas)
    for y in range(h):
        d.line((0, y, w, y), fill=lerp(INK, (8, 30, 26, 255), y / h))

    glow = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    gd = ImageDraw.Draw(glow)
    for i in range(20, 0, -1):
        alpha = int(1.4 * i)
        r = w * 0.30 * (i / 20)
        gd.ellipse((w * 0.86 - r, h * 0.5 - r, w * 0.86 + r, h * 0.5 + r),
                   fill=(0, 191, 166, alpha))
    canvas = Image.alpha_composite(canvas, glow)

    draw_mark(canvas, 128, 176, 132)

    d = ImageDraw.Draw(canvas)
    bold = font(os.path.join("C:\\Windows\\Fonts", "msyhbd.ttc"), 64)
    regular = font(os.path.join("C:\\Windows\\Fonts", "msyh.ttc"), 30)
    small = font(os.path.join("C:\\Windows\\Fonts", "msyh.ttc"), 25)
    label = font(os.path.join("C:\\Windows\\Fonts", "segoeb.ttf"), 26)

    text(d, (232, 132), "静哨  QuietSentry", bold, WHITE)
    text(d, (234, 216), "把闲置 Android 设备变成一台私有摄像头", regular, (190, 210, 205, 255))
    text(d, (234, 282), "本地存储 · 局域网直连 · 无账号 · 不经过云端", small, TEAL)
    text(d, (234, 326), "lock the screen, keep watching.", label, (120, 160, 152, 255))

    # 手机示意：外框 + 屏幕内画面 + 底部状态，让预览图表达“它能做什么”而不只是品牌字
    px, py, pw, ph = 968, 128, 226, 412
    d.rounded_rectangle((px, py, px + pw, py + ph), radius=34, outline=(58, 82, 76, 255), width=3)
    d.rounded_rectangle((px + 12, py + 14, px + pw - 12, py + ph - 14), radius=24, fill=(2, 10, 9, 255))

    inner = Image.new("RGBA", (pw - 24, ph - 28), (0, 0, 0, 0))
    idraw = ImageDraw.Draw(inner)
    iw, ih = inner.size
    for y in range(ih):
        idraw.line((0, y, iw, y), fill=lerp((6, 26, 23, 255), (2, 12, 11, 255), y / ih))

    # 屏幕里只保留“值守中的镜头”这一件事：同心圆环在小画布上比假装实景更容易读懂
    cx2, cy2 = iw * 0.5, ih * 0.46
    idraw.ellipse((cx2 - 52, cy2 - 52, cx2 + 52, cy2 + 52), outline=(255, 255, 255, 190), width=3)
    idraw.ellipse((cx2 - 20, cy2 - 20, cx2 + 20, cy2 + 20), fill=(0, 191, 166, 170))
    idraw.ellipse((iw * 0.08, ih * 0.08, iw * 0.14, ih * 0.14), fill=(76, 217, 100, 255))
    idraw.text((iw * 0.18, ih * 0.055), "值守中",
               font=font(os.path.join("C:\\Windows\\Fonts", "msyh.ttc"), 18),
               fill=WHITE)
    canvas.paste(inner, (px + 12, py + 14), inner)

    # 底部状态条用深色半透明：青色实底会把“事件提醒”误读成可点击按钮
    d.rounded_rectangle((px, py + ph - 82, px + pw, py + ph), radius=20, fill=(3, 20, 17, 235))
    d.rectangle((px + 1, py + ph - 82, px + 4, py + ph), fill=TEAL)
    small_label = font(os.path.join("C:\\Windows\\Fonts", "msyh.ttc"), 19)
    text(d, (px + 24, py + ph - 66), "侦测到移动", small_label, (225, 245, 240, 255))
    text(d, (px + 24, py + ph - 38), "已保存到本机", font(os.path.join("C:\\Windows\\Fonts", "msyh.ttc"), 17),
         (120, 170, 160, 255))

    # 底部细线让 1280x640 的裁切边界更明确
    d.line((80, h - 56, w - 80, h - 56), fill=(0, 191, 166, 70), width=2)
    text(d, (80, h - 40), "QuietSentry · Android 9+ · 无需账户 · 数据留在设备", small, (110, 145, 138, 255))

    save(canvas.convert("RGB"), os.path.join(ASSETS, "social-preview-1280x640.png"))


def main():
    os.makedirs(ASSETS, exist_ok=True)
    print("生成品牌资源")

    master = render_icon(1024, background=True, mark_scale=0.58)
    save(master, os.path.join(ASSETS, "logo-1024.png"))
    save(render_icon(1024, background=False, mark_scale=0.92),
         os.path.join(ASSETS, "logo-foreground-1024.png"))
    save(render_icon(512, background=True, mark_scale=0.58),
         os.path.join(ASSETS, "play-store-icon-512.png"))

    # 传统启动图标：Android 8 以下直接使用整张图，8+ 由自适应 XML 组装背景与前景
    densities = {
        "mdpi": 48,
        "hdpi": 72,
        "xhdpi": 96,
        "xxhdpi": 144,
        "xxxhdpi": 192,
    }
    for name, size in densities.items():
        save(render_icon(size, background=True), os.path.join(RES, f"mipmap-{name}", "ic_launcher.png"))
        save(render_icon(size, background=True, circle=True),
             os.path.join(RES, f"mipmap-{name}", "ic_launcher_round.png"))
        # 自适应前景是 108dp 画布，图形必须收在中心 66dp 安全区内，因此缩放比传统图标小
        foreground = render_icon(int(size * 108 / 48), background=False, mark_scale=0.52)
        save(foreground, os.path.join(RES, f"mipmap-{name}", "ic_launcher_foreground.png"))

    render_social_preview()


if __name__ == "__main__":
    main()
