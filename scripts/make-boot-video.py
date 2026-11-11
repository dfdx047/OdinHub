#!/usr/bin/env python3
"""
Gera app/src/main/res/raw/boot_video.mp4: a animação de arranque original do Odin Hub.

O ícone monta-se peça a peça (grelha hexagonal, hexágono do chip, a runa Ansuz "entalhada" em
rubi, pinos, os três pontos do Odin 3) e o nome ODIN HUB entra por baixo. O som é sintetizado
aqui (sopro + pulso grave + carrilhão), sem samples de terceiros.

Uso:  python3 -I scripts/make-boot-video.py [saida.mp4]
Precisa de: Pillow, numpy e ffmpeg no PATH.
"""
import math
import os
import subprocess
import sys
import tempfile
import wave

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FONT = os.path.join(ROOT, "app/src/main/res/font/outfit_regular.ttf")
OUT = sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, "app/src/main/res/raw/boot_video.mp4")

W, H = 1920, 1080
SS = 2                      # supersampling (antialiasing das linhas)
FPS = 30
DUR = 3.4
N = int(DUR * FPS)

BG_TOP = (24, 24, 32)
BG_BOT = (6, 6, 9)
WHITE = (243, 244, 246)
RUBY = (229, 0, 43)
EMBER = (255, 90, 60)
GREY = (150, 152, 162)

# Ícone no espaço 108x108 (o mesmo do vetor do launcher), escalado para o ecrã.
SCALE = 8.0
CX, CY = W / 2, H / 2 - 115


def P(x, y):
    """Ponto do espaço do ícone (108x108) para píxeis do ecrã supersampled."""
    return ((CX + (x - 54) * SCALE) * SS, (CY + (y - 54) * SCALE) * SS)


HEX = [(54, 30.5), (74.35, 42.25), (74.35, 65.75), (54, 77.5), (33.65, 65.75), (33.65, 42.25), (54, 30.5)]
RUNE = [[(48.5, 40.5), (48.5, 67.5)], [(48.5, 41.5), (60.5, 52)], [(48.5, 52), (60.5, 62.5)]]
PINS = [((28.5, y), (24.5, y)) for y in (47, 54, 61)] + [((79.5, y), (83.5, y)) for y in (47, 54, 61)]
DOTS = [(47.5, 84, WHITE), (54, 84, WHITE), (60.5, 84, EMBER)]


def clamp(v, a=0.0, b=1.0):
    return max(a, min(b, v))


def phase(t, start, end):
    return clamp((t - start) / (end - start))


def ease(x):
    return 1 - (1 - x) ** 3


def ease_back(x):
    c = 1.70158
    return 1 + (c + 1) * (x - 1) ** 3 + c * (x - 1) ** 2


def partial_polyline(points, frac):
    """Os primeiros `frac` (0..1) do comprimento da polilinha."""
    segs = list(zip(points, points[1:]))
    lens = [math.dist(a, b) for a, b in segs]
    target = sum(lens) * frac
    out = [points[0]]
    for (a, b), l in zip(segs, lens):
        if target <= 0:
            break
        if target >= l:
            out.append(b)
            target -= l
        else:
            k = target / l
            out.append((a[0] + (b[0] - a[0]) * k, a[1] + (b[1] - a[1]) * k))
            break
    return out


def line(draw, pts, color, width):
    pts = [P(*p) for p in pts]
    if len(pts) < 2:
        return
    w = int(width * SCALE * SS)
    draw.line(pts, fill=color, width=w, joint="curve")
    r = w / 2
    for x, y in (pts[0], pts[-1]):  # pontas redondas
        draw.ellipse((x - r, y - r, x + r, y + r), fill=color)


def background():
    g = np.linspace(0, 1, H)[:, None]
    top = np.array(BG_TOP)[None, None, :]
    bot = np.array(BG_BOT)[None, None, :]
    arr = (top * (1 - g[..., None]) + bot * g[..., None]).repeat(W, axis=1)
    return Image.fromarray(arr.astype(np.uint8), "RGB")


