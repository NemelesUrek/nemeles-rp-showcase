package com.nemeles.npcai;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Interacciones FISICAS con los NPCs (requiere Citizens): que te SIGAN si se lo pides (y la amistad
 * acompana), que se QUEDEN, que se MARCHEN molestos si los echas, y REGALOS de comida (se la comen
 * y suben afinidad). La orden se detecta de forma determinista en lo que dices (texto o voz) y la
 * accion REAL se le cuenta a la IA como "nota de escena" para que responda acorde.
 * Todo a prueba de fallos: sin Citizens (o con entidades no-Citizens) simplemente no actua.
 */
public final class InteractionManager {

    private record Follow(int npcId, UUID player, long until) { }

    private final Plugin plugin;
    private final ConversationManager mgr;
    private final AffinityManager affinity;   // puede ser null (BD apagada)
    private final Supplier<AiConfig> cfg;
    private final Map<Integer, Follow> following = new ConcurrentHashMap<>();

    public InteractionManager(Plugin plugin, ConversationManager mgr, AffinityManager affinity, Supplier<AiConfig> cfg) {
        this.plugin = plugin;
        this.mgr = mgr;
        this.affinity = affinity;
        this.cfg = cfg;
    }

    /** ¿Este NPC de Citizens esta escoltando a alguien? (lo usa el paseo para no interferir). */
    public boolean isFollowing(int npcId) { return following.containsKey(npcId); }

    // ───────────────────────── ordenes por chat/voz ─────────────────────────

    /** Hook desde la conversacion (HILO PRINCIPAL): detecta sigueme/quedate/vete y ACTUA. */
    public void onChat(Player p, Conversation c, String norm) {
        AiConfig a = cfg.get();
        if (!a.itxEnabled || norm == null || norm.isEmpty()) return;
        NPC npc = npcOf(c.entityId);
        if (npc == null) return;   // no es un NPC de Citizens: sin acciones fisicas

        if (containsAny(norm, a.leaveWords)) { leave(p, c, npc, a); return; }
        if (containsAny(norm, a.stayWords))  { stay(p, c, npc); return; }
        if (containsAny(norm, a.followWords)) follow(p, c, npc, a);
    }

    private void follow(Player p, Conversation c, NPC npc, AiConfig a) {
        int aff = affinity == null ? 0 : affinity.get(c.persona.key, p.getUniqueId());
        if (aff <= a.hostWaryThreshold) {
            c.actionNote = "te acaba de pedir que le acompanes y te has NEGADO con desprecio: no te fias de el (NO le sigues).";
            send(p, fill(a.followWaryMsg, c.persona.name, aff, a.followMinAffinity));
            return;
        }
        if (aff < a.followMinAffinity) {
            c.actionNote = "te ha pedido que le acompanes, pero aun no hay confianza suficiente: te niegas con educacion (NO le sigues).";
            send(p, fill(a.followLowMsg, c.persona.name, aff, a.followMinAffinity));
            return;
        }
        following.put(npc.getId(), new Follow(npc.getId(), p.getUniqueId(), System.currentTimeMillis() + a.followMaxMs));
        c.actionNote = "has ACEPTADO acompanarle: ahora caminas fisicamente detras de el (esta pasando de verdad).";
        send(p, fill(a.followOkMsg, c.persona.name, aff, a.followMinAffinity));
    }

    /** Rellena una plantilla de mensaje de seguir: {npc}, {have} (afinidad actual), {need} (umbral). */
    private static String fill(String tpl, String npcName, int have, int need) {
        return (tpl == null ? "" : tpl)
                .replace("{npc}", npcName)
                .replace("{have}", String.valueOf(Math.max(0, have)))
                .replace("{need}", String.valueOf(need));
    }

