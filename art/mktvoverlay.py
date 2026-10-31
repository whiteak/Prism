# Writes the images in tv/overlay/: every icon, banner, wordmark and opening picture the TV app shows,
# redrawn from masters at the size NuvioTV uses. Colour variants of the icon use the matching Prism variant.
# usage: python3 art/mktvoverlay.py <path to NuvioTV checkout>
import sys, os, glob
from PIL import Image
repo = sys.argv[1]
here = os.path.dirname(os.path.abspath(__file__))
masters = os.path.join(here, '..', 'masters')
out_root = os.path.join(here, '..', 'tv', 'overlay')
BG = (8, 6, 18)
VARIANTS = ['arctic_blue', 'copper', 'emerald', 'graphite', 'rose_gold']
res = 'app/src/main/res'
targets = ['drawable-nodpi/edition_loading_art.webp', 'drawable-nodpi/tv_banner.png', 'drawable/tv_banner.png',
           'drawable/app_logo_mark.png', 'drawable/ic_launcher.png']
targets += [os.path.relpath(p, os.path.join(repo, res)) for p in
            glob.glob(os.path.join(repo, res, 'drawable', 'app_logo_wordmark*.png')) +
            glob.glob(os.path.join(repo, res, 'mipmap-*', 'ic_launcher*.png')) +
            glob.glob(os.path.join(repo, res, 'mipmap-*', 'banner*.png'))]
def fill(m, w, h):
    scale = max(w / m.width, h / m.height)
    m = m.resize((round(m.width * scale), round(m.height * scale)), Image.LANCZOS)
    l, t = (m.width - w) // 2, (m.height - h) // 2
    return m.crop((l, t, l + w, t + h))
for rel in sorted(targets):
    base = Image.open(os.path.join(repo, res, rel)); w, h = base.size
    n = os.path.basename(rel)
    variant = next((v for v in VARIANTS if v in n), None)
    if 'edition_loading_art' in n: out = fill(Image.open(os.path.join(masters, 'loading_tv.png')).convert('RGBA'), w, h)
    elif 'banner' in n: out = fill(Image.open(os.path.join(masters, 'banner.png')).convert('RGBA'), w, h)
    elif 'wordmark' in n:
        m = Image.open(os.path.join(masters, 'wordmark.png')).convert('RGBA')
        out = m.resize((round(h * m.width / m.height), h), Image.LANCZOS)  # keep the wordmark's own shape
    else:
        out = Image.open(os.path.join(masters, 'icon_full' + ('_' + variant if variant else '') + '.png')).convert('RGBA').resize((w, h), Image.LANCZOS)
    if 'wordmark' not in n:
        flat = Image.new('RGBA', out.size, BG + (255,)); flat.alpha_composite(out); out = flat.convert('RGB')
    dst = os.path.join(out_root, res, rel); os.makedirs(os.path.dirname(dst), exist_ok=True)
    if dst.endswith('.webp'): out.save(dst, quality=90, method=6)
    else: out.save(dst, optimize=True)
print(len(targets), 'files')