def hex_grid_layer():
    """Grelha de hexágonos ténue (como as células de um die)."""
    img = Image.new("L", (W, H), 0)
    d = ImageDraw.Draw(img)
    r = 46
    dx, dy = r * math.sqrt(3), r * 1.5
    for row in range(-1, int(H / dy) + 2):
        for col in range(-1, int(W / dx) + 2):
            x = col * dx + (dx / 2 if row % 2 else 0)
            y = row * dy
            pts = [(x + r * math.cos(math.radians(a)), y + r * math.sin(math.radians(a))) for a in range(-90, 271, 60)]
            d.line(pts, fill=255, width=1)
    # Vinheta: a grelha só se vê perto do centro.
    yy, xx = np.mgrid[0:H, 0:W]
    dist = np.sqrt(((xx - CX) / (W * 0.45)) ** 2 + ((yy - CY) / (H * 0.55)) ** 2)
    mask = np.clip(1.2 - dist, 0, 1)
    return np.array(img, dtype=np.float32) / 255.0 * mask


def render_frame(t, base, grid, font_big, font_small):
    frame = base.copy()

    # 1) Grelha a acender (0.0 - 0.7) e a apagar no fim.
    g_alpha = 0.22 * ease(phase(t, 0.0, 0.7)) * (1 - 0.6 * phase(t, 2.6, 3.4))
    if g_alpha > 0:
        tint = np.array(RUBY, dtype=np.float32)[None, None, :]
        arr = np.array(frame, dtype=np.float32)
        a = (grid * g_alpha)[..., None]
        frame = Image.fromarray((arr * (1 - a) + tint * a).astype(np.uint8))

    # 2) Feixe de varrimento horizontal (0.1 - 0.9).
    sweep = phase(t, 0.1, 0.9)
    if 0 < sweep < 1:
        y = int(H * ease(sweep))
        band = Image.new("L", (W, H), 0)
        ImageDraw.Draw(band).rectangle((0, y - 2, W, y + 2), fill=120)
        band = band.filter(ImageFilter.GaussianBlur(10))
        frame = Image.composite(Image.new("RGB", (W, H), EMBER), frame, band)

    # 3) Brilho rubi atrás do chip (cresce com a runa, pulsa no fim).
    glow_k = 0.55 * ease(phase(t, 1.0, 1.9)) + 0.25 * math.sin(math.pi * phase(t, 2.2, 3.0))
    if glow_k > 0:
        glow = Image.new("L", (W // 4, H // 4), 0)
        gx, gy, gr = CX / 4, CY / 4, 30 * SCALE / 4
        ImageDraw.Draw(glow).ellipse((gx - gr, gy - gr, gx + gr, gy + gr), fill=int(255 * clamp(glow_k)))
        glow = glow.filter(ImageFilter.GaussianBlur(28)).resize((W, H), Image.BILINEAR)
        frame = Image.composite(Image.new("RGB", (W, H), RUBY), frame, glow.point(lambda v: int(v * 0.55)))

    # Camada vetorial em supersampling.
    layer = Image.new("RGBA", (W * SS, H * SS), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)

    # 4) Hexágono a desenhar-se (0.35 - 1.25).
    hx = ease(phase(t, 0.35, 1.25))
    if hx > 0:
        line(d, partial_polyline(HEX, hx), WHITE + (235,), 3.6)

    # 5) Runa Ansuz "entalhada", traço a traço (1.0 - 1.85).
    for i, stroke in enumerate(RUNE):
        k = ease(phase(t, 1.0 + i * 0.22, 1.35 + i * 0.25))
        if k > 0:
            col = tuple(int(RUBY[c] + (EMBER[c] - RUBY[c]) * 0.4) for c in range(3))
            line(d, partial_polyline(stroke, k), col + (255,), 5.4)

    # 6) Pinos a deslizar para fora do chip (1.5 - 1.95).
    pk = ease(phase(t, 1.5, 1.95))
    if pk > 0:
        for a, b in PINS:
            end = (a[0] + (b[0] - a[0]) * pk, a[1])
            line(d, [a, end], WHITE + (int(115 * pk),), 2.2)

    # 7) Três pontos (Odin 3), um de cada vez (1.75 - 2.25).
    for i, (x, y, col) in enumerate(DOTS):
        k = phase(t, 1.75 + i * 0.12, 1.98 + i * 0.12)
        if k > 0:
            r = 1.7 * SCALE * SS * ease_back(k)
            px, py = P(x, y)
            d.ellipse((px - r, py - r, px + r, py + r), fill=col + (int(255 * min(1, k * 1.5)),))

    layer = layer.resize((W, H), Image.LANCZOS)
    frame = frame.convert("RGBA")
    frame.alpha_composite(layer)

    # 8) Nome por baixo (2.0 - 2.6).
    tk = ease(phase(t, 2.0, 2.6))
    if tk > 0:
        txt = Image.new("RGBA", (W, H), (0, 0, 0, 0))
        td = ImageDraw.Draw(txt)
        w_odin = td.textlength("ODIN", font=font_big)
        w_hub = td.textlength("HUB", font=font_big)
        gap = 26
        total = w_odin + gap + w_hub
        x0 = (W - total) / 2
        y0 = CY + 34 * SCALE + 20 * (1 - tk)
        a = int(255 * tk)
        td.text((x0, y0), "ODIN", font=font_big, fill=RUBY + (a,), stroke_width=2, stroke_fill=RUBY + (a,))
        td.text((x0 + w_odin + gap, y0), "HUB", font=font_big, fill=WHITE + (a,))
        sub = "for AYN Odin 3"
        ws = td.textlength(sub, font=font_small)
        td.text(((W - ws) / 2, y0 + 158), sub, font=font_small, fill=GREY + (int(a * 0.9),))
        frame.alpha_composite(txt)

    # 9) Fade final para o fundo da app (3.1 - 3.4).
    f = phase(t, 3.1, 3.4)
    if f > 0:
        frame = Image.blend(frame, Image.new("RGBA", (W, H), BG_BOT + (255,)), ease(f))
    return frame.convert("RGB")


def synth_audio(path, sr=48000):
    """Som original: sopro filtrado, pulso grave quando a runa acende e um carrilhão no fim."""
    n = int(DUR * sr)
    t = np.arange(n) / sr
    rng = np.random.default_rng(3)
    out = np.zeros(n)

    # Sopro: ruído filtrado (média móvel) com envelope 0.1 - 1.3 s.
    noise = np.convolve(rng.standard_normal(n), np.ones(60) / 60, mode="same")
    env = np.clip((t - 0.1) / 0.6, 0, 1) * np.clip((1.3 - t) / 0.4, 0, 1)
    out += noise * env * 0.9

    # Pulso grave (1.0 s), sweep 90 -> 45 Hz.
    k = t >= 1.0
    tt = t[k] - 1.0
    out[k] += np.sin(2 * np.pi * (90 * tt - 22 * tt ** 2)) * np.exp(-tt * 5) * 0.55

    # Carrilhão (1.85 s): parciais de um "metal" afinado em Lá.
    k = t >= 1.85
    tt = t[k] - 1.85
    for f, a, dec in ((880, 0.22, 2.2), (1318.5, 0.14, 2.8), (1760, 0.08, 3.6), (2637, 0.04, 5.0)):
        out[k] += a * np.sin(2 * np.pi * f * tt) * np.exp(-tt * dec)

    fade = np.clip((DUR - t) / 0.3, 0, 1)
    out = np.tanh(out * 1.4) * 0.7 * fade
    pcm = (out * 32767).astype(np.int16)
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(sr)
        w.writeframes(pcm.tobytes())


def main():
    font_big = ImageFont.truetype(FONT, 120)
    font_small = ImageFont.truetype(FONT, 40)
    base = background()
    grid = hex_grid_layer()
    with tempfile.TemporaryDirectory() as tmp:
        wav = os.path.join(tmp, "boot.wav")
        synth_audio(wav)
        cmd = [
            "ffmpeg", "-y", "-loglevel", "error",
            "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{W}x{H}", "-r", str(FPS), "-i", "-",
            "-i", wav,
            "-c:v", "libx264", "-preset", "slow", "-crf", "20", "-pix_fmt", "yuv420p", "-profile:v", "high",
            "-c:a", "aac", "-b:a", "128k", "-shortest", "-movflags", "+faststart", OUT,
        ]
        proc = subprocess.Popen(cmd, stdin=subprocess.PIPE)
        for i in range(N):
            proc.stdin.write(render_frame(i / FPS, base, grid, font_big, font_small).tobytes())
        proc.stdin.close()
        if proc.wait() != 0:
            sys.exit("ffmpeg falhou")
    print(f"{OUT}: {os.path.getsize(OUT) // 1024} KB, {N} fotogramas")


if __name__ == "__main__":
    main()
