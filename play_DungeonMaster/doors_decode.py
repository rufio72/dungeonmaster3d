#!/usr/bin/env python3
# Decodifica le grafiche delle PORTE di Dungeon Master estratte dal ponte CSBwin.
#
# Input (scritti dal ponte in ./doors/):
#   doors/doors.txt    -> righe "N width height stride_bytes"
#   doors/g<N>.raw     -> pixel grezzi, PLANARE Atari ST a 4 bitplane
#   doors/palette.txt  -> 16 righe "r g b"
#   doors/anchor.txt   -> i 6 numeri di grafica porta in uso nel livello
# Output:
#   doors/door_g<N>.png  -> ogni grafica decodificata (indice colore 0 = trasparente)
#
# Formato identico ai ritratti (vedi portraits_decode.py): ogni riga e' una
# sequenza di word big-endian a gruppi di 4 (un bitplane ciascuno) per 16 pixel.
import os
from PIL import Image

BASE = "doors"
SCALE = 4
TRANSPARENT_INDEX = 0   # colore 0 = trasparente nelle grafiche DM

# Le ante stanno in 108..119: 4 stili x 3 distanze. Offset nello stile: 0=F1
# (lontana), 1=F2, 2=F3 (vicina/grande). Nomi descrittivi degli stili osservati.
STYLE_NAME = ["legno_borchie", "assi_legno", "ferro", "fulmine"]
DIST_NAME  = ["F1_lontana", "F2_media", "F3_vicina"]

def door_label(N):
    if 108 <= N <= 119:
        s, dd = divmod(N - 108, 3)
        if s < len(STYLE_NAME):
            return "style%d_%s_%s" % (s, STYLE_NAME[s], DIST_NAME[dd])
    return None

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

def decode(raw, w, h, stride, pal):
    img = Image.new("RGBA", (w, h))
    px = img.load()
    groups = (w + 15) // 16
    for y in range(h):
        rowoff = y * stride
        words = [(raw[rowoff + 2*k] << 8) | raw[rowoff + 2*k + 1]
                 for k in range(stride // 2)]
        for g in range(groups):
            if (g*4 + 3) >= len(words):
                break
            p0, p1, p2, p3 = words[g*4 : g*4+4]
            for b in range(16):
                x = g*16 + b
                if x >= w:
                    break
                bit = 15 - b
                idx = ((p0>>bit)&1) | (((p1>>bit)&1)<<1) | (((p2>>bit)&1)<<2) | (((p3>>bit)&1)<<3)
                r, gg, bb = pal[idx]
                a = 0 if idx == TRANSPARENT_INDEX else 255
                px[x, y] = (r, gg, bb, a)
    return img

def main():
    man = os.path.join(BASE, "doors.txt")
    if not os.path.exists(man):
        print("manca doors/doors.txt - avvia il gioco ed entra nel dungeon")
        return
    pal = load_palette(os.path.join(BASE, "palette.txt"))

    anchors = set()
    ap = os.path.join(BASE, "anchor.txt")
    if os.path.exists(ap):
        print("--- grafiche porta in uso nel livello (anchor) ---")
        for line in open(ap):
            print("   ", line.rstrip())
            parts = line.split()
            if parts:
                try: anchors.add(int(parts[-1]))
                except ValueError: pass

    n = 0
    for line in open(man):
        p = line.split()
        if len(p) < 4:
            continue
        N, w, h, stride = (int(x) for x in p[:4])
        label = door_label(N)
        if label is None:
            continue   # non e' un'anta (frammento muro/scala o font): salta
        raw = open(os.path.join(BASE, "g%d.raw" % N), "rb").read()
        img = decode(raw, w, h, stride, pal)
        tag = " (in uso nel livello)" if N in anchors else ""
        out = os.path.join(BASE, "door_%s.png" % label)
        img.resize((w*SCALE, h*SCALE), Image.NEAREST).save(out)
        print("g%d  %dx%d -> %s%s" % (N, w, h, os.path.basename(out), tag))
        n += 1
    print("decodificate:", n)

if __name__ == "__main__":
    main()
