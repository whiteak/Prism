# Writes mobile/overlay/: every image the Requiem edition replaces, redrawn from masters at the same size.
# Each icon colour variant (arctic_blue, copper, ...) gets the matching Prism colour variant.
# usage: python3 art/mkoverlay.py <path to NuvioMobile checkout>
import sys, os
from PIL import Image
repo = sys.argv[1]
here = os.path.dirname(os.path.abspath(__file__))
masters = os.path.join(here, '..', 'masters')
out_root = os.path.join(here, '..', 'mobile', 'overlay')
BG = (8, 6, 18)
VARIANTS = ['arctic_blue', 'copper', 'emerald', 'graphite', 'rose_gold']
src = os.path.join(repo, 'editions/requiem/overlay')
def pick(p):
    n = os.path.basename(p)
    if 'edition_loading_art' in n: return 'loading_phone'
    if 'monochrome' in n: return 'mono'
    if 'wordmark' in n: return 'wordmark'
    variant = next((v for v in VARIANTS if v in n), None)
    suffix = '_' + variant if variant else ''
    if '_foreground' in n or 'foreground.' in n: kind = 'fg'
    elif '_round' in n: kind = 'icon_round'
    elif 'splash_logo' in n: kind = 'splash'
    else: kind = 'icon_full'
    return kind + suffix
count = 0
for root, _, files in os.walk(src):
    for f in files:
        rel = os.path.relpath(os.path.join(root, f), src)
        if rel.startswith('iosApp'): continue
        target = os.path.join(repo, rel)
        base = Image.open(target if os.path.exists(target) else os.path.join(root, f))
        w, h = base.size
        has_alpha = base.mode in ('RGBA', 'LA', 'P') and (base.mode != 'P' or 'transparency' in base.info) or 'transparency' in base.info
        kind = pick(rel)
        m = Image.open(os.path.join(masters, kind + '.png')).convert('RGBA')
        if kind == 'wordmark':
            w = round(h * m.size[0] / m.size[1])  # keep the wordmark's own shape
        if kind == 'loading_phone':
            # The opening picture keeps its own shape (the PC's wide one); EditionLoadingArt.kt fills the window from it.
            out = m.resize((1600, round(1600 * m.height / m.width)), Image.LANCZOS)
        elif kind.startswith('loading'):
            # Fill the same shape from the middle.
            scale = max(w / m.width, h / m.height)
            m = m.resize((round(m.width * scale), round(m.height * scale)), Image.LANCZOS)
            l, t = (m.width - w) // 2, (m.height - h) // 2
            out = m.crop((l, t, l + w, t + h))
        else:
            out = m.resize((w, h), Image.LANCZOS)
        if not has_alpha or kind.startswith('icon_full') or kind.startswith('loading'):
            flat = Image.new('RGBA', out.size, BG + (255,)); flat.alpha_composite(out); out = flat.convert('RGB')
        dst = os.path.join(out_root, rel); os.makedirs(os.path.dirname(dst), exist_ok=True)
        if dst.endswith('.webp'): out.save(dst, quality=90, method=6)
        else: out.save(dst, optimize=True)
        count += 1
# The extra icons in mobile/icons.txt: every Copper icon file again under the new key, from that key's masters.
extra = [l.split('|')[0] for l in open(os.path.join(here, '..', 'mobile', 'icons.txt')).read().split('\n') if l.strip()]
for root, _, files in os.walk(os.path.join(repo, 'composeApp/src')):
    for f in files:
        if 'copper' not in f or 'wordmark' in f or f.endswith('.xml'): continue
        rel = os.path.relpath(os.path.join(root, f), repo)
        base = Image.open(os.path.join(root, f))
        w, h = base.size
        has_alpha = base.mode in ('RGBA', 'LA') or 'transparency' in base.info
        kind = pick(rel).replace('_copper', '')
        for key in extra:
            m = Image.open(os.path.join(masters, f'{kind}_{key}.png')).convert('RGBA')
            out = m.resize((w, h), Image.LANCZOS)
            if not has_alpha or kind == 'icon_full':
                flat = Image.new('RGBA', out.size, BG + (255,)); flat.alpha_composite(out); out = flat.convert('RGB')
            dst = os.path.join(out_root, rel.replace('copper', key)); os.makedirs(os.path.dirname(dst), exist_ok=True)
            if dst.endswith('.webp'): out.save(dst, quality=90, method=6)
            else: out.save(dst, optimize=True)
            count += 1
# The phone's tall opening (blur baked in), beside the square one the PC uses.
tall = Image.open(os.path.join(masters, 'loading_phone_tall.png')).convert('RGB')
tall.save(os.path.join(out_root, 'composeApp/src/commonMain/composeResources/drawable/edition_loading_art_tall.webp'), quality=90, method=6)
count += 1
print(count, 'files')