    private void stay(Player p, Conversation c, NPC npc) {
        Follow f = following.get(npc.getId());
        if (f == null || !f.player().equals(p.getUniqueId())) return;   // no te estaba siguiendo: que responda la IA
        stopFollow(npc);
        // su nueva "casa" es donde se queda (el paseo girara en torno a este punto)
        try {
            Location l = npc.getEntity() != null ? npc.getEntity().getLocation() : null;
            if (l != null) npc.data().setPersistent("nemeles-home",
                    (l.getWorld() == null ? "world" : l.getWorld().getName()) + ";" + l.getBlockX() + ";" + l.getBlockY() + ";" + l.getBlockZ());
        } catch (Throwable ignored) { }
        c.actionNote = "te ha pedido que esperes aqui: dejas de seguirle y te quedas en este sitio (es real).";
        send(p, "&8(" + c.persona.name + " se queda aquí)");
    }

    private void leave(Player p, Conversation c, NPC npc, AiConfig a) {
        Follow f = following.get(npc.getId());
        if (f != null && f.player().equals(p.getUniqueId())) stopFollow(npc);
        if (affinity != null) { try { affinity.bump(c.persona.key, p.getUniqueId(), -a.leaveAnnoy); } catch (Throwable ignored) { } }
        // se marcha unos pasos, molesto
        try {
            Entity e = npc.getEntity();
            if (e != null) {
                Vector dir = e.getLocation().toVector().subtract(p.getLocation().toVector()).setY(0);
                if (dir.lengthSquared() < 0.01) dir = new Vector(1, 0, 0);
                Location away = e.getLocation().add(dir.normalize().multiply(10));
                npc.getNavigator().setTarget(away);
                e.getWorld().spawnParticle(Particle.ANGRY_VILLAGER, e.getLocation().add(0, e.getHeight() + 0.3, 0), 5, 0.25, 0.2, 0.25, 0.02);
            }
        } catch (Throwable ignored) { }
        c.actionNote = "te ha echado (te ha dicho que te vayas): te has MOLESTADO de verdad y te marchas de alli ofendido.";
        send(p, "&8(" + c.persona.name + " se marcha, molesto)");
    }

    // ───────────────────────── regalos de comida ─────────────────────────

    private final Map<String, Long> lastGiftDay = new ConcurrentHashMap<>();   // (persona|jugador) -> dia del ultimo +afinidad por comida

    /** Clic derecho con COMIDA en la mano: si le gusta, se la come y te coge mas carino (max +afinidad UNA vez al dia). */
    public boolean gift(Player p, Entity ent, NpcPersona persona, ItemStack hand) {
        AiConfig a = cfg.get();
        if (!a.itxEnabled || hand == null || hand.getType() == Material.AIR || !hand.getType().isEdible()) return false;
        Material m = hand.getType();
        // ¿A este NPC le gusta esta comida? Si tiene lista de gustos y no esta, la rechaza con cortesia (sin comer ni afinidad).
        Set<Material> liked = a.likedFoods.get(persona.key);
        if (liked != null && !liked.isEmpty() && !liked.contains(m)) {
            send(p, "&d" + persona.name + " &7mira tu &f" + pretty(m) + "&7, arruga la nariz y lo rechaza: eso no le gusta.");
            mgr.noteAction(p.getUniqueId(), ent.getUniqueId(),
                    "le ofreciste " + pretty(m) + " pero a el/ella NO le gusta esa comida y la rechaza con cortesia.");
            return true;
        }
        hand.setAmount(hand.getAmount() - 1);   // se la come
        // Tope diario: el +afinidad por comida solo cuenta UNA vez al dia por NPC (no se puede spamear hasta el maximo).
        boolean gained = false;
        if (affinity != null) {
            long today = System.currentTimeMillis() / 86_400_000L;
            String k = persona.key + "|" + p.getUniqueId();
            Long last = lastGiftDay.get(k);
            if (last == null || last != today) {
                lastGiftDay.put(k, today);
                try { affinity.bump(persona.key, p.getUniqueId(), a.giftAffinity); } catch (Throwable ignored) { }
                gained = true;
            }
        }
        try {
            ent.getWorld().playSound(ent.getLocation(), "entity.generic.eat", 1f, 1f);
            ent.getWorld().spawnParticle(Particle.HEART, ent.getLocation().add(0, ent.getHeight() + 0.4, 0), 3, 0.25, 0.2, 0.25, 0.01);
            if (ent instanceof Player npcPl) npcPl.swingMainHand();
        } catch (Throwable ignored) { }
        String item = pretty(m);
        if (gained) {
            send(p, "&d" + persona.name + " &7acepta tu &f" + item + "&7, se lo come con gusto y te mira con más cariño.");
            mgr.noteAction(p.getUniqueId(), ent.getUniqueId(),
                    "te acaba de REGALAR comida que LE GUSTA (" + item + ") y te ha cogido mas carino.");
        } else {
            send(p, "&d" + persona.name + " &7se come tu &f" + item + "&7, pero por hoy ya te ganaste su aprecio. &8(vuelve mañana)");
            mgr.noteAction(p.getUniqueId(), ent.getUniqueId(),
                    "te dio mas comida que le gusta, pero ya te habia cogido carino hoy; lo agradece igual.");
        }
        return true;
    }

