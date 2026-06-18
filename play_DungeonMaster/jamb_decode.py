# Decodifica gli stipiti porta (buffer pDoorBitmaps[0..7], planare ST 4-bitplane).
# Larghezze di storage determinate empiricamente (multipli di 16); altezza = size/stride.
import os
from PIL import Image
def load_pal(p):
    pal=[]
    for line in open(p):
        s=line.split()
        if len(s)>=3: pal.append(tuple(int(v) for v in s[:3]))
    while len(pal)<16: pal.append((255,0,255))
    return pal
def decode(raw,pw,h,pal):
    stride=pw//2
    img=Image.new("RGBA",(pw,h)); px=img.load()
    for y in range(h):
        off=y*stride
        words=[(raw[off+2*k]<<8)|raw[off+2*k+1] for k in range(stride//2)]
        for g in range(pw//16):
            if g*4+3>=len(words): break
            a,b2,c,d=words[g*4:g*4+4]
            for b in range(16):
                x=g*16+b; bit=15-b
                idx=((a>>bit)&1)|(((b2>>bit)&1)<<1)|(((c>>bit)&1)<<2)|(((d>>bit)&1)<<3)
                r,gg,bb=pal[idx]; px[x,y]=(r,gg,bb,0 if idx==0 else 255)
    return img
pal=load_pal("jambs/palette.txt")
# buffer -> (ruolo pDoorBitmaps, larghezza storage)
specs=[("p0","frameRight_F1",32),("p1","edgeFrame_F0",32),("p2","frameLeft_F1",32),
       ("p3","frameLeft_F2",48),("p4","frameLeft_F3",32),("p5","frameLeft_F3L1",32),
       ("p6","topTrack_F1",16),("p7","topTrack_F2",16)]
for name,role,pw in specs:
    raw=open("jambs/%s.raw"%name,"rb").read()
    h=len(raw)//(pw//2)
    img=decode(raw,pw,h,pal)
    out="jambs/jamb_%s.png"%role
    img.resize((pw*6,h*6),Image.NEAREST).save(out)
    print("%s -> %s (%dx%d)"%(name,os.path.basename(out),pw,h))
