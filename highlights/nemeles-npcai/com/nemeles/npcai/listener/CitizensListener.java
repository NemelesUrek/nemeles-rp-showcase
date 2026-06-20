package com.nemeles.npcai.listener;

import com.nemeles.npcai.AiConfig;
import com.nemeles.npcai.ConversationManager;
import com.nemeles.npcai.NpcPersona;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.event.NPCRightClickEvent;
import net.citizensnpcs.api.event.NPCSpawnEvent;
import net.citizensnpcs.api.event.SpawnReason;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** Integración con Citizens: clic derecho en un NPC -> arranca la charla usando el NOMBRE REAL del NPC
 *  (la entidad de un NPC-jugador no expone bien el nombre con espacios/acentos).
 *  Además: al CREAR un NPC cuyo nombre casa con un persona que tiene skin-url, le pone la skin solo. */
public final class CitizensListener implements Listener {

    private final Plugin plugin;
    private final ConversationManager mgr;
    private final Supplier<AiConfig> cfg;
    private final Set<Integer> skinned = ConcurrentHashMap.newKeySet(); // ids ya skineados esta sesion
    private com.nemeles.npcai.StreetDealManager deals;   // opcional (venta callejera de maria)
    private com.nemeles.npcai.CocaChainManager coca;     // opcional (cadena de la cocaina)
    private com.nemeles.npcai.NpcCombatManager combat;   // P1: re-hacer danable al respawnear en chunk-load

    public CitizensListener(Plugin plugin, ConversationManager mgr, Supplier<AiConfig> cfg) {
        this.plugin = plugin;
        this.mgr = mgr;
        this.cfg = cfg;
    }

    public void setDeals(com.nemeles.npcai.StreetDealManager deals) { this.deals = deals; }
    public void setCoca(com.nemeles.npcai.CocaChainManager coca) { this.coca = coca; }
    public void setCombat(com.nemeles.npcai.NpcCombatManager combat) { this.combat = combat; }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onNpcRightClick(NPCRightClickEvent e) {
        String name = e.getNPC().getName();
        Player player0 = e.getClicker();
        Entity ent0 = e.getNPC().getEntity();
        // CADENA DE LA COCA: los NPC con nombre de la cadena (El Tuerto, Don Anselmo...) gestionan
        // su propio trato ANTES de cualquier charla/persona.
        if (coca != null && ent0 != null && coca.tryInteract(player0, ent0)) return;
        NpcPersona p = cfg.get().match(name == null ? null : ChatColor.stripColor(name));
        if (p == null) return;
        Player player = e.getClicker();
        if (!player.hasPermission("nemeles.npcai.use")) return;
        Entity ent = e.getNPC().getEntity();
        if (ent == null) return;
        // Con BOLSAS DE MARIA en la mano: trato de calle (negociar precio) en vez de charla.
        if (deals != null && deals.holdingBags(player)) {
            deals.interact(player, p, ent);
            return;
        }
        if (mgr.isActive(player.getUniqueId())) mgr.end(player);
        mgr.start(player, p, ent.getUniqueId());
    }

    /** Al spawnear un NPC (crearlo O recargar su chunk): le re-aplica vida/vulnerabilidad y, si es nuevo, la skin. */
    @EventHandler
    public void onNpcSpawn(NPCSpawnEvent e) {
        final NPC npc = e.getNPC();
        // SIEMPRE (tambien en chunk-load): si casa con un persona, hazlo danable otra vez. Si no, Citizens lo
        // respawnea con protected=true por defecto y queda "intocable" (ni lo golpeas ni se engancha a atacarte).
        if (combat != null) {
            try {
                String n = npc.getName();
                if (n != null && cfg.get() != null && cfg.get().match(ChatColor.stripColor(n)) != null) {
                    plugin.getServer().getScheduler().runTaskLater(plugin,
                            () -> { try { combat.makeVulnerable(npc); } catch (Throwable ignored) { } }, 3L);
                }
            } catch (Throwable ignored) { }
        }
        if (e.getReason() == SpawnReason.CREATE) applySkin(npc, false);   // skin solo al crearlo
    }

    /** Aplica la skin configurada (skin-url) a UN npc si su nombre casa con un persona. */
    private void applySkin(NPC npc, boolean force) {
        if (npc == null) return;
        String name = npc.getName();
        NpcPersona p = cfg.get().match(name == null ? null : ChatColor.stripColor(name));
        if (p == null || p.skinUrl == null || p.skinUrl.isBlank()) return;
        int id = npc.getId();
        if (!force && !skinned.add(id)) return; // ya hecho esta sesion
        dispatchSkin(id, p.skinUrl, 10L);
    }

    /** Aplica las skins configuradas a TODOS los NPCs ya existentes (lo usa /npcai skin). */
    public int applyAllSkins() {
        int n = 0, stagger = 0;
        for (NPC npc : CitizensAPI.getNPCRegistry()) {
            String name = npc.getName();
            NpcPersona p = cfg.get().match(name == null ? null : ChatColor.stripColor(name));
            if (p == null || p.skinUrl == null || p.skinUrl.isBlank()) continue;
            skinned.add(npc.getId());
            dispatchSkin(npc.getId(), p.skinUrl, 10L + (stagger++ * 15L));
            n++;
        }
        return n;
    }

    /** Replica EXACTAMENTE lo que harias a mano: seleccionar el NPC y aplicarle la skin por URL. */
    private void dispatchSkin(int id, String url, long delayTicks) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            try {
                CommandSender con = Bukkit.getConsoleSender();
                Bukkit.dispatchCommand(con, "npc select " + id);
                Bukkit.dispatchCommand(con, "npc skin --url " + url);
            } catch (Throwable t) {
                plugin.getLogger().warning("No se pudo aplicar la skin al NPC " + id + ": " + t.getMessage());
            }
        }, delayTicks);
    }
}
