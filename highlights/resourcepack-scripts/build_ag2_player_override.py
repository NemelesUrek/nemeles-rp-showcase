#!/usr/bin/env python3
# ─── CREDIT / ATTRIBUTION ───────────────────────────────────────────────
# The 3D weapon models & textures this script processes are from "Actual Guns 2 (AG2)"
# and "guns++", by their respective creators — full credit to them. This file is MY OWN
# tooling that adapts those assets for Java<->Bedrock crossplay parity; it does NOT
# redistribute the asset files themselves.
# ────────────────────────────────────────────────────────────────────────

# build_ag2_player_override.py -- Integra el sistema de armas 3D de AG2 (Actual Guns 2) en nuestro pack Bedrock
# vÃ­a el MÃ‰TODO VERIFICADO: override de player.entity.json (el arma es un hueso del rig del jugador -> cae
# perfecta en la mano, 1Âª y 3Âª persona, sin adivinar coordenadas). DetecciÃ³n de NUESTRO item por TAG.
#
# Usa la base COMPLETA de AG2 (cero referencias colgantes -> seguro para jugadores SIN arma). Edit quirÃºrgico:
# nuestro 'nemeles:rifle' (que Geyser ve como item con tag 'ak47') dispara variable.ak47.
#
# Fuente: ~/Downloads/_ag2rp (AG2 RP ya extraÃ­do). Destino: resourcepack/NemelesRP_Bedrock.
import json, os, shutil

AG2 = os.path.expanduser(r"~\Downloads\_ag2rp")
B = "NemelesRP_Bedrock"
# Armas de AG2 que activamos (cada una se dispara por su TAG, que aÃ±adimos al item via Geyser):
#   nemeles:rifle->ak47, nemeles:pistol->deagle, nemeles:smg->mp5, nemeles:knife->karambit
AG2_GUNS = ["ak47", "deagle", "mp5", "karambit"]

def copy_render_assets():
    # copia el subset de RENDER de entidades de AG2 (sin particles/sounds/blocks/ui/BP) -> sin colgantes en player.entity
    for sub in ["models/entity", "render_controllers", "animation_controllers", "animations", "textures/entity", "materials"]:
        src = os.path.join(AG2, sub.replace("/", os.sep))
        dst = os.path.join(B, sub.replace("/", os.sep))
        if os.path.isdir(src):
            shutil.copytree(src, dst, dirs_exist_ok=True)
            print("copiado", sub)

def edit_player_entity():
    pe = json.load(open(os.path.join(AG2, "entity", "player.entity.json"), encoding="utf-8"))
    d = pe["minecraft:client_entity"]["description"]
    # quitar particle_effects/sound_effects (referencian particulas/sonidos del BP que no copiamos -> evita colgantes)
    d.pop("particle_effects", None)
    d.pop("sound_effects", None)
    sc = d.get("scripts", {})
    def add_tag(line, g):
        # aÃ±ade '|| tag(g)' a CUALQUIER comparacion get_equipped_item_name(...)=='g' de esa linea
        tag = f"query.equipped_item_all_tags('slot.weapon.mainhand', '{g}')"
        for cmp in (f"query.get_equipped_item_name('main_hand') == '{g}'",
                    f"query.get_equipped_item_name(0) == '{g}'",
                    f"query.get_equipped_item_name(0)=='{g}'",
                    f"query.get_equipped_item_name('main_hand')=='{g}'"):
            if cmp in line:
                line = line.replace(cmp, f"({cmp} || {tag})")
        return line
    # 1) pre_animation: que variable.<arma> se encienda tambien por su TAG
    pa = sc.get("pre_animation", [])
    for i, line in enumerate(pa):
        for g in AG2_GUNS:
            if f"variable.{g} =" in line or f"variable.{g}=" in line:
                pa[i] = add_tag(pa[i], g)
    # 2) animate: idem si asigna variable.<arma>
    an = sc.get("animate", [])
    for entry in an:
        if isinstance(entry, dict):
            for k in list(entry.keys()):
                g = k.replace("variable.", "")
                if g in AG2_GUNS:
                    entry[k] = add_tag(entry[k], g)
    os.makedirs(os.path.join(B, "entity"), exist_ok=True)
    json.dump(pe, open(os.path.join(B, "entity", "player.entity.json"), "w", encoding="utf-8"), indent=1)
    # verificaciÃ³n rÃ¡pida del edit
    txt = json.dumps(pe)
    for g in AG2_GUNS:
        print(f"  tag {g}:", f"equipped_item_all_tags('slot.weapon.mainhand', '{g}')" in txt)
    print("particle_effects quitado:", "particle_effects" not in d, "| sound_effects quitado:", "sound_effects" not in d)

def remove_old_attachable():
    # quitar el attachable inferior del rifle (evita doble-render con el override del player)
    for f in ["attachables/nemeles_rifle.json", "models/entity/nemeles_rifle.geo.json",
              "render_controllers/ng_rifle.json", "animations/ng_rifle.animation.json"]:
        p = os.path.join(B, f.replace("/", os.sep))
        if os.path.exists(p):
            os.remove(p); print("removido", f)

if __name__ == "__main__":
    copy_render_assets()
    edit_player_entity()
    remove_old_attachable()
    # validar que player.entity es JSON vÃ¡lido
    json.load(open(os.path.join(B, "entity", "player.entity.json"), encoding="utf-8"))
    print("OK: override AG2 montado (rifle=AK47 por tag). Valida JSON player.entity.")
