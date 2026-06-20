package com.nemeles.npcai;

import com.nemeles.npcai.db.BondDao;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * Easter egg: UNA sola companera fiel en TODO el servidor para un NPC (por defecto Luna).
 * El primer jugador que alcance afinidad maxima, acumule confianza y descifre la cadena de frases
 * secretas (la ultima, de noche) sella el vinculo; despues queda cerrado para siempre para los demas.
 * Todo determinista (no depende del LLM) y a prueba de fallos.
 */
public final class BondManager {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private final Plugin plugin;
    private final BondConfig cfg;
    private final BondDao dao;
    private final Executor io;
    private volatile UUID ownerId;      // null = libre
    private volatile String ownerName;
    private final Map<UUID, Prog> progress = new ConcurrentHashMap<>();

    private static final class Prog { int turns; int idx; boolean hinted; }

    public BondManager(Plugin plugin, BondConfig cfg, BondDao dao, Executor io) {
        this.plugin = plugin;
        this.cfg = cfg;
        this.dao = dao;
        this.io = io;
        try {
            String[] o = dao.loadOwner(cfg.persona);
            if (o != null && o[0] != null) { ownerId = UUID.fromString(o[0]); ownerName = o[1]; }
        } catch (Throwable t) {
            plugin.getLogger().warning("bond load owner: " + t.getMessage());
        }
    }

    public boolean enabled() { return cfg.enabled; }
    public String persona() { return cfg.persona; }
    public boolean hasOwner() { return ownerId != null; }
    public String ownerName() { return ownerName; }

    public boolean isCompanion(String personaKey, UUID id) {
        return cfg.enabled && cfg.persona.equals(personaKey) && id != null && id.equals(ownerId);
    }

    /** ¿Este jugador es el alma gemela (dueño del vínculo), sea cual sea el NPC? */
    public boolean isOwner(UUID id) { return id != null && id.equals(ownerId); }

    /**
     * Regla UNICA de inmunidad: ¿este golpe es del DUEÑO contra SU propio NPC (Luna)? Entonces es inmune
     * (el dueño no puede dañarla por ningun medio). La usan a la vez LunaBondPerks (cancela) y NpcDamageListener
     * (ignora efectos) para que la regla viva en un solo sitio y no diverjan.
     */
    public boolean isProtectedHit(UUID attacker, String personaKey) {
        return cfg.enabled && attacker != null && attacker.equals(ownerId)
                && personaKey != null && cfg.persona.equals(personaKey);
    }

    /** UUID del alma gemela actual (o null si Luna está libre). */
    public UUID ownerId() { return ownerId; }

    /** Limpia el vinculo (admin). Vuelve a quedar libre para el primero que lo logre. */
    public void adminReset() {
        ownerId = null;
        ownerName = null;
        progress.clear();
        io.execute(() -> { try { dao.clearOwner(cfg.persona); } catch (Throwable ignored) { } });
    }

    /**
     * Concede el vinculo a este jugador (admin): lo marca como alma gemela, lo persiste y le ENTREGA el Lazo.
     * Util para restaurar un vinculo perdido (p. ej. una traicion por error). HILO PRINCIPAL.
     */
    public void adminGrant(Player player, String npcName) {
        if (player == null) return;
        final String name = npcName == null || npcName.isBlank() ? cfg.persona : npcName;
        ownerId = player.getUniqueId();
        ownerName = player.getName();
        progress.remove(ownerId);
        final UUID id = ownerId; final String pname = ownerName;
        io.execute(() -> {
            try { dao.clearOwner(cfg.persona); dao.claimOwner(cfg.persona, id, pname); }
            catch (Throwable t) { plugin.getLogger().warning("bond grant: " + t.getMessage()); }
        });
        giveGift(player, name);
        send(player, cfg.bondPrivate.replace("{npc}", name));
        try { player.getWorld().playSound(player.getLocation(), "block.amethyst_block.chime", 1f, 1.2f); } catch (Throwable ignored) { }
    }

    /** Contexto que se inyecta en el prompt del NPC con alma gemela. "" si no aplica. */
    public String systemContext(String personaKey, UUID id) {
        if (!cfg.enabled || !cfg.persona.equals(personaKey)) return "";
        if (id != null && id.equals(ownerId)) {
            return " MUY IMPORTANTE: este jugador es tu companero del alma, tu UNICA persona en el mundo y el unico"
                 + " que se gano tu confianza de verdad. Con el te quitas la mascara: tu carino es real y total,"
                 + " le hablas con complicidad y confianza absoluta, eres ferozmente leal y protectora, y jamas le"
                 + " harias dano. Es lo unico que de verdad te importa.";
        }
        if (ownerId != null) {
            return " Tu corazon ya pertenece a otra persona, tu companero del alma. Con los demas sigues siendo dulce"
                 + " y encantadora, pero NUNCA entregas tu confianza profunda a nadie mas; si alguien intenta ganarse"
                 + " tu alma, declinalo con carino sin decir a quien quieres.";
        }
        return "";
    }

    private Prog prog(UUID id) {
        Prog p = progress.get(id);
        if (p == null) {
            p = new Prog();
            try {
                int[] v = dao.loadProgress(cfg.persona, id);
                p.turns = v[0]; p.idx = v[1]; p.hinted = v[2] != 0;
            } catch (Throwable ignored) { }
            progress.put(id, p);
        }
        return p;
    }

