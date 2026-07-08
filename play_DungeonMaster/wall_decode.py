#!/usr/bin/env python3
# Genera la LIBRERIA LOCALE del 3D per le decorazioni murali random:
#   walldec/t<T>.png  (T = tipo decorazione DM, grafica near 121+2T+1,
#                      alpha: indice 10 trasparente (color-key DM, come le
#                      decorazioni pavimento; lo 0 e' NERO legittimo), x6 NEAREST)
# Input: buttons/g<N>.raw + buttons/buttons.txt + buttons/palette.txt
# (gia' estratti dal bridge col comando one-shot; questo script NON tocca
# i _custom.png, che vincono sempre nel viewer).
import os
from PIL import Image

def load_pal(p):
    pal = []
    for line in open(p):
        s = line.split()
        if len(s) >= 3: pal.append(tuple(int(v) for v in s[:3]))
    while len(pal) < 16: pal.append((255, 0, 255))
    return pal

def decode(raw, w, h, stride, pal):
    img = Image.new("RGBA", (w, h)); px = img.load()
    for y in range(h):
        off = y*stride
        words = [(raw[off+2*k] << 8) | raw[off+2*k+1] for k in range(stride//2)]
        for g in range((w+15)//16):
            if g*4+3 >= len(words): break
            a, b2, c, d = words[g*4:g*4+4]
            for b in range(16):
                x = g*16 + b
                if x >= w: break
                bit = 15 - b
                idx = ((a>>bit)&1) | (((b2>>bit)&1)<<1) | (((c>>bit)&1)<<2) | (((d>>bit)&1)<<3)
                r, gg, bb = pal[idx]
                px[x, y] = (r, gg, bb, 0 if idx == 10 else 255)
    return img

pal = load_pal("buttons/palette.txt")
dims = {}
for line in open("buttons/buttons.txt"):
    p = line.split()
    if len(p) >= 4: dims[int(p[0])] = tuple(int(x) for x in p[1:4])

os.makedirs("walldec", exist_ok=True)
done = 0
for T in range(0, 58):
    N = 121 + 2*T + 1                       # variante near
    if N not in dims: continue
    w, h, stride = dims[N]
    raw = open("buttons/g%d.raw" % N, "rb").read()
    img = decode(raw, w, h, stride, pal)
    img.resize((w*6, h*6), Image.NEAREST).save("walldec/t%d.png" % T)
    done += 1
print("decorazioni murali decodificate in walldec/:", done)
