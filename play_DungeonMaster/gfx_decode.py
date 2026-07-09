#!/usr/bin/env python3
# Decoder generico di basic-graphics DM estratte dal ponte (planare ST 4-bitplane).
# Uso:  python3 gfx_decode.py <cartella> [scala]
# Legge <cartella>/{nomemanifest}.txt ("N w h stride"), g<N>.raw, palette.txt
# e scrive <cartella>/g<N>.png (indice colore 0 = trasparente).
import os, sys, glob
from PIL import Image

def load_palette(path):
    pal = []
    if os.path.exists(path):
        for line in open(path):
            p = line.split()
            if len(p) >= 3:
                pal.append(tuple(int(v) for v in p[:3]))
    while len(pal) < 16:
        pal.append((255, 0, 255))
    return pal

# Post-processing per la cartella invgfx (schermata inventario):
#  - COLORKEY: il 2D blitta alcune grafiche con un indice-colore trasparente
#    (ultimo parametro di BLT2Viewport): g40 (menu resurrect) usa l'indice 6,
#    g27 (griglia name-entry) l'indice 4. Qui azzeriamo l'alpha di quel colore.
#  - FLIP: DrawImageRect dell'engine campiona il rettangolo sorgente
#    dall'immagine SPECCHIATA verticalmente (origine GL; verificato con
#    test_atlas.bb), quindi per atlanti e cornici scriviamo anche una copia
#    pre-specchiata gNf.png: campionata coi rect top-origin esce dritta.
# colorkey = 4° parametro dei blit del 2D: g40/g27 (BLT2Viewport), scudi
# 37-39 (BLT2Screen param 10), freccia pos/dir 28 (TAG0088b2 param 12)
COLORKEY = {40: 6, 27: 4, 37: 10, 38: 10, 39: 10, 28: 12,
            20: 8, 30: 12, 31: 12, 32: 12}   # pannello FOOD/WATER
FLIPPED  = (8, 10, 28, 33, 34, 35, 37, 38, 39, 42, 43, 44, 45, 46, 47, 48)
# SLICES: grafiche affettate in celle-PNG separate (gNc<i>.png) da disegnare
# con DrawImage (rispetta l'alpha; DrawImageRect e' una copia raw che
# schiaccerebbe i pixel trasparenti sul fondo). g28 = figurine pos/dir:
# passo 19, ma la CELLA del marching-order e' 18x13 (wRectPos1454) — il 2D
# ritaglia il blit alla cella, quindi affettiamo 18x13 per non sbordare.
SLICES   = {28: (19, 18, 13)}      # (passo, larghezza, altezza)
# SCALE2: grafiche usate ANCHE nella colonna destra del viewer (pannello
# attacco/menu azioni, scala MAGSC=2.0 invece di INVSC=2.75): oltre ai PNG
# alla scala principale scriviamo g<N>_2.png (e g<N>_2f.png se in FLIPPED)
# a scala fissa 2.0. 10 = pannello menu azioni, 42-48 = atlanti icone.
SCALE2   = (10, 42, 43, 44, 45, 46, 47, 48)
# SILHOUETTE: per il pannello attacco il 2D NON blitta l'icona a colori ma
# la rimappa con ShrinkBLT + color map Byte20194 (Character.cpp:4880):
# sfondo->ciano, colori visibili->NERO. Emettiamo quindi g<N>_2s.png (scala
# 2, NON specchiata): pixel visibili neri, resto trasparente — il viewer la
# affetta con LoadAnimImage (celle 32x32) e la disegna sul riquadro ciano.
SILHOUETTE = (42, 43, 44, 45, 46, 47, 48)

def postprocess(img, N, pal):
    if N in COLORKEY:
        r, g, b = pal[COLORKEY[N]]
        px = img.load()
        for y in range(img.height):
            for x in range(img.width):
                p = px[x, y]
                if p[0] == r and p[1] == g and p[2] == b:
                    px[x, y] = (r, g, b, 0)
    return img

