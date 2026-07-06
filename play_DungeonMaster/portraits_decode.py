#!/usr/bin/env python3
# Decodifica i 24 ritratti dei campioni di Dungeon Master dall'atlante (grafica 26).
#
# Input (scritti dal ponte CSBwin in ./portraits/):
#   portraits/atlas26.raw  -> pixel grezzi, 4-bit CHUNKY (2 px/byte)
#   portraits/atlas26.txt  -> "width height stride_bytes"
#   portraits/palette.txt  -> 16 righe "r g b"
# Output:
#   portraits/atlas.png            -> l'intero atlante decodificato (riferimento)
#   portraits/portrait_NN.png      -> i 24 ritratti (32x29), ingranditi x8
#
# Atlante = 8 colonne x 3 righe, ogni cella 32x29 (24 campioni).
#
# Uso: portraits_decode.py [scala] [prefisso]
#   default:              scala 8, output portrait_NN.png (riferimento)
#   per l'HUD del viewer:  portraits_decode.py 2.75 hud_
#     -> portraits/hud_NN.png 88x80 (32x29 * 2.75, NEAREST: pixel-art nitida)
#     e' il mini-ritratto ORIGINALE DM del box campione (ANALISI_HUD_3D.md §6)
import os, sys
from PIL import Image

CELL_W, CELL_H = 32, 29
COLS, ROWS = 8, 3
SCALE = float(sys.argv[1]) if len(sys.argv) > 1 else 8.0
PREFIX = sys.argv[2] if len(sys.argv) > 2 else "portrait_"
HIGH_NIBBLE_FIRST = True   # se i colori sono "sfasati" prova a metterlo False

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

def main():
    base = "portraits"
    if not os.path.exists(os.path.join(base, "atlas26.raw")):
        print("manca portraits/atlas26.raw - avvia il gioco ed entra nel dungeon")
        return
    w, h, stride = (int(x) for x in open(os.path.join(base, "atlas26.txt")).read().split())
    raw = open(os.path.join(base, "atlas26.raw"), "rb").read()
    pal = load_palette(os.path.join(base, "palette.txt"))

    # Formato PLANARE Atari ST a 4 bitplane: ogni riga e' fatta di word
    # big-endian a gruppi di 4 (un piano ciascuno) per ogni 16 pixel.
    atlas = Image.new("RGB", (w, h))
    ap = atlas.load()
    groups = w // 16
    for y in range(h):
        rowoff = y * stride
        words = [(raw[rowoff + 2*k] << 8) | raw[rowoff + 2*k + 1]
                 for k in range(stride // 2)]
        for g in range(groups):
            p0, p1, p2, p3 = words[g*4 : g*4+4]
            for b in range(16):
                bit = 15 - b
                idx = (((p0>>bit)&1)) | (((p1>>bit)&1)<<1) | (((p2>>bit)&1)<<2) | (((p3>>bit)&1)<<3)
                ap[g*16 + b, y] = pal[idx]
    atlas.save(os.path.join(base, "atlas.png"))
    print("atlante:", (w, h), "-> portraits/atlas.png")

    n = 0
    for r in range(ROWS):
        for c in range(COLS):
            x0, y0 = c*CELL_W, r*CELL_H
            if x0+CELL_W > w or y0+CELL_H > h:
                continue
            cell = atlas.crop((x0, y0, x0+CELL_W, y0+CELL_H))
            out = os.path.join(base, "%s%02d.png" % (PREFIX, n))
            cell.resize((int(round(CELL_W*SCALE)), int(round(CELL_H*SCALE))),
                        Image.NEAREST).save(out)
            n += 1
    print("ritratti salvati:", n)

if __name__ == "__main__":
    main()
