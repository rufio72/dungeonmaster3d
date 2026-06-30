#!/usr/bin/env python3
"""Genera le TARGHE DI MARMO con le scritte di Dungeon Master per il viewer 3D.

Nel DM originale le scritte sono in sovraimpressione 2D sul muro. In 3D non si
puo': qui ogni scritta diventa una targa di marmo con il testo INCISO (lettere
scavate), montata sulla parete nella posizione reale (la dà il bridge).

Per ogni record di dm_texts_<livello>.txt produce DUE texture:
  plaque_<L>_<i>.png     diffuse: marmo procedurale + scritta incisa
  plaque_<L>_<i>_n.png   normal map (convenzione OpenGL +Y) con la HEIGHT
                         impacchettata nell'ALPHA -> parallax-occlusion, come i
                         muri (in .bb: EntityTexture t,n,0,1 + TextureBlend n,4).

Le lettere sono RECESSE (height bassa) -> look "lapide scolpita". La profondità
del rilievo è in --depth; se in-game appaiono in rilievo invece che incise,
usare --raised (inverte la height).

Uso:
  python3 gen_plaques.py                      # legge dm_texts_0.txt
  python3 gen_plaques.py --level 0
  python3 gen_plaques.py --text "HALL OF CHAMPIONS" --out plaque_demo
"""
import argparse
import os
import sys
import numpy as np
from PIL import Image, ImageDraw, ImageFont, ImageFilter

# ---- dimensioni texture (la targa è larga e bassa, ~2.8:1) -----------------
TEX_W, TEX_H = 1024, 384
MARGIN = 0.12          # margine interno (frazione) per il testo
BORDER = 0.045         # cornice in rilievo attorno al bordo (frazione lato corto)

FONT_CANDIDATES = [
    "/usr/share/fonts/truetype/dejavu/DejaVuSerif-Bold.ttf",
    "/usr/share/fonts/truetype/liberation/LiberationSerif-Bold.ttf",
    "/usr/share/fonts/truetype/dejavu/DejaVuSerif.ttf",
]


def find_font():
    for p in FONT_CANDIDATES:
        if os.path.exists(p):
            return p
    sys.exit("Nessun font serif trovato (installa fonts-dejavu).")


# ---- marmo procedurale -----------------------------------------------------
def marble(w, h, seed=0):
    """Marmo grigio chiaro con venature. Deterministico per seed (niente Random
    proibito qui: usiamo np.random con seed esplicito)."""
    rng = np.random.RandomState(1234 + seed)
    # base: rumore frazionale a più ottave -> nuvole
    acc = np.zeros((h, w), np.float32)
    amp = 1.0
    for oct in range(5):
        sh, sw = max(1, h >> (oct + 1)), max(1, w >> (oct + 1))
        n = rng.rand(sh, sw).astype(np.float32)
        n = np.asarray(Image.fromarray((n * 255).astype(np.uint8)).resize((w, h), Image.BILINEAR), np.float32) / 255.0
        acc += amp * n
        amp *= 0.5
    acc /= acc.max()
    # venature: turbolenza che distorce un pattern sinusoidale diagonale
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    warp = (acc - 0.5) * 28.0
    veins = np.sin((xx + yy) * 0.018 + warp * 0.6)
    veins = np.abs(veins)
    veins = np.clip((veins - 0.55) / 0.45, 0, 1)  # solo le creste -> filamenti
    veins = np.asarray(Image.fromarray((veins * 255).astype(np.uint8)).filter(
        ImageFilter.GaussianBlur(0.8)), np.float32) / 255.0
    # composito colore: grigio caldo, screziato, con venature più scure
    base = 0.74 + 0.10 * (acc - 0.5)
    base = base - 0.16 * veins
    base = np.clip(base, 0.35, 0.95)
    rgb = np.stack([base * 0.99, base * 0.98, base * 0.95], -1)  # leggermente caldo
    return np.clip(rgb, 0, 1), acc


# ---- layout testo: auto-fit + word-wrap ------------------------------------
def fit_text(draw, text, font_path, box_w, box_h):
    words = text.split()
    best = None
    for size in range(140, 22, -3):
        font = ImageFont.truetype(font_path, size)
        # word-wrap greedy a questa dimensione
        lines, cur = [], ""
        for wd in words:
            t = (cur + " " + wd).strip()
            if draw.textlength(t, font=font) <= box_w or not cur:
                cur = t
            else:
                lines.append(cur); cur = wd
        if cur:
            lines.append(cur)
        asc, desc = font.getmetrics()
        lh = int((asc + desc) * 1.12)
        total_h = lh * len(lines)
        widest = max(draw.textlength(ln, font=font) for ln in lines)
        if total_h <= box_h and widest <= box_w:
            best = (font, lines, lh)
            break
    if best is None:
        font = ImageFont.truetype(font_path, 24)
        best = (font, [text], int(sum(font.getmetrics()) * 1.12))
    return best


