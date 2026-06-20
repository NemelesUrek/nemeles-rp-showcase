#!/usr/bin/env python3
# build_vehicles.py -- Reskin de la entidad BOAT vanilla a VEHICULOS 3D distintos en Bedrock (via Geyser).
# Geyser pasa cada *_BOAT de Java como minecraft:boat de Bedrock con el dato VARIANT = ordinal de la madera
# (OAK=0,SPRUCE=1,BIRCH=2,JUNGLE=3,ACACIA=4,DARK_OAK=5,MANGROVE=6,BAMBOO=7,CHERRY=8,PALE_OAK=9).
# Cada VehicleType de NemelesVehicles spawnea una madera concreta (config.yml), asi que la madera = el slot
# de modelo. Sobreescribimos entity/boat.entity.json + render controller que elige geo/textura por q.variant.
# CERO cambios en Java.
#
# Mapeo (config.yml de NemelesVehicles -> madera -> modelo):
#   OAK(0)      sedan/taxi   -> sedan
#   SPRUCE(1)   furgoneta/camion -> van (furgon)
#   BIRCH(2)    bici         -> bike
#   JUNGLE(3)   quad         -> quad
#   ACACIA(4)   moto         -> moto
#   DARK_OAK(5) robado/clasico -> classic
#   MANGROVE(6) lancha       -> BOTE VANILLA (flota; un bote ES lo correcto)
#   BAMBOO(7)   (sin tipo)   -> bote vanilla
#   CHERRY(8)   deportivo    -> sports
#   PALE_OAK(9) patrulla     -> police (sedan + barra de luces)
#
# Modelos hechos a mano (voxel, estilo Minecraft). Cada cara muestrea un pixel de color de una paleta 16x16,
# asi una sola textura sirve para todos. Coches ~2 bloques de largo, ruedas apoyadas en y=0, morro hacia +Z.
import json, os
from PIL import Image

B = "NemelesRP_Bedrock"
WOODS = ["oak","spruce","birch","jungle","acacia","dark_oak","mangrove","bamboo","cherry","pale_oak"]

# --- paleta de color (columna,fila) en una textura 16x16 ---
PAL = {
    "body":  (0, 0),   # azul (sedan)
    "tire":  (1, 0),   # negro (ruedas)
    "glass": (2, 0),   # azul claro (cristales)
    "chrome":(3, 0),   # gris claro (cromados/parachoques)
    "yellow":(4, 0),   # amarillo (taxi/intermitentes)
    "white": (5, 0),   # blanco (patrulla/furgon)
    "navy":  (6, 0),   # azul marino (patrulla)
    "red":   (7, 0),   # rojo (deportivo/clasico)
    "seat":  (8, 0),   # marron (asiento/cuero)
    "rlight":(9, 0),   # rojo luz (pilotos/barra)
    "head":  (10, 0),  # faro
    "frame": (11, 0),  # gris oscuro (chasis/motor/manillar)
    "steel": (12, 0),  # acero (depositos/llantas)
    "blight":(13, 0),  # azul luz (barra policia)
}
PAL_RGB = {
    "body": (40,90,160,255), "tire": (22,22,24,255), "glass": (130,200,235,255),
    "chrome": (195,198,205,255), "yellow": (240,200,30,255), "white": (236,236,238,255),
    "navy": (28,38,110,255), "red": (160,32,32,255), "seat": (95,62,38,255),
    "rlight": (220,40,40,255), "head": (255,238,150,255), "frame": (58,58,64,255),
    "steel": (140,145,152,255), "blight": (50,120,235,255),
}

def face(color):
    cx, cy = PAL[color]
    return {d: {"uv": [cx + 0.25, cy + 0.25], "uv_size": [0.5, 0.5]} for d in ("north","south","east","west","up","down")}

def cube(origin, size, color, rot=None, pivot=None):
    c = {"origin": origin, "size": size, "uv": face(color)}
    if rot is not None:
        c["rotation"] = rot
        c["pivot"] = pivot if pivot else [origin[0]+size[0]/2, origin[1]+size[1]/2, origin[2]+size[2]/2]
    return c

