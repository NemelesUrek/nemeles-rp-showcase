package com.nemeles.npcai.listener;

import com.nemeles.npcai.AffinityManager;
import com.nemeles.npcai.AiConfig;
import com.nemeles.npcai.ConversationManager;
import com.nemeles.npcai.HologramManager;
import com.nemeles.npcai.NpcPersona;
import com.nemeles.npcai.RelationInfo;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityMountEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.function.Supplier;

/** Clic derecho en un NPC -> conversacion / regalo de comida; agachado+clic -> ver tu relacion (holograma+chat). */
public final class NpcInteractListener implements Listener {

    private final ConversationManager mgr;
    private final Supplier<AiConfig> cfg;
    private final Supplier<com.nemeles.npcai.InteractionManager> interactions;   // null-safe (sin Citizens no hay regalos)
    private final Supplier<AffinityManager> affinity;   // null-safe (sin BD no hay afinidad)
    private final HologramManager holo;                 // cartel de "tu relacion"

    public NpcInteractListener(ConversationManager mgr, Supplier<AiConfig> cfg,
                               Supplier<com.nemeles.npcai.InteractionManager> interactions,
                               Supplier<AffinityManager> affinity, HologramManager holo) {
        this.mgr = mgr;
        this.cfg = cfg;
        this.interactions = interactions;
        this.affinity = affinity;
        this.holo = holo;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Entity ent = e.getRightClicked();
        NpcPersona p = cfg.get().match(ent.getName() == null ? null : org.bukkit.ChatColor.stripColor(ent.getName()));
        if (p == null) return;
        e.setCancelled(true);                 // corta comerciar / equipar / MONTAR
        Player player = e.getPlayer();
        if (!player.hasPermission("nemeles.npcai.use")) return;

        // AGACHADO + clic-derecho -> NO charlamos: mostramos "tu relacion" con este NPC.
        if (player.isSneaking()) {
            try {
                AiConfig conf = cfg.get();
                AffinityManager aff = affinity == null ? null : affinity.get();
                if (holo != null) {
                    String text = RelationInfo.holoText(conf, aff, p, player);
                    int secs = conf.holoSeconds > 0 ? conf.holoSeconds : 8;
                    holo.show(ent, text, secs);
                }
                for (String line : RelationInfo.chatLines(conf, aff, p, player)) {
                    player.sendMessage(org.bukkit.ChatColor.translateAlternateColorCodes('&', line));
                }
            } catch (Throwable ignored) { }
            return;
        }

        // ¿Lleva COMIDA en la mano? -> REGALO para el NPC.
        com.nemeles.npcai.InteractionManager im = interactions == null ? null : interactions.get();
        if (im != null) {
            try { if (im.gift(player, ent, p, player.getInventory().getItemInMainHand())) return; }
            catch (Throwable ignored) { }
        }
        if (mgr.isActive(player.getUniqueId())) mgr.end(player);
        mgr.start(player, p, ent.getUniqueId());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteractAt(PlayerInteractAtEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        String rn = e.getRightClicked().getName();
        if (cfg.get().match(rn == null ? null : org.bukkit.ChatColor.stripColor(rn)) != null) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onMount(EntityMountEvent e) {
        Entity m = e.getMount();
        if (m.hasMetadata("NPC") || cfg.get().match(m.getName()) != null) e.setCancelled(true);
    }
}
