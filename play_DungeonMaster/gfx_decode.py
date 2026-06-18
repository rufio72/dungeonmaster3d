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

def decode(raw, w, h, stride, pal):
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
                px[x, y] = (r, gg, bb, 0 if idx == 0 else 255)
    return img

def main():
    base = sys.argv[1] if len(sys.argv) > 1 else "."
    scale = int(sys.argv[2]) if len(sys.argv) > 2 else 4
    pal = load_palette(os.path.join(base, "palette.txt"))
    man = glob.glob(os.path.join(base, "*.txt"))
    man = [m for m in man if os.path.basename(m) not in ("palette.txt", "anchor.txt")]
    if not man:
        print("nessun manifest in", base); return
    n = 0
    for line in open(man[0]):
        p = line.split()
        if len(p) < 4:
            continue
        N, w, h, stride = (int(x) for x in p[:4])
        raw = open(os.path.join(base, "g%d.raw" % N), "rb").read()
        img = decode(raw, w, h, stride, pal)
        out = os.path.join(base, "g%d.png" % N)
        img.resize((w*scale, h*scale), Image.NEAREST).save(out)
        print("g%d %dx%d -> %s" % (N, w, h, os.path.basename(out)))
        n += 1
    print("decodificate:", n, "in", base)

if __name__ == "__main__":
    main()
