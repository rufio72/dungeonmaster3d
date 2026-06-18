#!/usr/bin/env python3
# Genera le texture PROCEDURALI usate dal viewer 3D (dm3d.bb):
#   dm_wood.png  - venatura legno per l'anta di legno (BuildWoodDoor)
#   dm_iron.png  - ferro scuro maculato + ruggine per la grata (BuildIronDoor)
#
# Sono nostre (NON arte del gioco) ma escluse da .gitignore come binari: questo
# script le ricrea identiche (seed fisso).  Uso:  python3 gen_textures.py
import math, random
from PIL import Image

W = H = 256

def gen_wood(path="dm_wood.png"):
    random.seed(7)
    img = Image.new("RGB", (W, H)); px = img.load()
    for x in range(W):
        for y in range(H):
            v = math.sin(x*0.16 + 1.5*math.sin(y*0.025)) * 0.5 + 0.5
            knot = 0.25*math.sin(x*0.05)*math.sin(y*0.04)
            t = max(0.0, min(1.0, v + knot))
            n = random.randint(-10, 10)
            r = int(95 + 70*t) + n
            g = int(55 + 40*t) + n
            b = int(28 + 22*t) + n
            if (x % (W//4)) < 2: r -= 35; g -= 25; b -= 15   # fughe tra assi
            px[x, y] = (max(0,min(255,r)), max(0,min(255,g)), max(0,min(255,b)))
    img.save(path); print("scritto", path)

def gen_iron(path="dm_iron.png"):
    random.seed(31)
    img = Image.new("RGB", (W, H)); px = img.load()
    # base ferro scuro maculato (niente striature direzionali -> niente "alluminio")
    for x in range(W):
        for y in range(H):
            base = 74 + random.randint(-16, 16)
            px[x, y] = (max(0,min(255,base)), max(0,min(255,base+3)), max(0,min(255,base+9)))
    # macchie morbide (nuvolosita')
    for _ in range(120):
        cx = random.randint(0,W-1); cy = random.randint(0,H-1); rad = random.randint(4,16)
        d = random.randint(-22, 16)
        for dx in range(-rad, rad+1):
            for dy in range(-rad, rad+1):
                dd = dx*dx + dy*dy
                if dd <= rad*rad:
                    f = 1.0 - (dd**0.5)/rad
                    xx = (cx+dx) % W; yy = (cy+dy) % H; pr,pg,pb = px[xx,yy]
                    px[xx,yy] = (max(0,min(255,int(pr+d*f))), max(0,min(255,int(pg+d*f))), max(0,min(255,int(pb+d*f))))
    # pitting (corrosione)
    for _ in range(700):
        xx = random.randint(0,W-1); yy = random.randint(0,H-1); pr,pg,pb = px[xx,yy]
        px[xx,yy] = (max(0,pr-30), max(0,pg-30), max(0,pb-28))
    # ruggine
    for _ in range(50):
        cx = random.randint(0,W-1); cy = random.randint(0,H-1); rad = random.randint(3,10)
        for dx in range(-rad, rad+1):
            for dy in range(-rad, rad+1):
                if dx*dx + dy*dy <= rad*rad:
                    xx = (cx+dx) % W; yy = (cy+dy) % H; pr,pg,pb = px[xx,yy]
                    px[xx,yy] = (min(255,pr+50), max(0,pg-8), max(0,pb-22))
    img.save(path); print("scritto", path)

if __name__ == "__main__":
    gen_wood()
    gen_iron()