def decode(raw, w, h, stride, pal, tkey=0):
    # tkey = indice-colore trasparente (default 0). Le decorazioni di pavimento
    # DM usano l'indice 10 come color-key (vedi TAG0088b2 in DrawFloorDecorations,
    # ultimo parametro = 10), non lo 0.
    img = Image.new("RGBA", (w, h))
    px = img.load()
    groups = (w + 15) // 16
    for y in range(h):
        off = y * stride
        words = [(raw[off + 2*k] << 8) | raw[off + 2*k + 1] for k in range(stride // 2)]
        for g in range(groups):
            if g*4 + 3 >= len(words):
                break
            p0, p1, p2, p3 = words[g*4:g*4+4]
            for b in range(16):
                x = g*16 + b
                if x >= w:
                    break
                bit = 15 - b
                idx = ((p0>>bit)&1) | (((p1>>bit)&1)<<1) | (((p2>>bit)&1)<<2) | (((p3>>bit)&1)<<3)
                r, gg, bb = pal[idx]
                px[x, y] = (r, gg, bb, 0 if idx == tkey else 255)
    return img

def main():
    base = sys.argv[1] if len(sys.argv) > 1 else "."
    scale = float(sys.argv[2]) if len(sys.argv) > 2 else 4.0
    tkey = int(sys.argv[3]) if len(sys.argv) > 3 else 0   # indice trasparente
    pal = load_palette(os.path.join(base, "palette.txt"))
    man = glob.glob(os.path.join(base, "*.txt"))
    # esclusi: palette e file di METADATI (non-manifest) esportati dal bridge
    skip = ("palette.txt", "anchor.txt", "rects.txt",
            "icondisplay.txt", "layout.txt")
    man = [m for m in man if os.path.basename(m) not in skip]
    if not man:
        print("nessun manifest in", base); return
    n = 0
    for line in open(man[0]):
        p = line.split()
        if len(p) < 4:
            continue
        N, w, h, stride = (int(x) for x in p[:4])
        raw = open(os.path.join(base, "g%d.raw" % N), "rb").read()
        img = postprocess(decode(raw, w, h, stride, pal, tkey), N, pal)
        big = img.resize((int(round(w*scale)), int(round(h*scale))), Image.NEAREST)
        out = os.path.join(base, "g%d.png" % N)
        big.save(out)
        if N in FLIPPED:
            big.transpose(Image.FLIP_TOP_BOTTOM).save(
                os.path.join(base, "g%df.png" % N))
        if N in SCALE2:
            two = img.resize((w*2, h*2), Image.NEAREST)
            two.save(os.path.join(base, "g%d_2.png" % N))
            if N in FLIPPED:
                two.transpose(Image.FLIP_TOP_BOTTOM).save(
                    os.path.join(base, "g%d_2f.png" % N))
            if N in SILHOUETTE:
                # lo sfondo delle celle degli atlanti NON e' l'indice 0 ma il
                # grigio pannello (palette 12 = 72,72,72, opaco): va reso
                # trasparente, tutto il resto diventa la sagoma nera
                bg = pal[12]
                sil = two.copy()
                spx = sil.load()
                for sy in range(sil.height):
                    for sx in range(sil.width):
                        p = spx[sx, sy]
                        if p[3] == 0 or (p[0], p[1], p[2]) == bg:
                            spx[sx, sy] = (0, 0, 0, 0)
                        else:
                            spx[sx, sy] = (0, 0, 0, 255)
                sil.save(os.path.join(base, "g%d_2s.png" % N))
        if N in SLICES:
            pitch, cw, ch = SLICES[N]
            k = 0
            for x0 in range(0, w - cw + 1, pitch):
                cell = img.crop((x0, 0, x0 + cw, ch))
                cell.resize((int(round(cw*scale)), int(round(ch*scale))),
                            Image.NEAREST).save(
                    os.path.join(base, "g%dc%d.png" % (N, k)))
                k += 1
        print("g%d %dx%d -> %s" % (N, w, h, os.path.basename(out)))
        n += 1
    print("decodificate:", n, "in", base)

if __name__ == "__main__":
    main()
