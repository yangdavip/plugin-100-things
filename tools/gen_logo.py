#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成插件 Logo（256×256 PNG）：奶油纸感手账风——卡片 + 清单 + 勾选 + “100”。

纯标准库实现 PNG 编码（zlib + struct），不依赖 PIL。
"""
import math
import struct
import zlib

W = H = 256

PAPER = (250, 244, 232)
PAPER_DEEP = (240, 230, 212)
CARD = (255, 253, 247)
LINE = (228, 217, 196)
INK = (61, 54, 44)
ACCENT = (179, 137, 95)
ACCENT_DEEP = (142, 106, 68)
WARM = (214, 186, 140)

px = [[(0, 0, 0, 0) for _ in range(W)] for _ in range(H)]


def blend(x, y, color, alpha):
    if x < 0 or y < 0 or x >= W or y >= H or alpha <= 0:
        return
    r, g, b = color
    dr, dg, db, da = px[y][x]
    a = alpha + da * (1 - alpha)
    if a <= 0:
        px[y][x] = (0, 0, 0, 0)
        return
    nr = (r * alpha + dr * da * (1 - alpha)) / a
    ng = (g * alpha + dg * da * (1 - alpha)) / a
    nb = (b * alpha + db * da * (1 - alpha)) / a
    px[y][x] = (nr, ng, nb, a)


def rounded_rect_sdf(x, y, cx, cy, half_w, half_h, radius):
    """圆角矩形有向距离场：内部为负。"""
    dx = abs(x - cx) - (half_w - radius)
    dy = abs(y - cy) - (half_h - radius)
    outside = math.hypot(max(dx, 0), max(dy, 0))
    inside = min(max(dx, dy), 0)
    return outside + inside - radius


def fill_round_rect(cx, cy, half_w, half_h, radius, color, alpha=1.0, feather=1.0):
    for y in range(H):
        for x in range(W):
            d = rounded_rect_sdf(x + 0.5, y + 0.5, cx, cy, half_w, half_h, radius)
            if d <= 0:
                blend(x, y, color, alpha)
            elif d < feather:
                blend(x, y, color, alpha * (1 - d / feather))


def stroke_round_rect(cx, cy, half_w, half_h, radius, width, color, alpha=1.0):
    for y in range(H):
        for x in range(W):
            d = abs(rounded_rect_sdf(x + 0.5, y + 0.5, cx, cy, half_w, half_h, radius))
            if d <= width / 2:
                edge = 1.0 - (d / (width / 2))
                blend(x, y, color, alpha * min(1.0, edge + 0.35))


def fill_ellipse(cx, cy, rx, ry, color, alpha=1.0):
    for y in range(H):
        for x in range(W):
            v = ((x + 0.5 - cx) / rx) ** 2 + ((y + 0.5 - cy) / ry) ** 2
            if v <= 1.0:
                blend(x, y, color, alpha)


def stroke_ellipse(cx, cy, rx, ry, width, color, alpha=1.0):
    for y in range(H):
        for x in range(W):
            v = ((x + 0.5 - cx) / rx) ** 2 + ((y + 0.5 - cy) / ry) ** 2
            d = abs(math.sqrt(v) - 1.0)
            approx = min(rx, ry) * d
            if approx <= width / 2:
                blend(x, y, color, alpha * (1.0 - approx / (width / 2) * 0.6))


def fill_line(x0, y0, x1, y1, width, color, alpha=1.0):
    """粗线段（胶囊形）。"""
    dx, dy = x1 - x0, y1 - y0
    length2 = dx * dx + dy * dy
    for y in range(H):
        for x in range(W):
            px_, py_ = x + 0.5, y + 0.5
            t = 0.0 if length2 == 0 else max(0.0, min(1.0, ((px_ - x0) * dx + (py_ - y0) * dy) / length2))
            dist = math.hypot(px_ - (x0 + t * dx), py_ - (y0 + t * dy))
            if dist <= width / 2:
                blend(x, y, color, alpha * (1.0 - dist / (width / 2) * 0.5))


# ---------- 1) 背景：奶油纸底 + 暖色光斑 ----------
fill_round_rect(128, 128, 128, 128, 52, PAPER, 1.0)
for y in range(H):
    for x in range(W):
        t = ((x / W) * 0.5 + (y / H) * 0.5)
        r = PAPER[0] + (PAPER_DEEP[0] - PAPER[0]) * t
        g = PAPER[1] + (PAPER_DEEP[1] - PAPER[1]) * t
        b = PAPER[2] + (PAPER_DEEP[2] - PAPER[2]) * t
        if px[y][x][3] > 0:
            blend(x, y, (r, g, b), 0.55 * px[y][x][3])

# 右上角暖光晕
for y in range(H):
    for x in range(W):
        d = math.hypot(x - 186, y - 66)
        if d < 62:
            blend(x, y, WARM, 0.30 * (1 - d / 62) * px[y][x][3])

# ---------- 2) 卡片主体 ----------
fill_round_rect(128, 128, 92, 92, 22, CARD, 1.0)
stroke_round_rect(128, 128, 92, 92, 22, 2.0, LINE, 0.95)

# 卡片顶部胶带
fill_round_rect(128, 52, 34, 12, 6, WARM, 0.55)

# ---------- 3) 清单三行 ----------
line_y = [104, 132, 160]
# 已完成的一行：勾选 + 强调色
stroke_ellipse(78, line_y[0], 13, 13, 3.0, ACCENT, 1.0)
fill_line(72, line_y[0], 78, line_y[0] + 7, 3.2, ACCENT, 1.0)
fill_line(78, line_y[0] + 7, 86, line_y[0] - 8, 3.2, ACCENT, 1.0)
fill_line(102, line_y[0], 176, line_y[0], 6.0, ACCENT_DEEP, 0.92)

# 未完成的两行：细线 + 空心圆
for idx, ly in enumerate(line_y[1:], start=1):
    stroke_ellipse(78, ly, 12, 12, 2.4, LINE, 1.0)
    width = 68 if idx == 1 else 50
    fill_line(102, ly, 102 + width, ly, 5.0, INK, 0.34)

# ---------- 4) “100”字样：由三个数字笔画组成 ----------
def draw_digit_one(cx, cy, h, color, alpha=1.0):
    """数字 1：左上斜撇 + 粗竖笔 + 底横。"""
    top = cy - h / 2
    bottom = cy + h / 2
    # 斜撇
    fill_line(cx - 7, top + 1, cx + 1, top + 9, 4.2, color, alpha)
    # 竖笔（加粗，保证清晰）
    fill_line(cx + 1, top + 9, cx + 1, bottom, 5.0, color, alpha)
    # 底横
    fill_line(cx - 8, bottom, cx + 10, bottom, 4.2, color, alpha)


def draw_digit_zero(cx, cy, h, color, alpha=1.0):
    """数字 0：椭圆环。"""
    rx = h * 0.36
    ry = h / 2
    stroke_ellipse(cx, cy, rx, ry, 4.0, color, alpha)


digit_color = ACCENT_DEEP
draw_digit_zero(88, 196, 30, digit_color, 0.95)
draw_digit_one(122, 196, 30, digit_color, 0.95)
draw_digit_zero(152, 196, 30, digit_color, 0.95)

# ---------- 5) 编码 PNG ----------
raw = bytearray()
for y in range(H):
    raw.append(0)
    for x in range(W):
        r, g, b, a = px[y][x]
        # px 里 alpha 存的是 0-1，PNG 需要 0-255
        raw += bytes((int(r) & 255, int(g) & 255, int(b) & 255,
                      max(0, min(255, int(round(a * 255)))) & 255))


def chunk(tag, data):
    c = struct.pack(">I", len(data)) + tag + data
    return c + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)


png = (b"\x89PNG\r\n\x1a\n"
       + chunk(b"IHDR", struct.pack(">IIBBBBB", W, H, 8, 6, 0, 0, 0))
       + chunk(b"IDAT", zlib.compress(bytes(raw), 9))
       + chunk(b"IEND", b""))

out = "src/main/resources/logo.png"
with open(out, "wb") as f:
    f.write(png)
print("logo 已生成:", out, len(png), "bytes")
