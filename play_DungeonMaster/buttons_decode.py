# Decodifica i dispositivi murali (bottoni/interruttori/serrature) dalle grafiche
# 121+2*T (far/piccola) e 121+2*T+1 (near/grande), T = tipo decorazione.
import os
from PIL import Image
NAME={5:"keyhole_plain",7:"switch_small",14:"switch_tiny",15:"button_green",
 16:"button_blue",17:"coin_slot",18:"keyhole_double",19:"keyhole_square",
 20:"keyhole_winged",21:"keyhole_block",22:"keyhole_stone",23:"keyhole_cross",
 24:"keyhole_gold_turquoise",25:"keyhole_skeleton",26:"keyhole_gold",
 27:"keyhole_turquoise",28:"keyhole_emerald",29:"keyhole_ruby",30:"keyhole_ra",
 31:"keyhole_master",32:"gem_hole",
 # leve e pulsanti/interruttori "grossi" (i veri comandi apri/chiudi porta)
 44:"lever_up",45:"lever_down",47:"button_red",48:"switch_eye",
 49:"switch_big_out",50:"switch_crack_out",54:"switch_big_in"}
def load_pal(p):
    pal=[]
    for line in open(p):
        s=line.split()
        if len(s)>=3: pal.append(tuple(int(v) for v in s[:3]))
    while len(pal)<16: pal.append((255,0,255))
    return pal
def decode(raw,w,h,stride,pal):
    img=Image.new("RGBA",(w,h)); px=img.load()
    for y in range(h):
        off=y*stride
        words=[(raw[off+2*k]<<8)|raw[off+2*k+1] for k in range(stride//2)]
        for g in range((w+15)//16):
            if g*4+3>=len(words): break
            a,b2,c,d=words[g*4:g*4+4]
            for b in range(16):
                x=g*16+b
                if x>=w: break
                bit=15-b
                idx=((a>>bit)&1)|(((b2>>bit)&1)<<1)|(((c>>bit)&1)<<2)|(((d>>bit)&1)<<3)
                r,gg,bb=pal[idx]; px[x,y]=(r,gg,bb,0 if idx==0 else 255)
    return img
pal=load_pal("buttons/palette.txt")
dims={}
for line in open("buttons/buttons.txt"):
    p=line.split()
    if len(p)>=4: dims[int(p[0])]=tuple(int(x) for x in p[1:4])  # w,h,stride
done=0
for T,nm in sorted(NAME.items()):
    for sub,tag in ((0,"far"),(1,"near")):
        N=121+2*T+sub
        if N not in dims: continue
        w,h,stride=dims[N]
        raw=open("buttons/g%d.raw"%N,"rb").read()
        img=decode(raw,w,h,stride,pal)
        out="buttons/dev_%s_%s.png"%(nm,tag)
        img.resize((w*6,h*6),Image.NEAREST).save(out)
        done+=1
print("dispositivi decodificati:",done)

# Restanti decorazioni NON-dispositivo (per non lasciare raw senza png).
DECOR={0:"unknown0",1:"alcove_square",2:"altar_vi",3:"alcove_ornate",6:"wall_ring",
 8:"dent",11:"wall_crack",12:"slime_outlet",35:"fountain_water",36:"manacles",
 37:"demon_face",38:"torch_holder",40:"poison_holes",41:"fireball_holes",
 42:"dagger_holes",43:"mirror"}
extra=0
for N,(w,h,stride) in sorted(dims.items()):
    T,sub=divmod(N-121,2)
    if T in NAME: continue                 # gia' fatto come dev_
    nm=DECOR.get(T,"type%d"%T)
    raw=open("buttons/g%d.raw"%N,"rb").read()
    img=decode(raw,w,h,stride,pal)
    out="buttons/decor_%s_%s.png"%(nm,"far" if sub==0 else "near")
    img.resize((w*6,h*6),Image.NEAREST).save(out)
    extra+=1
print("decorazioni non-dispositivo decodificate:",extra)
print("TOTALE png:",done+extra,"(raw:",len(dims),")")
