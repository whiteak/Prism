# Makes Prism's icon from Jason's own icon picture (art/source/jason-prism-icon.png), the built-in opening from his
# tall and wide pictures, and the
# wordmark from his own lettering (art/source/jason-wordmark.png),
# replacing the drawn crystal in masters. Run mkoverlay.py afterwards to refresh the overlay.
# usage: python3 art/from_picture.py
import os
from PIL import Image, ImageDraw, ImageEnhance, ImageFilter, ImageOps

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, '..', 'masters')
src = Image.open(os.path.join(HERE, 'source', 'jason-prism-icon.png')).convert('RGB')
S = 1024
TINTS = {
    'arctic_blue': (90, 170, 255),
    'copper': (230, 140, 80),
    'emerald': (70, 210, 140),
    'graphite': (170, 175, 185),
    'rose_gold': (240, 150, 170),
}


def crop(box, size=S):
    return src.crop(box).resize((size, size), Image.LANCZOS)


def tint(img, colour):
    grey = ImageOps.grayscale(img).convert('RGB')
    toned = ImageOps.colorize(ImageOps.grayscale(img), (6, 4, 10), colour, mid=tuple(c // 2 for c in colour))
    return Image.blend(Image.blend(grey, toned, 0.8), img, 0.25)


def circle(img, radius_frac=0.5, feather=0.0):
    mask = Image.new('L', img.size, 0)
    w, h = img.size
    r = radius_frac * w
    ImageDraw.Draw(mask).ellipse([w / 2 - r, h / 2 - r, w / 2 + r, h / 2 + r], fill=255)
    if feather:
        mask = mask.filter(ImageFilter.GaussianBlur(feather * w))
    out = img.convert('RGBA')
    out.putalpha(mask)
    return out


# Jason's icon picture is square, with the crystal (x 220-1035, y 250-815 of 1254) a little above the middle.
# Launchers show only the middle of an adaptive icon (about two thirds, less on Samsung's squircle), so the
# foreground is zoomed out around the crystal; past the picture's edges a blurred, enlarged copy fills in.
CX, CY, HALF = 627, 627, 940   # the whole picture fills the part launchers show (72 of 108)
ext = Image.new('RGB', (src.width + 2 * HALF, src.height + 2 * HALF))
big = src.resize(ext.size, Image.LANCZOS).filter(ImageFilter.GaussianBlur(30))
ext.paste(big, (0, 0))
ext.paste(src, (HALF, HALF))
fg = ext.crop((CX + HALF - HALF, CY + HALF - HALF, CX + HALF + HALF, CY + HALF + HALF)).resize((S, S), Image.LANCZOS)
icon = src.resize((S, S), Image.LANCZOS)     # legacy square icon: the whole picture
splash = circle(fg, 1 / 3, 0.01)             # Android 12 launch symbol, masked to its circle


def save_set(suffix, f):
    f(icon).save(os.path.join(OUT, f'icon_full{suffix}.png'), optimize=True)
    f(fg).convert('RGBA').save(os.path.join(OUT, f'fg{suffix}.png'), optimize=True)
    circle(f(icon)).save(os.path.join(OUT, f'icon_round{suffix}.png'), optimize=True)
    sp = f(splash.convert('RGB'))
    sp = sp.convert('RGBA'); sp.putalpha(splash.getchannel('A'))
    sp.save(os.path.join(OUT, f'splash{suffix}.png'), optimize=True)


save_set('', lambda im: im)
for name, colour in TINTS.items():
    save_set('_' + name, lambda im, c=colour: tint(im, c))

# More built-in icons to choose from (Settings > Appearance > App icon), cut from Jason's other pictures. Each
# key needs a line in mobile/icons.txt too (its name in the app). Boxes are squares in the source picture.
def adaptive(square):
    """The whole square in the part launchers show, a blurred, enlarged copy of it round the edge."""
    side = round(square.width * 108 / 72)
    out = square.resize((side, side), Image.LANCZOS).filter(ImageFilter.GaussianBlur(side / 40))
    edge = (side - square.width) // 2
    out.paste(square, (edge, edge))
    return out.resize((S, S), Image.LANCZOS)


def source(name):
    return Image.open(os.path.join(HERE, 'source', name)).convert('RGB')


EXTRA = {
    'close_up': (source('jason-prism-icon.png'), (277, 222, 977, 922)),     # the crystal, close
    'wanderer': (source('jason-prism.png'), (0, 0, 1254, 1254)),            # the first picture, whole
    'shattered': (source('jason-prism.png'), (290, 40, 990, 740)),          # its crystal breaking apart
    'guardians': (source('jason-prism-wide.png'), (486, 340, 1186, 941)),   # the two figures under the prism
    'title': (source('jason-prism-wide.png'), (536, 0, 1136, 600)),         # the crystal with PRISM under it
}
for key, (picture, box) in EXTRA.items():
    sq = picture.crop(box)
    side = min(sq.size)
    sq = sq.crop((0, 0, side, side)).resize((S, S), Image.LANCZOS)
    a = adaptive(sq)
    sq.save(os.path.join(OUT, f'icon_full_{key}.png'), optimize=True)
    a.convert('RGBA').save(os.path.join(OUT, f'fg_{key}.png'), optimize=True)
    circle(sq).save(os.path.join(OUT, f'icon_round_{key}.png'), optimize=True)
    circle(a, 1 / 3, 0.01).save(os.path.join(OUT, f'splash_{key}.png'), optimize=True)

# Built-in opening: Jason's two pictures with PRISM across them, a tall one for the phone
# (art/source/jason-prism-tall.png) and a wide one for the TV and PC (art/source/jason-prism-wide.png).
from PIL import ImageFilter
tall_src = Image.open(os.path.join(HERE, 'source', 'jason-prism-tall.png')).convert('RGB')
wide_src = Image.open(os.path.join(HERE, 'source', 'jason-prism-wide.png')).convert('RGB')
# The PC: the wide picture, which EditionLoadingArt.kt fills the window with from the middle.
wide = wide_src.resize((1920, round(1920 * wide_src.height / wide_src.width)), Image.LANCZOS)
wide.save(os.path.join(OUT, 'loading_phone.png'), optimize=True)
# The phone's tall version: the tall picture filling the screen from the middle (longer phones lose a little of
# its sides, never the lettering or the figures).
TW, TH = 1080, 2340
scale = max(TW / tall_src.width, TH / tall_src.height)
tall = tall_src.resize((round(tall_src.width * scale), round(tall_src.height * scale)), Image.LANCZOS)
tall = tall.crop(((tall.width - TW) // 2, (tall.height - TH) // 2, (tall.width - TW) // 2 + TW, (tall.height - TH) // 2 + TH))
tall.save(os.path.join(OUT, 'loading_phone_tall.png'), optimize=True)
# The TV: the wide picture.
tv = wide_src.resize((1920, 1080), Image.LANCZOS)
tv.save(os.path.join(OUT, 'loading_tv.png'), optimize=True)

# PC window and installer icon.
icon.save(os.path.join(HERE, '..', 'desktop', 'icon.png'))
icon.save(os.path.join(HERE, '..', 'desktop', 'icon.ico'), sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
icon.save(os.path.join(OUT, 'icon.png'))
icon.save(os.path.join(OUT, 'icon.ico'), sizes=[(16, 16), (32, 32), (48, 48), (256, 256)])
print('done')

# The wordmark: Jason's own (art/source/jason-wordmark.png, see-through background), trimmed to its glow.
wm_src = Image.open(os.path.join(HERE, 'source', 'jason-wordmark.png')).convert('RGBA')
wm_src = wm_src.crop(wm_src.getchannel('A').point(lambda v: 255 if v > 8 else 0).getbbox())
wm_src.save(os.path.join(OUT, 'wordmark.png'), optimize=True)

# The TV launcher banner: the same wide picture, which already has PRISM across it.
banner = tv.resize((1280, 720), Image.LANCZOS)
banner.save(os.path.join(OUT, 'banner.png'), optimize=True)
