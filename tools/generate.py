#!/usr/bin/env python3
"""Vapor asset generator.

Regenerates the two trail textures. Run from anywhere:

    python tools/generate.py

- stream.png — the broad vapor wash: long horizontal streaks with a smooth translucent core and ragged,
  torn top/bottom edges. Tiles seamlessly along X (the ribbon's length axis) so the scroll never shows a
  seam, and reads as a continuous stream rather than repeated blobs.
- line.png — the crisp wingtip contrail: a clean line with a bright core and a soft falloff, uniform along
  its length (like the vortex line off a plane's wingtip).

Per-vertex colour supplies the tint and fade; the textures' own alpha is just the shape.
"""
import math, os, random
from PIL import Image, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TEX = os.path.join(ROOT, "src", "main", "resources", "assets", "vapor", "textures")


def wrap_noise(rnd, n, octaves=5):
    """Smooth 1D noise of length n that wraps seamlessly. Integer frequencies keep the wrap exact; picking
    them at random (rather than harmonically) breaks the scalloped periodicity so edges look torn."""
    waves = [(rnd.choice((2, 3, 5, 7, 9, 11, 13)), rnd.random() * 2 * math.pi,
              rnd.uniform(0.35, 1.0)) for _ in range(octaves)]
    out = []
    for i in range(n):
        t = i / n * 2 * math.pi
        v = sum(amp / math.sqrt(freq) * math.sin(t * freq + ph) for freq, ph, amp in waves)
        out.append(v)
    lo, hi = min(out), max(out)
    span = (hi - lo) or 1.0
    return [(v - lo) / span for v in out]


def stream_tex(path, w=256, h=32, seed=11):
    rnd = random.Random(seed)
    img = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    px = img.load()

    # Ragged edge profiles (wrap along X): how far the torn edge eats into the band at each column.
    top_edge = wrap_noise(rnd, w)
    bot_edge = wrap_noise(rnd, w, octaves=4)
    # A few long streak bands: each is a horizontal lane with its own brightness that drifts slowly.
    lanes = []
    for _ in range(6):
        centre = rnd.uniform(0.2, 0.8)
        width = rnd.uniform(0.06, 0.16)
        strength = rnd.uniform(0.35, 0.85)
        drift = wrap_noise(rnd, w, octaves=2)
        lanes.append((centre, width, strength, drift))

    for x in range(w):
        # edges eat up to ~30% in from each side, independently per column
        top = 0.06 + top_edge[x] * 0.26
        bot = 1.0 - (0.06 + bot_edge[x] * 0.26)
        for y in range(h):
            v = y / (h - 1)
            if v < top or v > bot:
                continue
            # smooth core: strongest mid-band, feathered toward the torn edges
            span = bot - top
            f = (v - top) / span            # 0..1 inside the band
            core = math.sin(f * math.pi) ** 0.8
            # long streaks: sum lane contributions (lanes drift vertically along X, staying elongated)
            streak = 0.35
            for centre, lw, strength, drift in lanes:
                lane_c = centre + (drift[x] - 0.5) * 0.14
                d = abs(v - lane_c) / lw
                if d < 1.0:
                    streak += strength * (1.0 - d) ** 2
            a = core * min(1.0, streak) * 0.85
            if a <= 0.015:
                continue
            px[x, y] = (255, 255, 255, int(min(1.0, a) * 255))

    img = img.filter(ImageFilter.GaussianBlur(0.6))
    os.makedirs(TEX, exist_ok=True)
    img.save(path)


def line_tex(path, w=128, h=8):
    img = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    px = img.load()
    c = (h - 1) / 2.0
    for y in range(h):
        d = abs(y - c) / (h / 2.0)          # 0 at core, 1 at edge
        a = max(0.0, 1.0 - d) ** 1.6        # bright core, quick soft falloff
        if a <= 0.02:
            continue
        av = int(a * 255)
        for x in range(w):
            px[x, y] = (255, 255, 255, av)
    os.makedirs(TEX, exist_ok=True)
    img.save(path)


def main():
    stream_tex(os.path.join(TEX, "stream.png"))
    line_tex(os.path.join(TEX, "line.png"))
    old = os.path.join(TEX, "vapor.png")
    if os.path.exists(old):
        os.remove(old)
        print("[vapor] removed old vapor.png")
    print("[vapor] generated stream.png + line.png")


if __name__ == "__main__":
    main()
