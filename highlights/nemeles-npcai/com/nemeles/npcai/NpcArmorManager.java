package com.nemeles.npcai;

import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Cuando un NPC protagonista es ATACADO por primera vez, "se defiende": se equipa una armadura
 * completa (por defecto NETHERITE) encantada con Proteccion IV con aspecto ROTO/usado. Al ser los
 * NPC EntityType.PLAYER (LivingEntity real), esa armadura REDUCE el dano vanilla que reciben.
 * Via trait Equipment de Citizens (HELMET/CHESTPLATE/LEGGINGS/BOOTS): 100% crossplay. No-op sin Citizens.
 */
public final class NpcArmorManager {

    private final Plugin plugin;
    private final Supplier<AiConfig> cfg;

    // Indexado por el id ESTABLE del NPC de Citizens (no por el UUID de entidad, que cambia al respawnear).
    private final Map<Integer, Long> armored = new ConcurrentHashMap<>();

    public NpcArmorManager(Plugin plugin, Supplier<AiConfig> cfg) {
        this.plugin = plugin;
        this.cfg = cfg;
    }

    public void onAttacked(org.bukkit.entity.Entity victim, NpcPersona persona) {
        try {
            AiConfig c = cfg.get();
            if (c == null || !c.armorEnabled || victim == null || persona == null) return;
            if (!appliesTo(c, persona)) return;

            net.citizensnpcs.api.npc.NPC npc = net.citizensnpcs.api.CitizensAPI.getNPCRegistry().getNPC(victim);
            if (npc == null) return;
            if (armored.containsKey(npc.getId())) return;

            net.citizensnpcs.api.trait.trait.Equipment eq =
                    npc.getOrAddTrait(net.citizensnpcs.api.trait.trait.Equipment.class);

            setPiece(eq, net.citizensnpcs.api.trait.trait.Equipment.EquipmentSlot.HELMET,     c.armorHelmet,     c);
            setPiece(eq, net.citizensnpcs.api.trait.trait.Equipment.EquipmentSlot.CHESTPLATE, c.armorChestplate, c);
            setPiece(eq, net.citizensnpcs.api.trait.trait.Equipment.EquipmentSlot.LEGGINGS,   c.armorLeggings,   c);
            setPiece(eq, net.citizensnpcs.api.trait.trait.Equipment.EquipmentSlot.BOOTS,      c.armorBoots,      c);

            armored.put(npc.getId(), System.currentTimeMillis());
        } catch (Throwable ignored) { }
    }

    public void disarm(org.bukkit.entity.Entity victim) {
        try {
            if (victim == null) return;
            net.citizensnpcs.api.npc.NPC npc = net.citizensnpcs.api.CitizensAPI.getNPCRegistry().getNPC(victim);
            if (npc == null) return;
            armored.remove(npc.getId());
            net.citizensnpcs.api.trait.trait.Equipment eq =
                    npc.getOrAddTrait(net.citizensnpcs.api.trait.trait.Equipment.class);
            for (net.citizensnpcs.api.trait.trait.Equipment.EquipmentSlot slot : new net.citizensnpcs.api.trait.trait.Equipment.EquipmentSlot[]{
                    net.citizensnpcs.api.trait.trait.Equipment.EquipmentSlot.HELMET,
                    net.citizensnpcs.api.trait.trait.Equipment.EquipmentSlot.CHESTPLATE,
                    net.citizensnpcs.api.trait.trait.Equipment.EquipmentSlot.LEGGINGS,
                    net.citizensnpcs.api.trait.trait.Equipment.EquipmentSlot.BOOTS}) {
                eq.set(slot, null);
            }
        } catch (Throwable ignored) { }
    }

    public boolean isArmored(org.bukkit.entity.Entity victim) {
        try {
            if (victim == null) return false;
            net.citizensnpcs.api.npc.NPC npc = net.citizensnpcs.api.CitizensAPI.getNPCRegistry().getNPC(victim);
            return npc != null && armored.containsKey(npc.getId());
        } catch (Throwable ignored) { return false; }
    }

    private boolean appliesTo(AiConfig c, NpcPersona persona) {
        if (c.armorKeys.isEmpty()) return NpcLifeManager.PROTAGONISTS.contains(persona.key);
        return c.armorKeys.contains(persona.key);
    }

    private void setPiece(net.citizensnpcs.api.trait.trait.Equipment eq,
                          net.citizensnpcs.api.trait.trait.Equipment.EquipmentSlot slot,
                          String materialName, AiConfig c) {
        try {
            Material m = Material.matchMaterial(materialName.toUpperCase(Locale.ROOT));
            if (m == null) return;
            ItemStack piece = new ItemStack(m);
            ItemMeta meta = piece.getItemMeta();
            if (meta != null) {
                try { meta.addEnchant(Enchantment.PROTECTION, c.armorProtection, true); } catch (Throwable ignored) { }
                if (c.armorUnbreaking > 0) {
                    try { meta.addEnchant(Enchantment.UNBREAKING, c.armorUnbreaking, true); } catch (Throwable ignored) { }
                }
                if (c.armorWornPercent > 0 && meta instanceof Damageable dmg) {
                    short max = m.getMaxDurability();
                    if (max > 1) {
                        int worn = (int) Math.floor(max * Math.min(0.95, c.armorWornPercent / 100.0));
                        dmg.setDamage(Math.max(0, Math.min(max - 1, worn)));
                    }
                }
                try { meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS, org.bukkit.inventory.ItemFlag.HIDE_ATTRIBUTES); } catch (Throwable ignored) { }
                piece.setItemMeta(meta);
            }
            eq.set(slot, piece);
        } catch (Throwable ignored) { }
    }

    public void clearMemory() { armored.clear(); }
}
