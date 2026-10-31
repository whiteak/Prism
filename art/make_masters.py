# Draws Prism's own original artwork (a faceted crystal mark; superseded for the icon and opening by from_picture.py (Jason's own picture)) into masters/: the icon (and its colour variants for the in-app
# icon picker), the adaptive-icon foreground, monochrome and splash symbols, the wordmark, the TV banner
# and the built-in opening pictures.
# usage: python3 art/make_masters.py
import math, os
from PIL import Image, ImageDraw, ImageFilter, ImageFont, ImageChops

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, '..', 'masters')
os.makedirs(OUT, exist_ok=True)
FONT = '/usr/share/fonts/opentype/inter/InterDisplay-Bold.otf'

SPECTRUM = [(255, 77, 109), (255, 152, 67), (255, 226, 92), (92, 230, 140), (72, 190, 255), (148, 108, 255)]
VARIANTS = {
    'original': ((24, 16, 52), (8, 6, 18)),
    'arctic_blue': ((12, 34, 58), (4, 10, 20)),
    'copper': ((58, 30, 14), (18, 8, 4)),
    'emerald': ((10, 46, 34), (3, 14, 10)),
    'graphite': ((36, 38, 44), (10, 10, 12)),
    'rose_gold': ((58, 24, 34), (18, 7, 10)),
}


def radial(size, inner, outer, centre=(0.5, 0.45)):
    w, h = size
    img = Image.new('RGB', size)
    px = img.load()
    cx, cy = centre[0] * w, centre[1] * h
    r = math.hypot(max(cx, w - cx), max(cy, h - cy))
    for y in range(h):
        for x in range(w):
            t = min(1.0, math.hypot(x - cx, y - cy) / r)
            t = t ** 1.3
            px[x, y] = tuple(int(inner[i] * (1 - t) + outer[i] * t) for i in range(3))
    return img


def gradient_facet(S, pts, c0, c1, alpha=255):
    """A triangle filled with a straight blend from c0 (at its first corner) to c1 (across from it)."""
    layer = Image.new('RGBA', (S, S), (0, 0, 0, 0))
    a = pts[0]
    b = ((pts[1][0] + pts[2][0]) / 2, (pts[1][1] + pts[2][1]) / 2)
    dx, dy = b[0] - a[0], b[1] - a[1]
    length = dx * dx + dy * dy
    xs = [p[0] for p in pts]; ys = [p[1] for p in pts]
    x0, x1, y0, y1 = int(min(xs)), int(max(xs)) + 1, int(min(ys)), int(max(ys)) + 1
    grad = Image.new('RGBA', (x1 - x0, y1 - y0))
    gp = grad.load()
    for y in range(y0, y1):
        for x in range(x0, x1):
            t = max(0.0, min(1.0, ((x - a[0]) * dx + (y - a[1]) * dy) / length))
            gp[x - x0, y - y0] = tuple(int(c0[k] * (1 - t) + c1[k] * t) for k in range(3)) + (alpha,)
    mask = Image.new('L', (S, S), 0)
    ImageDraw.Draw(mask).polygon(pts, fill=255)
    layer.paste(grad, (x0, y0))
    layer.putalpha(ImageChops.multiply(layer.getchannel('A'), mask))
    return layer