def wheels4(half_w, z_front, z_rear, r=6, w=3, y=0):
    # 4 ruedas en las esquinas; half_w = mitad del ancho del coche; r=alto, w=grosor lateral
    xs = [-half_w - w + 0.5, half_w - 0.5]
    out = []
    for x in xs:
        for z in (z_front, z_rear):
            out.append(cube([x, y, z], [w, r, r], "tire"))
    return out

# ---------------- MODELOS ----------------
def m_sedan(body="body"):
    return [
        cube([-7, 4, -16], [14, 4, 32], body),                 # chasis
        cube([-6.5, 4.5, -16.4], [13, 2.5, 0.6], "chrome"),    # parachoques delantero
        cube([-6.5, 4.5, 15.8], [13, 2.5, 0.6], "chrome"),     # parachoques trasero
        cube([-6, 8, -7], [12, 5, 15], body),                  # cabina
        cube([-5.4, 8.5, -7.5], [10.8, 3.6, 0.6], "glass"),    # parabrisas
        cube([-5.4, 8.5, 7.4], [10.8, 3.6, 0.6], "glass"),     # luneta
        cube([-6.3, 8.5, -6.5], [0.5, 3.6, 13], "glass"),      # ventanilla izq
        cube([5.8, 8.5, -6.5], [0.5, 3.6, 13], "glass"),       # ventanilla der
        cube([-6.6, 5, -16.2], [1.4, 1.6, 0.6], "head"),       # faro izq
        cube([5.2, 5, -16.2], [1.4, 1.6, 0.6], "head"),        # faro der
        cube([-6.6, 5, 15.9], [1.4, 1.4, 0.5], "rlight"),      # piloto izq
        cube([5.2, 5, 15.9], [1.4, 1.4, 0.5], "rlight"),       # piloto der
    ] + wheels4(7, -11, 7)

def m_police():
    g = m_sedan(body="white")
    g += [
        cube([-7, 4, -3], [14, 4.2, 8], "navy"),               # franja lateral oscura
        cube([-4.5, 13, -1.5], [9, 1.6, 4], "chrome"),         # base barra de luces
        cube([-4.5, 14.4, -1.5], [4.4, 1.6, 4], "blight"),     # luz azul
        cube([0.1, 14.4, -1.5], [4.4, 1.6, 4], "rlight"),      # luz roja
    ]
    return g

def m_sports():
    return [
        cube([-7, 3, -17], [14, 3.5, 34], "red"),              # chasis bajo y largo
        cube([-6.5, 3.2, -17.3], [13, 2, 0.5], "chrome"),      # splitter delantero
        cube([-5.5, 6.5, -3], [11, 3.5, 11], "red"),           # cabina baja
        cube([-5, 6.8, -3.4], [10, 2.8, 0.5], "glass"),        # parabrisas inclinado
        cube([-5, 6.8, 7], [10, 2.6, 0.5], "glass"),           # luneta
        cube([-5.8, 6.8, -2.5], [0.5, 2.8, 9], "glass"),
        cube([5.3, 6.8, -2.5], [0.5, 2.8, 9], "glass"),
        cube([-6.2, 4, -17.1], [1.6, 1.2, 0.5], "head"),
        cube([4.6, 4, -17.1], [1.6, 1.2, 0.5], "head"),
        # aleron trasero
        cube([-6, 8, 14], [1.5, 5, 1.5], "frame"),
        cube([4.5, 8, 14], [1.5, 5, 1.5], "frame"),
        cube([-6.5, 12.5, 13], [13, 1, 3], "red"),
    ] + wheels4(7, -12, 9, r=6, w=3)

def m_classic():
    body = "red"
    return [
        cube([-7, 5, -16], [14, 4, 32], body),                 # chasis alto
        cube([-7.3, 4, -10], [1.2, 3, 7], "frame"),            # estribo izq
        cube([6.1, 4, -10], [1.2, 3, 7], "frame"),             # estribo der
        cube([-6, 9, -5], [12, 6, 12], body),                  # cabina alta y cuadrada
        cube([-5.4, 9.5, -5.5], [10.8, 4.5, 0.6], "glass"),
        cube([-5.4, 9.5, 6.4], [10.8, 4.5, 0.6], "glass"),
        cube([-6.3, 9.5, -4.5], [0.5, 4.5, 10.5], "glass"),
        cube([5.8, 9.5, -4.5], [0.5, 4.5, 10.5], "glass"),
        cube([-7, 6, -16.6], [14, 2.5, 0.8], "chrome"),        # parrilla cromada
        cube([-6.2, 7, -16.9], [1.8, 1.8, 0.6], "head"),       # faros redondos grandes
        cube([4.4, 7, -16.9], [1.8, 1.8, 0.6], "head"),
        cube([-3.2, 8.5, -17], [6.4, 1.2, 1.4], "chrome"),     # adorno capo
    ] + wheels4(7, -11, 8, r=7, w=3)

