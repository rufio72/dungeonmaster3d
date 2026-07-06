#!/usr/bin/env python3
# ===========================================================================
# Rigenera gli asset del DRAG-AND-DROP inventario nel viewer 3D (dm3d.bb),
# a partire dalle grafiche gia' estratte in invgfx/ (prodotte dal bridge +
# gfx_decode.py). Arte DERIVATA dai dati di gioco: NON e' committata, si
# rigenera con questo script — come il resto di invgfx/.
#
# Produce:
#   invgfx/g17_prov.png     pannello inventario per i campioni PROVVISORI
#                           (non ancora nel gruppo): come g17 ma coi 3 tasti
#                           salva/dormi/chiudi in alto a destra CANCELLATI
#                           (riempiti col grigio pannello). Il 2D fa lo stesso
#                           (CSBCode.cpp:7494, FillRectangle se Potential...).
#   invgfx/icons/icon_<a>_<cl>.png   icona del singolo oggetto col fondo grigio
#                           reso TRASPARENTE, per disegnarla al cursore quando
#                           si trascina (DrawImage rispetta l'alpha; l'atlante
#                           gNf usato dal pannello ha fondo grigio opaco e
#                           DrawImageRect e' una copia raw). a = indice atlante
#                           0..6 (file g42..g48), cl = cella 0..31.
#
# Uso:  python3 gen_inv_dragdrop.py
# ===========================================================================
import os
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
INV  = os.path.join(HERE, "invgfx")

SC     = 2.75          # scala dei PNG decodificati (INVSC in dm3d.bb)
CW     = int(16 * SC)  # cella icona = 44 px
GREY   = (72, 72, 72)  # colore 12 = sfondo del pannello DM


def gen_g17_prov():
    src = os.path.join(INV, "g17.png")
    if not os.path.exists(src):
        print("  [g17_prov] manca g17.png, salto"); return
    im = Image.open(src).convert("RGBA")
    px = im.load()
    # regione dei 3 tasti in alto a destra (coord. gioco): x 168..223, y 0..14.
    # Solo la parte destra: cosi' non si toccano occhio/bocca (x < 70).
    x1 = int(168 * SC); x2 = min(im.width, int(223 * SC))
    y1 = 0;             y2 = int(14.5 * SC)
    for y in range(y1, y2):
        for x in range(x1, x2):
            px[x, y] = (GREY[0], GREY[1], GREY[2], 255)
    im.save(os.path.join(INV, "g17_prov.png"))
    print("  [g17_prov] ok")


def gen_icons():
    out = os.path.join(INV, "icons")
    os.makedirs(out, exist_ok=True)
    for fn in os.listdir(out):
        os.remove(os.path.join(out, fn))
    made = 0
    for a in range(7):                       # atlanti g42..g48
        src = os.path.join(INV, "g%d.png" % (42 + a))   # NON flippato = upright
        if not os.path.exists(src):
            continue
        atl = Image.open(src).convert("RGBA")
        W, H = atl.size
        for cl in range(32):                 # 16 colonne x 2 righe
            col = cl % 16; row = cl // 16
            sx = col * CW; sy = row * CW
            if sx + CW > W or sy + CW > H:
                continue
            # cella (col,row) SENZA flip: gNf=flipud(gN) e DrawImageRect
            # ri-flippa, i due flip si annullano -> oggetto = gN cella (col,row).
            cell = atl.crop((sx, sy, sx + CW, sy + CW))
            px = cell.load(); solid = 0
            for y in range(CW):
                for x in range(CW):
                    r, g, b, al = px[x, y]
                    if (r, g, b) == GREY:
                        px[x, y] = (r, g, b, 0)      # grigio -> trasparente
                    elif al > 0:
                        solid += 1
            if solid < 4:                    # cella vuota: non la salvo
                continue
            cell.save(os.path.join(out, "icon_%d_%d.png" % (a, cl)))
            made += 1
    print("  [icons] %d icone" % made)


if __name__ == "__main__":
    print("Rigenero asset drag-and-drop in %s:" % INV)
    gen_g17_prov()
    gen_icons()
    print("Fatto.")