def symbol(size, scale=1.0, mono=False):
    """Prism's mark: a faceted crystal triangle, each facet a different run of the spectrum."""
    S = size * 2
    layer = Image.new('RGBA', (S, S), (0, 0, 0, 0))
    c = S / 2
    side = S * 0.62 * scale
    h = side * math.sqrt(3) / 2
    top = (c, c - h * 0.62)
    left = (c - side / 2, c + h * 0.38)
    right = (c + side / 2, c + h * 0.38)
    mid = (c, c + h * 0.05)
    edge_w = max(2, int(S * 0.012 * scale))
    if mono:
        d = ImageDraw.Draw(layer)
        d.polygon([top, right, left], outline=(255, 255, 255, 255), width=int(edge_w * 2.4))
        for p in (top, left, right):
            d.line([mid, p], fill=(255, 255, 255, 255), width=int(edge_w * 1.6))
        return layer.resize((size, size), Image.LANCZOS)
    facets = [
        gradient_facet(S, [top, left, mid], SPECTRUM[0], SPECTRUM[2]),
        gradient_facet(S, [top, mid, right], SPECTRUM[2], SPECTRUM[4]),
        gradient_facet(S, [left, right, mid], SPECTRUM[5], SPECTRUM[3]),
    ]
    body = Image.new('RGBA', (S, S), (0, 0, 0, 0))
    for f in facets:
        body.alpha_composite(f)
    glow = body.filter(ImageFilter.GaussianBlur(S * 0.06))
    glow.putalpha(glow.getchannel('A').point(lambda v: int(v * 0.8)))
    layer.alpha_composite(glow)
    layer.alpha_composite(body)
    # A glassy sheen on the upper facets.
    sheen = Image.new('RGBA', (S, S), (0, 0, 0, 0))
    ImageDraw.Draw(sheen).polygon([top, ((top[0] + left[0]) / 2, (top[1] + left[1]) / 2), mid], fill=(255, 255, 255, 60))
    layer.alpha_composite(sheen)
    lines = Image.new('RGBA', (S, S), (0, 0, 0, 0))
    ld = ImageDraw.Draw(lines)
    ld.polygon([top, right, left], outline=(255, 255, 255, 235), width=edge_w)
    for p in (top, left, right):
        ld.line([mid, p], fill=(255, 255, 255, 170), width=max(1, edge_w // 2))
    layer.alpha_composite(lines)
    # Small four-point sparkle on the top corner.
    sp = Image.new('RGBA', (S, S), (0, 0, 0, 0))
    sd = ImageDraw.Draw(sp)
    r = S * 0.06 * scale
    sx, sy = top[0] + S * 0.1 * scale, top[1] + S * 0.04 * scale
    sd.polygon([(sx, sy - r), (sx + r * 0.18, sy - r * 0.18), (sx + r, sy), (sx + r * 0.18, sy + r * 0.18), (sx, sy + r), (sx - r * 0.18, sy + r * 0.18), (sx - r, sy), (sx - r * 0.18, sy - r * 0.18)], fill=(255, 255, 255, 240))
    layer.alpha_composite(sp.filter(ImageFilter.GaussianBlur(S * 0.004)))
    return layer.resize((size, size), Image.LANCZOS)


def icon(variant, size=1024):
    inner, outer = VARIANTS[variant]
    bg = radial((size, size), inner, outer).convert('RGBA')
    bg.alpha_composite(symbol(size, 1.0))
    return bg


def rounded(img, radius_frac=0.0, circle=False):
    w, h = img.size
    mask = Image.new('L', (w * 2, h * 2), 0)
    md = ImageDraw.Draw(mask)
    if circle:
        md.ellipse([0, 0, w * 2 - 1, h * 2 - 1], fill=255)
    else:
        md.rounded_rectangle([0, 0, w * 2 - 1, h * 2 - 1], radius=int(w * 2 * radius_frac), fill=255)
    mask = mask.resize((w, h), Image.LANCZOS)
    out = img.copy()
    out.putalpha(ImageChops.multiply(out.getchannel('A'), mask))
    return out


def wordmark(height=240):
    font = ImageFont.truetype(FONT, int(height * 0.82))
    text = 'PRISM'
    tracking = int(height * 0.12)
    widths = [font.getbbox(ch)[2] - font.getbbox(ch)[0] for ch in text]
    w = sum(widths) + tracking * (len(text) - 1) + height // 2
    img = Image.new('RGBA', (w, height), (0, 0, 0, 0))
    mask = Image.new('L', (w, height), 0)
    md = ImageDraw.Draw(mask)
    x = height // 4
    for ch, cw in zip(text, widths):
        bb = font.getbbox(ch)
        md.text((x - bb[0], (height - (bb[3] - bb[1])) / 2 - bb[1]), ch, font=font, fill=255)
        x += cw + tracking
    # White letters with a thin spectrum sweep along the bottom edge.
    fill = Image.new('RGBA', (w, height), (255, 255, 255, 255))
    sweep = Image.new('RGBA', (w, height), (0, 0, 0, 0))
    sd = ImageDraw.Draw(sweep)
    for xx in range(w):
        t = xx / max(1, w - 1) * (len(SPECTRUM) - 1)
        i = min(int(t), len(SPECTRUM) - 2)
        f = t - i
        col = tuple(int(SPECTRUM[i][k] * (1 - f) + SPECTRUM[i + 1][k] * f) for k in range(3))
        sd.line([(xx, int(height * 0.62)), (xx, height)], fill=col + (255,))
    fill.alpha_composite(sweep)
    img = Image.composite(fill, img, mask)
    glow = Image.new('RGBA', (w, height), (180, 160, 255, 0))
    glow.putalpha(mask.filter(ImageFilter.GaussianBlur(height * 0.06)).point(lambda v: v * 0.5))
    out = Image.new('RGBA', (w, height), (0, 0, 0, 0))
    out.alpha_composite(glow)
    out.alpha_composite(img)
    return out


def opening(size):
    w, h = size
    base = radial(size, (30, 20, 62), (5, 4, 12), centre=(0.5, 0.42)).convert('RGBA')
    # Soft ribbons of spectrum colour drifting across, like light through glass.
    wash = Image.new('RGBA', size, (0, 0, 0, 0))
    wd = ImageDraw.Draw(wash)
    for i, col in enumerate(SPECTRUM):
        y = h * (0.18 + i * 0.12)
        pts = []
        for k in range(0, 41):
            x = w * k / 40
            pts.append((x, y + math.sin(k / 40 * math.pi * 2 + i) * h * 0.04))
        wd.line(pts, fill=col + (70,), width=int(min(w, h) * 0.07))
    base.alpha_composite(wash.filter(ImageFilter.GaussianBlur(min(w, h) * 0.07)))
    sym = symbol(int(min(w, h) * 0.5), 1.0)
    base.alpha_composite(sym, (int(w / 2 - sym.width / 2), int(h * 0.42 - sym.height / 2)))
    vignette = Image.new('L', size, 0)
    ImageDraw.Draw(vignette).ellipse([-w * 0.3, -h * 0.2, w * 1.3, h * 1.2], fill=255)
    vignette = vignette.filter(ImageFilter.GaussianBlur(min(w, h) * 0.12))
    dark = Image.new('RGBA', size, (0, 0, 0, 255))
    dark.putalpha(vignette.point(lambda v: int((255 - v) * 0.75)))
    base.alpha_composite(dark)
    return base.convert('RGB')


for name in VARIANTS:
    full = icon(name)
    suffix = '' if name == 'original' else '_' + name
    full.convert('RGB').save(os.path.join(OUT, f'icon_full{suffix}.png'))
    # Adaptive icon foreground: the variant's colour with the symbol inside the 66 % safe zone, so each
    # variant looks different even though they share one background colour (Android masks it).
    fg = radial((1024, 1024), *VARIANTS[name]).convert('RGBA')
    s = symbol(int(1024 * 0.7), 1.0)
    fg.alpha_composite(s, ((1024 - s.width) // 2, (1024 - s.height) // 2))
    fg.save(os.path.join(OUT, f'fg{suffix}.png'))
    rounded(full, circle=True).save(os.path.join(OUT, f'icon_round{suffix}.png'))
    s = symbol(1024, 0.62)
    s.save(os.path.join(OUT, f'splash{suffix}.png'))
symbol(1024, 0.8, mono=True).save(os.path.join(OUT, 'mono.png'))
wm = wordmark()
wm.save(os.path.join(OUT, 'wordmark.png'))
banner = radial((1280, 720), (24, 16, 52), (8, 6, 18)).convert('RGBA')
sym = symbol(560, 1.0)
banner.alpha_composite(sym, (40, 80))
bw = wm.resize((int(wm.width * 1.4), int(wm.height * 1.4)), Image.LANCZOS)
banner.alpha_composite(bw, (560, (720 - bw.height) // 2))
banner.convert('RGB').save(os.path.join(OUT, 'banner.png'))
opening((1080, 2340)).save(os.path.join(OUT, 'loading_phone.png'))
opening((1920, 1080)).save(os.path.join(OUT, 'loading_tv.png'))
# Windows icon for the PC app.
icon('original').save(os.path.join(OUT, 'icon.ico'), sizes=[(256, 256), (128, 128), (64, 64), (48, 48), (32, 32), (16, 16)])
icon('original').resize((512, 512), Image.LANCZOS).save(os.path.join(OUT, 'icon.png'))
print('masters written to', os.path.normpath(OUT))
