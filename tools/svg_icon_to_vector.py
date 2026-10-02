#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把设计稿 SVG 转成 Android 自适应图标的 VectorDrawable（前景层 + 单色层）。

为什么需要脚本：自适应图标的前景要落进 108×108 viewport 的**安全区**
（圆心 54,54 半径约 33dp），必须对原图做平移+缩放；贝塞尔控制点、圆弧半径、
描边宽度全都要一起缩放 —— 手算容易错，这里按仿射变换精确算。

只支持设计稿里实际用到的子集：`rect`（忽略，改由背景层承担）、`path`（M/L/C/A/Z）、
`circle`、`stroke-*` / `fill`。不是通用 SVG 转换器。

用法：
    python tools/svg_icon_to_vector.py 设计稿.svg \
        --foreground app/src/main/res/drawable/ic_launcher_foreground.xml \
        --monochrome app/src/main/res/drawable/ic_launcher_monochrome.xml \
        --safe 62
"""

import argparse
import math
import os
import re
import sys

TOKEN = re.compile(r"([MmLlHhVvCcSsQqTtAaZz])|(-?\d*\.?\d+(?:e[-+]?\d+)?)")


def parse_path(d):
    """返回 [(cmd, [nums...]), ...]；只处理绝对坐标（设计稿都是绝对的）"""
    out = []
    i = 0
    while i < len(d):
        ch = d[i]
        if ch in "MmLlHhVvCcSsQqTtAaZz":
            cmd = ch
            i += 1
            nums = []
            while i < len(d):
                if d[i] in "MmLlHhVvCcSsQqTtAaZz":
                    break
                m = re.match(r"[-+]?\d*\.?\d+(?:[eE][-+]?\d+)?", d[i:])
                if not m:
                    i += 1
                    continue
                nums.append(float(m.group(0)))
                i += m.end()
            out.append((cmd, nums))
        else:
            i += 1
    return out


class Xf:
    """仿射：先平移中心到原点，再缩放，再平移到 54,54"""

    def __init__(self, cx, cy, s):
        self.cx, self.cy, self.s = cx, cy, s

    def p(self, x, y):
        return ((x - self.cx) * self.s + 54.0, (y - self.cy) * self.s + 54.0)

    def l(self, v):
        return v * self.s


def fmt(v):
    t = ("%.2f" % v).rstrip("0").rstrip(".")
    return "0" if t in ("", "-0") else t


def conv_path(d, xf):
    parts = []
    for cmd, nums in parse_path(d):
        c = cmd.upper()
        if c == "M":
            pts = [xf.p(nums[i], nums[i + 1]) for i in range(0, len(nums) - 1, 2)]
            parts.append("M" + " ".join("%s,%s" % (fmt(a), fmt(b)) for a, b in pts))
        elif c == "L":
            pts = [xf.p(nums[i], nums[i + 1]) for i in range(0, len(nums) - 1, 2)]
            parts.append("L" + " ".join("%s,%s" % (fmt(a), fmt(b)) for a, b in pts))
        elif c == "C":
            pts = [xf.p(nums[i], nums[i + 1]) for i in range(0, len(nums) - 1, 2)]
            parts.append("C" + " ".join("%s,%s" % (fmt(a), fmt(b)) for a, b in pts))
        elif c == "A":
            # A rx ry rot large sweep x y
            segs = []
            for i in range(0, len(nums) - 6, 7):
                rx, ry, rot, large, sweep, x, y = nums[i:i + 7]
                px, py = xf.p(x, y)
                segs.append("A%s,%s %s %d %d %s,%s" % (
                    fmt(xf.l(rx)), fmt(xf.l(ry)), fmt(rot), int(large), int(sweep),
                    fmt(px), fmt(py)))
            parts.append("".join(segs))
        elif c == "Z":
            parts.append("Z")
        else:
            sys.exit("不支持的路径命令：%s" % cmd)
    return "".join(parts)


def circle_path(cx, cy, r, xf):
    X, Y = xf.p(cx, cy)
    R = xf.l(r)
    return ("M%s,%s a%s,%s 0 1,0 %s,0 a%s,%s 0 1,0 %s,0 Z"
            % (fmt(X - R), fmt(Y), fmt(R), fmt(R), fmt(2 * R),
               fmt(R), fmt(R), fmt(-2 * R)))


def read_svg(path):
    s = open(path, encoding="utf-8").read()
    vb = re.search(r'viewBox="([^"]+)"', s)
    vb = [float(x) for x in vb.group(1).split()] if vb else [0, 0, 100, 100]
    return s, vb


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("svg")
    ap.add_argument("--foreground", required=True)
    ap.add_argument("--monochrome", required=True)
    ap.add_argument("--safe", type=float, default=62.0,
                    help="前景要落进的安全区直径（dp，108 viewport 内；圆屏建议 ≤64）")
    a = ap.parse_args()

    s, (vx, vy, vw, vh) = read_svg(a.svg)
    # <defs> 里是 marker/gradient 之类，不是要画的图形；且 marker 的 stroke 是
    # `context-stroke`，写进 VectorDrawable 会编译失败 —— 先整段丢掉。
    s = re.sub(r"<defs>.*?</defs>", "", s, flags=re.S)

    # 只取描边图形（path/circle）；白底 rect 交给 adaptive-icon 的 background 层
    strokes = []
    for m in re.finditer(r"<path\b([^>]*)/?>", s):
        attrs = m.group(1)
        d = re.search(r'\bd="([^"]+)"', attrs)
        if not d:
            continue
        if 'stroke="none"' in attrs or "fill=\"#" in attrs and "stroke=" not in attrs:
            # 纯填充的底板（rect 之外若有）跳过
            pass
        sw = re.search(r'stroke-width="([\d.]+)"', attrs)
        sc = re.search(r'stroke="([^"]+)"', attrs)
        if not sc or not re.match(r"^#[0-9A-Fa-f]{3,8}$", sc.group(1)):
            continue
        strokes.append(("path", d.group(1), float(sw.group(1)) if sw else 1.0, sc.group(1)))
    for m in re.finditer(r"<circle\b([^>]*)/?>", s):
        attrs = m.group(1)
        cx = float(re.search(r'cx="([\d.]+)"', attrs).group(1))
        cy = float(re.search(r'cy="([\d.]+)"', attrs).group(1))
        r = float(re.search(r'r="([\d.]+)"', attrs).group(1))
        fill = re.search(r'fill="([^"]+)"', attrs)
        strokes.append(("circle", (cx, cy, r), 0.0, fill.group(1) if fill else "#000000"))

    if not strokes:
        sys.exit("没解析到任何图形")

    # 计算整体 bbox（含描边宽度），据此定缩放
    xs, ys = [], []
    for kind, data, sw, _c in strokes:
        if kind == "path":
            pts = []
            for cmd, ns in parse_path(data):
                if cmd.upper() in ("M", "L", "C"):
                    for i in range(0, len(ns) - 1, 2):
                        pts.append((ns[i], ns[i + 1]))
            if not pts:
                continue
            # 每条路径各自算 bbox，再**四边各扩 sw/2**（描边是沿路径向两侧铺开的），
            # 最后取并集 —— 这样才是真实外接框，图标不会被算小。
            h = sw / 2.0
            xs += [min(px for px, _ in pts) - h, max(px for px, _ in pts) + h]
            ys += [min(py for _, py in pts) - h, max(py for _, py in pts) + h]
        else:
            cx, cy, r = data
            xs += [cx - r, cx + r]
            ys += [cy - r, cy + r]

    cx = (min(xs) + max(xs)) / 2.0
    cy = (min(ys) + max(ys)) / 2.0
    span = max(max(xs) - min(xs), max(ys) - min(ys))
    scale = a.safe / span
    xf = Xf(cx, cy, scale)
    print("原图 bbox 中心=(%.1f, %.1f) 跨度=%.1f → 缩放 %.4f" % (cx, cy, span, scale))

    def body(uniform_color=None):
        out = []
        for kind, data, sw, color in strokes:
            if kind == "path":
                out.append((conv_path(data, xf), xf.l(sw), uniform_color or color, False))
            else:
                out.append((circle_path(data[0], data[1], data[2], xf), 0.0,
                            uniform_color or color, True))
        return out

    def write(path, uniform_color=None, comment=""):
        lines = ['<?xml version="1.0" encoding="utf-8"?>']
        if comment:
            lines += ["<!-- %s -->" % comment]
        lines += ['<vector xmlns:android="http://schemas.android.com/apk/res/android"',
                  '    android:width="108dp"',
                  '    android:height="108dp"',
                  '    android:viewportWidth="108"',
                  '    android:viewportHeight="108">']
        for d, sw, color, filled in body(uniform_color):
            if filled:
                lines.append('    <path')
                lines.append('        android:pathData="%s"' % d)
                lines.append('        android:fillColor="%s" />' % color)
            else:
                lines.append('    <path')
                lines.append('        android:pathData="%s"' % d)
                lines.append('        android:strokeColor="%s"' % color)
                lines.append('        android:strokeWidth="%s"' % fmt(sw))
                lines.append('        android:strokeLineCap="round"')
                lines.append('        android:strokeLineJoin="round" />')
        lines.append('</vector>')
        os.makedirs(os.path.dirname(path), exist_ok=True)
        open(path, "w", encoding="utf-8", newline="\n").write("\n".join(lines) + "\n")
        print("写出 %s（%d 个图形）" % (path, len(body(uniform_color))))

    write(a.foreground, None,
          "由 tools/svg_icon_to_vector.py 从设计稿 SVG 生成，勿手改。\n"
          "     自适应图标前景层：淡蓝曲线组，已缩放进 108dp viewport 的安全区。")
    write(a.monochrome, "#FFFFFFFF",
          "Android 13+ 主题图标（monochrome）：同一组曲线的纯色剪影，系统自行着色。")


if __name__ == "__main__":
    main()