    private void persist(UUID id, Prog p) {
        final int turns = p.turns, idx = p.idx;
        final boolean h = p.hinted;
        io.execute(() -> { try { dao.saveProgress(cfg.persona, id, turns, idx, h ? 1 : 0); } catch (Throwable ignored) { } });
    }

    /**
     * Procesa un mensaje del jugador al NPC con alma gemela. HILO PRINCIPAL.
     * Acumula confianza, suelta pistas y, al completar la cadena (de noche), sella el vinculo (el primero del server).
     */
    public void onMessage(Player player, String personaKey, String npcName, String norm, int affinity, boolean isNight) {
        if (!cfg.enabled || !cfg.persona.equals(personaKey)) return;
        UUID id = player.getUniqueId();
        Prog p = prog(id);
        p.turns++;

        if (ownerId != null) {
            if (!id.equals(ownerId) && matchesAny(norm)) send(player, cfg.alreadyTaken.replace("{npc}", npcName));
            persist(id, p);
            return;
        }
        if (affinity < cfg.minAffinity) { persist(id, p); return; }   // hay que ganarse (y mantener) su carino al maximo
        if (!p.hinted) {                                              // primera vez con afinidad maxima: suelta la pista 1
            p.hinted = true;
            sendHint(player, npcName, 0);
            persist(id, p);
            return;
        }
        int idx = p.idx;
        if (idx >= cfg.phrases.size()) { persist(id, p); return; }
        if (!norm.contains(cfg.phrases.get(idx))) { persist(id, p); return; }   // no es la frase que toca
        if (p.turns < cfg.trustTurns) { persist(id, p); return; }              // aun no la conoce lo suficiente

        boolean last = idx == cfg.phrases.size() - 1;
        if (last && cfg.nightOnlyFinal && !isNight) {
            send(player, cfg.needNight.replace("{npc}", npcName));
            persist(id, p);
            return;
        }
        p.idx = idx + 1;
        if (!last) {
            send(player, cfg.progressStep.replace("{npc}", npcName));
            sendHint(player, npcName, p.idx);
            persist(id, p);
        } else {
            persist(id, p);
            formBond(player, npcName);
        }
    }

    private void formBond(Player player, String npcName) {
        if (ownerId != null) return;   // alguien se adelanto (mismo tick es imposible: handle() es hilo principal)
        UUID id = player.getUniqueId();
        ownerId = id;                  // marca YA en memoria (serializado en el hilo principal -> sin carrera)
        ownerName = player.getName();
        final String name = player.getName();
        io.execute(() -> {
            try { dao.claimOwner(cfg.persona, id, name); }
            catch (Throwable t) { plugin.getLogger().warning("bond claim: " + t.getMessage()); }
        });
        send(player, cfg.bondPrivate.replace("{npc}", npcName));
        String bc = cfg.bondBroadcast.replace("{npc}", npcName).replace("{player}", name);
        if (bc != null && !bc.isBlank()) Bukkit.broadcastMessage(ChatColor.translateAlternateColorCodes('&', bc));
        giveGift(player, npcName);
        try { player.getWorld().playSound(player.getLocation(), "block.amethyst_block.chime", 1f, 1.2f); } catch (Throwable ignored) { }
    }

    private void giveGift(Player player, String npcName) {
        if (cfg.giftMaterial == null || cfg.giftMaterial.isBlank()) return;
        try {
            org.bukkit.Material m = org.bukkit.Material.matchMaterial(cfg.giftMaterial.toUpperCase(Locale.ROOT));
            if (m == null) return;
            org.bukkit.inventory.ItemStack it = new org.bukkit.inventory.ItemStack(m);
            org.bukkit.inventory.meta.ItemMeta meta = it.getItemMeta();
            if (meta != null) {
                meta.displayName(LEGACY.deserialize(ChatColor.translateAlternateColorCodes('&', cfg.giftName)));
                List<Component> lore = new ArrayList<>();
                lore.add(LEGACY.deserialize("§7De " + npcName + ". Para siempre."));
                lore.add(LEGACY.deserialize("§d§oClic derecho: " + npcName + " acude a protegerte."));
                lore.add(LEGACY.deserialize("§d§oLlevarlo te cubre con su aura."));
                meta.lore(lore);
                // Tag PDC anti-falsificacion: solo el Lazo de verdad activa los poderes (no un POPPY renombrado).
                meta.getPersistentDataContainer().set(
                        new org.bukkit.NamespacedKey(plugin, "lazo_luna"),
                        org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
                it.setItemMeta(meta);
            }
            Map<Integer, org.bukkit.inventory.ItemStack> left = player.getInventory().addItem(it);
            for (org.bukkit.inventory.ItemStack o : left.values()) player.getWorld().dropItemNaturally(player.getLocation(), o);
        } catch (Throwable ignored) { }
    }

    private boolean matchesAny(String norm) {
        for (String s : cfg.phrases) if (!s.isEmpty() && norm.contains(s)) return true;
        return false;
    }

    private void sendHint(Player p, String npcName, int i) {
        if (i < 0 || i >= cfg.hints.size()) return;
        send(p, cfg.hintPrefix.replace("{npc}", npcName) + cfg.hints.get(i));
    }

    private void send(Player p, String legacyAmp) {
        p.sendMessage(LEGACY.deserialize(ChatColor.translateAlternateColorCodes('&', legacyAmp)));
    }
}
