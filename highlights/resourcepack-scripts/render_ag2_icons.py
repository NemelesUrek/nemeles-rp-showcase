#!/usr/bin/env python3
# render_ag2_icons.py -- Renderiza las GEOMETRIAS 3D de AG2 (formato Bedrock) a ICONOS 2D PNG sombreados,
# para que el icono del inventario COINCIDA con el arma 3D que se ve en la mano. Python puro + Pillow.
# Filtra solo los huesos del ARMA (los que cuelgan de rightArm), aplica rotaciones de cubo+hueso (jerarquia),
# proyecta una vista 3/4 isometrica y samplea la textura HQ por cara. Se verifica leyendo el PNG resultante.
import json, math, os, sys
from PIL import Image, ImageDraw

AG2 = os.path.expanduser(r"~\Downloads\_ag2rp")
DEC = json.JSONDecoder()
def lz(p):
    r = open(p, encoding="utf-8", errors="replace").read().lstrip("﻿")
    i = r.find("{"); o,_ = DEC.raw_decode(r[i:]); return o

def find_geo(ident):
    import glob
    for f in glob.glob(os.path.join(AG2,"models","**","*.json"), recursive=True):
        try:
            for g in lz(f).get("minecraft:geometry",[]):
                if g["description"]["identifier"] == ident: return g
        except: pass
    return None

NONGUN = {"root","view_position","waist","body","head","leftArm","rightArm","leftLeg","rightLeg",
          "jaw","cape","leftSleeve","rightSleeve","leftPants","rightPants","jacket","hat"}

def rot_xyz(v, rx, ry, rz):
    x,y,z = v
    if rx: a=math.radians(rx); y,z = y*math.cos(a)-z*math.sin(a), y*math.sin(a)+z*math.cos(a)
    if ry: a=math.radians(ry); x,z = x*math.cos(a)+z*math.sin(a), -x*math.sin(a)+z*math.cos(a)
    if rz: a=math.radians(rz); x,y = x*math.cos(a)-y*math.sin(a), x*math.sin(a)+y*math.cos(a)
    return (x,y,z)

def rot_about(v, rot, piv):
    x,y,z = v[0]-piv[0], v[1]-piv[1], v[2]-piv[2]
    x,y,z = rot_xyz((x,y,z), rot[0],rot[1],rot[2])
    return (x+piv[0], y+piv[1], z+piv[2])

# caras de un cuboide (from=min, to=max): indices de esquina + normal
def corners(f,t):
    return [(f[0],f[1],f[2]),(t[0],f[1],f[2]),(t[0],t[1],f[2]),(f[0],t[1],f[2]),
            (f[0],f[1],t[2]),(t[0],f[1],t[2]),(t[0],t[1],t[2]),(f[0],t[1],t[2])]
FACEDEF = {"north":([0,1,2,3],(0,0,-1)),"south":([5,4,7,6],(0,0,1)),
           "west":([4,0,3,7],(-1,0,0)),"east":([1,5,6,2],(1,0,0)),
           "up":([3,2,6,7],(0,1,0)),"down":([4,5,1,0],(0,-1,0))}

