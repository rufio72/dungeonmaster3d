#!/usr/bin/env python3
"""Genera le texture degli OGGETTI A TERRA per il viewer 3D.

Affetta gli atlanti icone gia' estratti dal bridge (invgfx/g42..g48.png,
celle 16x16 a scala 2.75 = 44px, 16 colonne x 2 righe) in una PNG per
objectNameIndex: floorobj/ni<NI>.png. Le basi NI di ogni atlante sono le
word612 (multipli di 32, vedi invgfx/layout.txt).

Il viewer li usa come decal piatti sul pavimento (pattern dell'erba in
decorgfx/); un file floorobj/ni<NI>_custom.png, se presente, ha la
precedenza e non viene mai sovrascritto da questo script.
"""
import os
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "invgfx")
DST = os.path.join(HERE, "floorobj")

# basi word612 (da invgfx/layout.txt: "word612 0 32 64 96 128 160 192")
BASES = [0, 32, 64, 96, 128, 160, 192]

os.makedirs(DST, exist_ok=True)
made = skipped = 0
for a, base in enumerate(BASES):
    path = os.path.join(SRC, "g%d.png" % (42 + a))
    im = Image.open(path).convert("RGBA")
    cw = im.width // 16                    # lato cella (44 a scala 2.75)
    rows = im.height // cw
    for cl in range(16 * rows):
        ni = base + cl
        x, y = (cl % 16) * cw, (cl // 16) * cw
        cell = im.crop((x, y, x + cw, y + cw))
        if cell.getchannel("A").getextrema()[1] == 0:
            skipped += 1                   # cella vuota: nessun file
            continue
        # color-key: nell'atlante le icone stanno su un fondo pieno (il grigio
        # del pannello inventario); sul pavimento va bucato. Il colore di fondo
        # e' quello dell'angolo in alto a sinistra della cella.
        px = cell.load()
        bg = px[0, 0][:3]
        for cy in range(cw):
            for cx in range(cw):
                if px[cx, cy][:3] == bg:
                    px[cx, cy] = (0, 0, 0, 0)
        cell.save(os.path.join(DST, "ni%d.png" % ni))
        made += 1
print("floorobj: %d texture generate, %d celle vuote saltate" % (made, skipped))