    // ───────────────────────── escolta (tick cada 2 s) ─────────────────────────

    public void tick() {
        if (following.isEmpty()) return;
        long now = System.currentTimeMillis();
        AiConfig a = cfg.get();
        for (Follow f : following.values().toArray(new Follow[0])) {
            NPC npc = byId(f.npcId());
            Player p = Bukkit.getPlayer(f.player());
            if (npc == null || !npc.isSpawned() || p == null || !p.isOnline() || now > f.until()) {
                following.remove(f.npcId());
                if (npc != null) stopNav(npc);
                continue;
            }
            try {
                Entity e = npc.getEntity();
                if (!e.getWorld().equals(p.getWorld()) || e.getLocation().distanceSquared(p.getLocation()) > 30 * 30) {
                    e.teleport(p.getLocation());   // no te pierde: se teletransporta a tu lado si te alejas mucho
                    continue;
                }
                double d2 = e.getLocation().distanceSquared(p.getLocation());
                if (d2 > 3.2 * 3.2) {
                    try { npc.getNavigator().getLocalParameters().speedModifier((float) a.followSpeed); } catch (Throwable ignored) { }
                    npc.getNavigator().setTarget(p, false);
                } else if (npc.getNavigator().isNavigating()) {
                    npc.getNavigator().cancelNavigation();
                }
            } catch (Throwable ignored) { }
        }
    }

    /** Suelta a todos (apagado/recarga limpia). */
    public void stopAll() {
        for (Integer id : following.keySet().toArray(new Integer[0])) {
            NPC npc = byId(id);
            if (npc != null) stopNav(npc);
        }
        following.clear();
    }

    // ───────────────────────── utilidades ─────────────────────────

    private void stopFollow(NPC npc) {
        following.remove(npc.getId());
        stopNav(npc);
    }

    private void stopNav(NPC npc) {
        try { if (npc.getNavigator().isNavigating()) npc.getNavigator().cancelNavigation(); } catch (Throwable ignored) { }
    }

    private NPC npcOf(UUID entityId) {
        try {
            Entity ent = Bukkit.getEntity(entityId);
            return ent == null ? null : CitizensAPI.getNPCRegistry().getNPC(ent);
        } catch (Throwable t) { return null; }
    }

    private NPC byId(int id) {
        try { return CitizensAPI.getNPCRegistry().getById(id); } catch (Throwable t) { return null; }
    }

    private static boolean containsAny(String norm, Set<String> words) {
        for (String w : words) if (!w.isEmpty() && norm.contains(w)) return true;
        return false;
    }

    private static String pretty(Material m) {
        return m.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private void send(Player p, String legacyAmp) {
        p.sendMessage(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection()
                .deserialize(ChatColor.translateAlternateColorCodes('&', legacyAmp)));
    }
}