def text_mask(text, font_path):
    """Maschera bianca (lettere=255) centrata, con cornice in rilievo."""
    img = Image.new("L", (TEX_W, TEX_H), 0)
    d = ImageDraw.Draw(img)
    box_w = TEX_W * (1 - 2 * MARGIN)
    box_h = TEX_H * (1 - 2 * MARGIN)
    font, lines, lh = fit_text(d, text, font_path, box_w, box_h)
    total_h = lh * len(lines)
    y = (TEX_H - total_h) / 2
    for ln in lines:
        w = d.textlength(ln, font=font)
        d.text(((TEX_W - w) / 2, y), ln, fill=255, font=font)
        y += lh
    return np.asarray(img, np.float32) / 255.0


def border_mask():
    """Cornice rettangolare in rilievo (1 sul bordo, 0 dentro/fuori)."""
    b = int(BORDER * TEX_H)
    m = np.zeros((TEX_H, TEX_W), np.float32)
    outer = b
    inner = int(b * 1.9)
    m[outer:TEX_H - outer, outer:TEX_W - outer] = 1.0
    m[inner:TEX_H - inner, inner:TEX_W - inner] = 0.0
    return m


# ---- height -> normal (stessa matematica di gen_normalmap, OpenGL +Y) ------
def height_to_normal(height, strength=4.0):
    gy, gx = np.gradient(height.astype(np.float32))
    nx = -gx * strength
    ny = -gy * strength
    nz = np.ones_like(height)
    ln = np.sqrt(nx * nx + ny * ny + nz * nz)
    nx, ny, nz = nx / ln, ny / ln, nz / ln
    r = (nx * 0.5 + 0.5)
    g = (ny * 0.5 + 0.5)          # +Y verso l'alto (OpenGL); invertibile a valle
    b = (nz * 0.5 + 0.5)
    return np.stack([r, g, b], -1)


def generate(text, out_base, depth=0.55, raised=False, seed=0):
    font_path = find_font()
    letters = text_mask(text, font_path)          # 0..1, lettere=1
    border = border_mask()

    # bevel: smussa i bordi delle lettere -> incisione con pareti inclinate
    soft = np.asarray(Image.fromarray((letters * 255).astype(np.uint8)).filter(
        ImageFilter.GaussianBlur(3.0)), np.float32) / 255.0
    soft_b = np.asarray(Image.fromarray((border * 255).astype(np.uint8)).filter(
        ImageFilter.GaussianBlur(2.0)), np.float32) / 255.0

    # height: parte dal marmo (micro-rilievo lieve), lettere RECESSE, bordo in rilievo
    rgb, acc = marble(TEX_W, TEX_H, seed)
    h = 0.5 + 0.04 * (acc - 0.5)
    sign = +1.0 if raised else -1.0               # incise (-) oppure in rilievo (+)
    h = h + sign * depth * soft
    h = h + 0.18 * soft_b                          # cornice sempre in rilievo
    h = np.clip(h, 0.0, 1.0)

    # diffuse: marmo + ombra nella scanalatura (AO) + filo di luce sul bordo inciso
    gy, gx = np.gradient(soft)
    edge_lo = np.clip(-(gx + gy), 0, None)         # lato in ombra
    edge_hi = np.clip((gx + gy), 0, None)          # lato illuminato
    ao = 0.55 * soft
    diff = rgb * (1.0 - ao[..., None])
    diff += (0.18 * edge_hi - 0.10 * edge_lo)[..., None]
    diff += 0.12 * soft_b[..., None]               # cornice un filo più chiara
    diff = np.clip(diff, 0, 1)

    # normal + height in alpha
    nrm = height_to_normal(h, strength=4.5)
    alpha = h                                       # height per il parallax

    Image.fromarray((diff * 255).astype(np.uint8), "RGB").save(out_base + ".png")
    rgba = np.concatenate([(nrm * 255).astype(np.uint8),
                           (alpha * 255).astype(np.uint8)[..., None]], -1)
    Image.fromarray(rgba, "RGBA").save(out_base + "_n.png")
    print(f"  -> {out_base}.png + {out_base}_n.png   \"{text}\"")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--level", type=int, default=0)
    ap.add_argument("--text", default=None, help="genera una sola targa da questo testo")
    ap.add_argument("--out", default="plaque_demo")
    ap.add_argument("--depth", type=float, default=0.55)
    ap.add_argument("--raised", action="store_true", help="lettere in rilievo invece che incise")
    args = ap.parse_args()

    if args.text is not None:
        generate(args.text, args.out, args.depth, args.raised)
        return

    path = f"dm_texts_{args.level}.txt"
    if not os.path.exists(path):
        sys.exit(f"{path} non trovato (gioca per farlo generare dal bridge).")
    with open(path) as f:
        lines = [l.rstrip("\n") for l in f]
    n = int(lines[0]); k = 1
    print(f"{n} targhe da {path}:")
    for i in range(n):
        fx, fy, d, text = lines[k], lines[k + 1], lines[k + 2], lines[k + 3]
        k += 4
        generate(text, f"plaque_{args.level}_{i}", args.depth, args.raised, seed=i)


if __name__ == "__main__":
    main()