def m_van():
    return [
        cube([-7, 4, -16], [14, 5, 14], "white"),              # cabina (morro)
        cube([-6.4, 9, -15], [12.8, 5, 12], "white"),          # techo cabina
        cube([-6, 9.5, -16.3], [12, 4, 0.6], "glass"),         # parabrisas
        cube([-7, 4, -3], [14, 13, 19], "white"),              # caja de carga grande
        cube([-6.4, 9.5, -15.5], [0.5, 3.5, 5], "glass"),
        cube([5.9, 9.5, -15.5], [0.5, 3.5, 5], "glass"),
        cube([-6.6, 5, -16.3], [1.4, 1.6, 0.5], "head"),
        cube([5.2, 5, -16.3], [1.4, 1.6, 0.5], "head"),
        cube([-6.6, 5, 15.9], [1.4, 2, 0.5], "rlight"),
        cube([5.2, 5, 15.9], [1.4, 2, 0.5], "rlight"),
    ] + wheels4(7, -12, 9, r=6, w=3)

def m_bike():
    # bicicleta: 2 ruedas finas, cuadro, manillar, sillin
    return [
        cube([-0.8, 0, -10], [1.6, 12, 12], "tire"),           # rueda delantera (alta y fina)
        cube([-0.8, 0, 0], [1.6, 12, 12], "tire"),             # rueda trasera
        cube([-0.6, 5, -9], [1.2, 1.2, 17], "frame"),          # tubo superior
        cube([-0.6, 1, -3.5], [1.2, 6, 1.2], "frame", rot=[-25,0,0]),  # tija sillin
        cube([-0.6, 1, -8.5], [1.2, 5, 1.2], "frame", rot=[35,0,0]),   # horquilla
        cube([-2.5, 11, -9.5], [5, 1, 1], "frame"),            # manillar
        cube([-2, 11, -3], [4, 1.2, 3], "seat"),               # sillin
        cube([-1.5, 2, -4.5], [3, 2, 4], "frame"),             # pedalier
    ]

def m_moto():
    return [
        cube([-1.3, 0, -11], [2.6, 11, 11], "tire"),           # rueda delantera
        cube([-1.6, 0, 2], [3.2, 12, 12], "tire"),             # rueda trasera (mas gorda)
        cube([-2, 6, -9], [4, 4, 18], "frame"),                # cuerpo/motor
        cube([-2.2, 9, -3], [4.4, 3, 9], "red"),               # deposito + colin
        cube([-2.4, 8.5, 3.5], [4.8, 2, 6], "seat"),           # asiento
        cube([-3, 11, -10], [6, 1.2, 1.2], "frame"),           # manillar
        cube([-1, 9.5, -11], [2, 2, 1.2], "head"),             # faro
        cube([-1.5, 4, -10], [3, 4, 1.2], "frame", rot=[30,0,0]),  # horquilla
    ]

def m_quad():
    return [
        cube([-7, 4, -13], [14, 5, 26], "red"),                # carroceria baja y ancha
        cube([-5, 9, -3], [10, 3, 10], "seat"),                # asiento
        cube([-4, 11, -12], [8, 1.2, 1.2], "frame"),           # manillar
        cube([-3, 11.5, -12.5], [6, 2.5, 2], "frame"),         # consola
        cube([-6.5, 7, -13.5], [13, 2, 1], "head"),            # faros frontales
        # 4 ruedas gordas tipo todoterreno
        cube([-9.5, 0, -11], [3.5, 8, 8], "tire"),
        cube([6, 0, -11], [3.5, 8, 8], "tire"),
        cube([-9.5, 0, 4], [3.5, 8, 8], "tire"),
        cube([6, 0, 4], [3.5, 8, 8], "tire"),
    ]