def avg_color(tex, uv, uv_size):
    W,H = tex.size
    u,v = uv; w,h = uv_size
    x1=int(min(u,u+w)); x2=int(math.ceil(max(u,u+w)))
    y1=int(min(v,v+h)); y2=int(math.ceil(max(v,v+h)))
    x1=max(0,min(W-1,x1)); x2=max(x1+1,min(W,x2))
    y1=max(0,min(H-1,y1)); y2=max(y1+1,min(H,y2))
    px=tex.crop((x1,y1,x2,y2)).load(); cw,ch=x2-x1,y2-y1
    r=g=b=n=0
    for yy in range(ch):
        for xx in range(cw):
            pr,pg,pb,pa = px[xx,yy]
            if pa>40: r+=pr; g+=pg; b+=pb; n+=1
    if n==0: return None
    return (r//n,g//n,b//n)

def gun_bones(geo):
    bones = {b["name"]: b for b in geo["bones"]}
    def anc(name):
        out=[]; cur=bones.get(name,{}).get("parent")
        while cur: out.append(cur); cur=bones.get(cur,{}).get("parent")
        return out
    keep = [b for b in geo["bones"]
            if "rightArm" in anc(b["name"]) and b["name"] not in NONGUN]
    if not keep:  # fallback: todo lo que no sea cuerpo ni este bajo leftArm
        keep = [b for b in geo["bones"]
                if b["name"] not in NONGUN and "leftArm" not in anc(b["name"])]
    return keep, bones

def render(ident, texpng, ax, ay, az, size=128, out=None, flip=(1,1)):
    geo = find_geo(ident)
    if not geo: print("NO geo:",ident); return
    tex = Image.open(texpng).convert("RGBA")
    keep, bones = gun_bones(geo)
    # cadena de huesos (para rotaciones de bind pose)
    def chain(name):
        out=[name]; cur=bones.get(name,{}).get("parent")
        while cur: out.append(cur); cur=bones.get(cur,{}).get("parent")
        return out
    ll=math.sqrt(0.4**2+0.85**2+0.45**2); light=(-0.4/ll,0.85/ll,0.45/ll)
    faces=[]
    for b in keep:
        bchain = chain(b["name"])
        for cube in b.get("cubes",[]):
            o=cube["origin"]; s=cube["size"]
            f=o; t=[o[0]+s[0],o[1]+s[1],o[2]+s[2]]
            cs=corners(f,t)
            # 1) rotacion del cubo
            crot=cube.get("rotation"); cpiv=cube.get("pivot", [ (f[0]+t[0])/2,(f[1]+t[1])/2,(f[2]+t[2])/2 ])
            if crot: cs=[rot_about(c,crot,cpiv) for c in cs]
            # 2) rotaciones de huesos (jerarquia, del propio hueso hacia la raiz)
            for bn in bchain:
                bb=bones.get(bn,{})
                if bb.get("rotation"): cs=[rot_about(c,bb["rotation"],bb.get("pivot",[0,0,0])) for c in cs]
            for fname,(idx,normal) in FACEDEF.items():
                fc=cube.get("uv",{}).get(fname) if isinstance(cube.get("uv"),dict) else None
                if not fc: continue
                col=avg_color(tex, fc["uv"], fc["uv_size"])
                if col is None: continue
                # normal con rotaciones de cubo+huesos
                nrm=normal
                if crot: nrm=rot_about(nrm,crot,(0,0,0))
                for bn in bchain:
                    bb=bones.get(bn,{})
                    if bb.get("rotation"): nrm=rot_about(nrm,bb["rotation"],(0,0,0))
                faces.append(([cs[i] for i in idx], nrm, col))
    if not faces: print("sin caras:",ident); return
    # --- AUTO-ORIENTACION: detectar eje largo (canon/hoja) y ponerlo horizontal ---
    allc=[c for pts,_,_ in faces for c in pts]
    ext=[max(p[i] for p in allc)-min(p[i] for p in allc) for i in range(3)]
    order=sorted(range(3), key=lambda i:ext[i], reverse=True)  # [largo, medio, corto]
    L,M,S=order
    fx,fy=flip  # signos para corregir orientacion (arriba/derecha) por arma
    def remap(p): return (p[L]*fx, p[M]*fy, p[S])  # largo->X(horiz), medio->Y(vert), corto->profundidad
    cx=sum(p[0] for p in allc)/len(allc); cy=sum(p[1] for p in allc)/len(allc); cz=sum(p[2] for p in allc)/len(allc)
    out_faces=[]
    for pts,nrm,col in faces:
        rp=[rot_xyz(remap((p[0]-cx,p[1]-cy,p[2]-cz)), ax,ay,az) for p in pts]
        rn=rot_xyz(remap(nrm), ax,ay,az)
        if rn[2] <= 0.02: continue  # backface (camara mira +Z)
        sh=0.32+0.68*max(0.0, sum(rn[k]*light[k] for k in range(3)))
        color=tuple(min(255,int(c*sh)) for c in col)
        depth=sum(p[2] for p in rp)/len(rp)
        out_faces.append((depth, rp, color))
    xs=[p[0] for _,pts,_ in out_faces for p in pts]; ys=[p[1] for _,pts,_ in out_faces for p in pts]
    minx,maxx,miny,maxy=min(xs),max(xs),min(ys),max(ys)
    span=max(maxx-minx,maxy-miny)*1.12
    mx=(minx+maxx)/2; my=(miny+maxy)/2
    SS=4; W=size*SS
    img=Image.new("RGBA",(W,W),(0,0,0,0)); dr=ImageDraw.Draw(img)
    def proj(p):
        return ((p[0]-mx)/span*W*0.94 + W/2, (-(p[1]-my))/span*W*0.94 + W/2)
    for depth,pts,color in sorted(out_faces, key=lambda f:f[0]):
        dr.polygon([proj(p) for p in pts], fill=color+(255,))
    img=img.resize((size,size), Image.LANCZOS)
    out=out or f"_icon_{ident.split('.')[-1]}.png"
    img.save(out)
    print(f"{ident}: {len(out_faces)} caras -> {out}")

# armas -> (geometry, textura HQ)
GUNS = {
    "deagle":   ("geometry.deagle",   AG2+r"\textures\entity\3d\hq\deagle.png"),
    "ak47":     ("geometry.ak47",     AG2+r"\textures\entity\3d\hq\ak47.png"),
    "mp5":      ("geometry.mp5",      AG2+r"\textures\entity\3d\hq\mp5.png"),
    "karambit": ("geometry.karambit", AG2+r"\textures\entity\3d\hq\karambit.png"),
    "flip_knife": ("geometry.flip_knife", AG2+r"\textures\entity\3d\hq\flip_knife.png"),
}

# 3/4 de vista (tilt) + correccion de orientacion (arriba/derecha) por arma
VIEW = {  # ax(pitch), ay(yaw), az, flip(x,y)
    "deagle":   (14, 26, 0, (1, 1)),
    "ak47":     (14, 26, 0, (1, 1)),
    "mp5":      (14, 26, 0, (1, 1)),
    "karambit": (0, 0, 0, (1, 1)),
    "flip_knife": (12, 22, 0, (1, 1)),
}
if __name__=="__main__":
    os.makedirs("_icon_preview", exist_ok=True)
    for nm,(ident,tex) in GUNS.items():
        ax,ay,az,flip = VIEW[nm]
        render(ident, tex, ax, ay, az, size=128, out=f"_icon_preview/{nm}.png", flip=flip)
