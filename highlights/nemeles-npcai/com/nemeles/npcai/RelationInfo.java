package com.nemeles.npcai;

import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Texto de "tu relacion con un NPC": afinidad + descriptor + umbrales. Lo usan el comando y el holograma. */
public final class RelationInfo {

    private RelationInfo() {}

    public static String label(AiConfig cfg, int aff) {
        if (aff <= cfg.hostHostileThreshold) return "&4HOSTIL";
        if (aff <= cfg.hostWaryThreshold)    return "&cRECELOSO";
        if (aff < 0)                          return "&6FRIO";
        if (aff >= 40)                        return "&aLEAL";
        if (aff >= cfg.followMinAffinity)     return "&aAMIGABLE";
        if (aff > 0)                          return "&eCORDIAL";
        return "&7NEUTRAL";
    }

    public static String bar(int aff) {
        int slots = 20;
        int filled = Math.round((aff + 100) / 200f * slots);
        if (filled < 0) filled = 0;
        if (filled > slots) filled = slots;
        String color = aff < 0 ? "&c" : (aff > 0 ? "&a" : "&7");
        StringBuilder b = new StringBuilder(color);
        for (int i = 0; i < slots; i++) b.append(i < filled ? '|' : ':');
        return b.toString();
    }

    public static List<String> chatLines(AiConfig cfg, AffinityManager aff, NpcPersona persona, Player viewer) {
        List<String> out = new ArrayList<>();
        String name = persona.color + persona.name;
        if (aff == null) {
            out.add("&7Tu relacion con " + name + "&7: &fno disponible &8(sistema de afinidad apagado).");
            return out;
        }
        UUID uid = viewer.getUniqueId();
        int v;
        try { v = aff.get(persona.key, uid); } catch (Throwable t) { v = 0; }
        String desc;
        try { desc = aff.descriptor(persona.key, uid); } catch (Throwable t) { desc = ""; }

        out.add("&8&m                            ");
        out.add("&fTu relacion con " + name + "&7:");
        out.add("  &7Afinidad: &f" + v + " &8/ 100   " + label(cfg, v));
        out.add("  " + bar(v));
        if (desc != null && !desc.isBlank()) out.add("  &7\"" + desc + "\"");
        out.add("&7Umbrales con &f" + persona.name + "&7:");
        out.add("  &a- Te sigue (\"sigueme\") si tu afinidad es &f" + cfg.followMinAffinity + "&a+.");
        out.add("  &a- Te da mejores trabajos a partir de &f15&a (y aun mejores a &f40&a).");
        out.add("  &c- Desconfia de ti por debajo de &f" + cfg.hostWaryThreshold + "&c (te avisa de que te alejes).");
        out.add("  &4- Te ATACA si baja a &f" + cfg.hostHostileThreshold + "&4 o menos.");
        out.add("&7Sube hablando y con regalos de comida (&a+&f" + cfg.giftAffinity
                + "&7); baja al insultar o golpear (&c-&f" + cfg.hostAttackDrop + "&7 por golpe).");
        out.add("&8&m                            ");
        return out;
    }

    public static String holoText(AiConfig cfg, AffinityManager aff, NpcPersona persona, Player viewer) {
        if (aff == null) return persona.color + persona.name + "\n&7(afinidad apagada)";
        int v;
        try { v = aff.get(persona.key, viewer.getUniqueId()); } catch (Throwable t) { v = 0; }
        StringBuilder sb = new StringBuilder();
        sb.append(persona.color).append(persona.name).append("\n");
        sb.append("&7Afinidad: &f").append(v).append("&8/100  ").append(label(cfg, v)).append("\n");
        sb.append(bar(v)).append("\n");
        if (v < cfg.followMinAffinity)        sb.append("&7Necesita &f").append(cfg.followMinAffinity).append("&7 para seguirte");
        else if (v <= cfg.hostWaryThreshold)  sb.append("&cDesconfia de ti");
        else                                  sb.append("&aTe seguiria si se lo pides");
        return sb.toString();
    }
}