MODELS = {
    "sedan":   ("geometry.nemeles_sedan",   m_sedan),
    "van":     ("geometry.nemeles_van",     m_van),
    "bike":    ("geometry.nemeles_bike",    m_bike),
    "quad":    ("geometry.nemeles_quad",    m_quad),
    "moto":    ("geometry.nemeles_moto",    m_moto),
    "classic": ("geometry.nemeles_classic", m_classic),
    "sports":  ("geometry.nemeles_sports",  m_sports),
    "police":  ("geometry.nemeles_police",  m_police),
}
# madera -> modelo (None = bote vanilla)
WOOD_MODEL = {
    "oak": "sedan", "spruce": "van", "birch": "bike", "jungle": "quad",
    "acacia": "moto", "dark_oak": "classic", "mangrove": None, "bamboo": None,
    "cherry": "sports", "pale_oak": "police",
}

def geo_json(identifier, cubes):
    return {"format_version": "1.16.0", "minecraft:geometry": [{
        "description": {"identifier": identifier,
                        "texture_width": 16, "texture_height": 16,
                        "visible_bounds_width": 5, "visible_bounds_height": 4,
                        "visible_bounds_offset": [0, 1, 0]},
        "bones": [{"name": "body", "pivot": [0, 0, 0], "cubes": cubes}]}]}

def build():
    for d in ["models/entity/vehicles", "textures/entity/vehicles", "entity", "render_controllers"]:
        os.makedirs(f"{B}/{d}", exist_ok=True)
    # 1) geometrias
    for key, (ident, fn) in MODELS.items():
        json.dump(geo_json(ident, fn()), open(f"{B}/models/entity/vehicles/{key}.geo.json", "w", encoding="utf-8"), indent=1)
    # 2) textura paleta 16x16
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    px = img.load()
    for name, (cx, cy) in PAL.items():
        px[cx, cy] = PAL_RGB[name]
    img.save(f"{B}/textures/entity/vehicles/palette.png")
    # 3) override del client-entity del boat
    geo = {}; tex = {}
    for w in WOODS:
        mk = WOOD_MODEL.get(w)
        if mk:
            geo[w] = MODELS[mk][0]; tex[w] = "textures/entity/vehicles/palette"
        else:  # bote vanilla (lancha/sin tipo)
            geo[w] = "geometry.boat"; tex[w] = "textures/entity/boat/" + w
    ent = {"format_version": "1.10.0", "minecraft:client_entity": {"description": {
        "identifier": "minecraft:boat",
        "materials": {"default": "entity_alphatest"},
        "textures": tex, "geometry": geo,
        "render_controllers": ["controller.render.nemeles_vehicle"]}}}
    json.dump(ent, open(f"{B}/entity/boat.entity.json", "w", encoding="utf-8"), indent=1)
    # 4) render controller: elige geo/textura por q.variant (ORDEN EXACTO del enum BoatVariant 0..9)
    rc = {"format_version": "1.10.0", "render_controllers": {"controller.render.nemeles_vehicle": {
        "arrays": {
            "geometries": {"Array.geo": ["Geometry." + w for w in WOODS]},
            "textures": {"Array.tex": ["Texture." + w for w in WOODS]}},
        "geometry": "Array.geo[q.variant]",
        "textures": ["Array.tex[q.variant]"],
        "materials": [{"*": "Material.default"}]}}}
    json.dump(rc, open(f"{B}/render_controllers/nemeles_vehicle.render_controllers.json", "w", encoding="utf-8"), indent=1)
    # limpiar el modelo de prueba antiguo si existe
    old = f"{B}/models/entity/car_sedan.geo.json"
    if os.path.exists(old): os.remove(old)
    oldtex = f"{B}/textures/entity/vehicles/car_sedan.png"
    if os.path.exists(oldtex): os.remove(oldtex)
    print("VEHICLES OK:", ", ".join(f"{w}->{WOOD_MODEL[w] or 'bote'}" for w in WOODS))

if __name__ == "__main__":
    build()
