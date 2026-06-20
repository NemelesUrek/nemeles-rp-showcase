package com.nemeles.territories;

import com.nemeles.core.api.NemelesApi;
import com.nemeles.core.api.economy.MoneyType;
import com.nemeles.core.api.faction.Faction;
import com.nemeles.core.api.faction.FactionLifecycleEvent;
import com.nemeles.core.api.faction.FactionPermission;
import com.nemeles.core.api.faction.Relation;
import com.nemeles.territories.db.TerritoriesDao;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Guerras de territorio con ventana + bote (escrow). SOLO el atacante arriesga el bote.
 *
 * <p>{@code /turf guerra declarar}: el atacante paga el bote (LIMPIO de su banco) a una cuenta de
 * ESCROW sintetica. Hay una ventana de preparacion (aviso) y luego una ventana activa donde la captura
 * por presencia decide. Si el atacante captura -> recupera el bote; si la ventana expira sin captura ->
 * el bote pasa al DEFENSOR. El dinero se mueve SIEMPRE con transfer (atomico) para no crear/duplicar.</p>
 */
public final class WarManager {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private final Plugin plugin;
    private final TerritoryServiceImpl svc;
    private final TerrConfig cfg;
    private final TerritoriesDao dao;
    private final Executor dbExecutor;

    // territoryId -> guerra en curso (a lo sumo una por territorio)
    private final Map<Integer, WarSession> activeWars = new ConcurrentHashMap<>();
    private final AtomicLong nextWarId = new AtomicLong(0);
    // true solo cuando nextWarId ya esta fijado desde BD: declare() rechaza guerras hasta entonces para
    // que dos arranques no reutilicen un id (INSERT con PK duplicada -> escrow congelado).
    private volatile boolean loaded = false;

    WarManager(Plugin plugin, TerritoryServiceImpl svc, TerrConfig cfg, TerritoriesDao dao, Executor dbExecutor) {
        this.plugin = plugin;
        this.svc = svc;
        this.cfg = cfg;
        this.dao = dao;
        this.dbExecutor = dbExecutor;
    }

    /**
     * Carga + reconciliacion. Fija {@code nextWarId} de forma SINCRONA (leyendo MAX(id) en BD) ANTES de
     * que {@link #declare} pueda correr, para evitar la carrera del contador (dos arranques reusando un id
     * -> INSERT con PK duplicada -> escrow congelado). La reconciliacion de guerras colgadas (refund al
     * atacante) sigue siendo async porque toca economia/scheduler.
     *
     * <p>Se llama en {@code onEnable} ANTES de registrar los comandos /turf, asi que cuando el contador
     * esta fijado ya no hay forma de declarar con un id antiguo. El flag {@code loaded} es defensa extra:
     * si la lectura sincrona fallara, declare() lo verifica igualmente.</p>
     */
    public void load() {
        // ── sincrono: fijar el contador antes de aceptar declaraciones ──
        try {
            nextWarId.set(dao.maxWarId());
            loaded = true;
        } catch (Exception e) {
            plugin.getLogger().warning("[TERR] No se pudo fijar nextWarId al arrancar: " + e.getMessage());
        }
        // ── async: reconciliar guerras colgadas (refund al atacante) ──
        dbExecutor.execute(() -> {
            try {
                for (WarSession w : dao.loadOpenWars()) {
                    // El servidor cayo con la guerra abierta: el escrow sigue retenido. Devolvemos el bote
                    // al atacante y cerramos. (No reanudamos la ventana: las CaptureSession no se persisten.)
                    final WarSession war = w;
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        refund(war, "war:reconcile:" + war.id);
                        markEnded(war);
                    });
                }
                plugin.getLogger().info("[TERR] Guerras reconciliadas (escrow devuelto al atacante si quedo colgado).");
            } catch (Exception e) {
                plugin.getLogger().warning("[TERR] Error reconciliando guerras: " + e.getMessage());
            }
        });
    }

    boolean isInPrep(int territoryId, long now) {
        WarSession w = activeWars.get(territoryId);
        return w != null && w.inPrep(now);
    }

    /** True solo si hay una guerra con la VENTANA ACTIVA abierta (la captura por presencia decide). */
    boolean isInWindow(int territoryId, long now) {
        WarSession w = activeWars.get(territoryId);
        return w != null && w.id >= 0 && WarSession.WINDOW.equals(w.state) && w.inWindow(now);
    }

    boolean hasActiveWar(int territoryId) { return activeWars.containsKey(territoryId); }

    WarSession war(int territoryId) { return activeWars.get(territoryId); }

    // ─── /turf guerra declarar ───────────────────────────────
    /** Devuelve un mensaje de error (empieza por §c/§e) o null si la declaracion procede (escrow async). */
    public String declare(Player p, String territoryName) {
        if (!loaded) return "§eEl sistema de guerras aun se esta cargando, intenta en un momento.";
        int fac = factionOf(p);
        if (fac < 0) return "§cNo estas en ninguna mafia.";
        if (!hasPerm(p, FactionPermission.BANK_WITHDRAW))
            return "§cNo tienes permiso para arriesgar el banco de la mafia en una guerra.";
        TerritoryData t = territoryName == null ? null : byName(territoryName);
        if (t == null) return "§cNo existe el territorio '" + territoryName + "'.";
        if (t.ownerFaction < 0) return "§eEse territorio es NEUTRAL: usa §f/turf atacar §een la zona, no una guerra.";
        if (t.ownerFaction == fac) return "§eEse territorio ya es de tu mafia.";
        if (relation(fac, t.ownerFaction) == Relation.ALLY) return "§cNo puedes declarar la guerra a un aliado.";
        long now = System.currentTimeMillis();
        if (t.isShielded(now)) return "§cEsa zona esta protegida tras una captura reciente.";
        if (activeWars.containsKey(t.id)) return "§eYa hay una guerra en curso por §f" + t.name + "§e.";
        if (cfg.warPotCents <= 0) return "§cLas guerras estan desactivadas en este servidor (bote = 0).";

        final int defender = t.ownerFaction;
        final int tid = t.id;
        final String tname = t.name;
        // Reserva optimista del slot para que dos /declarar simultaneos no dupliquen el escrow.
        // Se libera si el cobro del escrow falla.
        WarSession placeholder = new WarSession(-1, tid, fac, defender, cfg.warPotCents, 0, 0, WarSession.PREP);
        if (activeWars.putIfAbsent(tid, placeholder) != null)
            return "§eYa hay una guerra en curso por §f" + tname + "§e.";

        UUID attackerBank = accountId(fac);
        BigDecimal pot = money(cfg.warPotCents);
        long warId = nextWarId.incrementAndGet();
        UUID escrow = WarSession.escrowAccount(warId);
        NemelesApi.economy().transfer(attackerBank, MoneyType.LIMPIO, escrow, MoneyType.LIMPIO, pot, "war:escrow:" + warId)
                .thenAccept(res -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (res == null || !res.success()) {
                        activeWars.remove(tid, placeholder);
                        p.sendMessage("§cEl banco de tu mafia necesita §f$" + pot.toPlainString()
                                + "§c en LIMPIO para declarar la guerra.");
                        return;
                    }
                    long n2 = System.currentTimeMillis();
                    long prepEnd = n2 + (long) cfg.warPrepMinutes * 60_000L;
                    long windowEnd = prepEnd + (long) cfg.warWindowMinutes * 60_000L;
                    WarSession war = new WarSession(warId, tid, fac, defender, cfg.warPotCents,
                            prepEnd, windowEnd, WarSession.PREP);
                    activeWars.put(tid, war); // reemplaza el placeholder
                    persistInsert(war);
                    broadcastDeclared(war, tname);
                    fireLifecycle(FactionLifecycleEvent.Kind.WAR_DECLARED, fac, defender, tid);
                }));
        return null;
    }

    // ─── tick (desde TerritoryManager.contestTick) ───────────
    /** Avanza las guerras: aviso al abrir la ventana activa y resolucion al expirar sin captura. */
    void warTick(long now) {
        for (WarSession w : activeWars.values()) {
            if (w.id < 0) continue; // placeholder de escrow en curso
            if (WarSession.PREP.equals(w.state) && now >= w.prepEndMs && now < w.windowEndMs) {
                w.state = WarSession.WINDOW;
                persistState(w);
                broadcastWindowOpen(w);
            } else if (w.windowExpired(now)) {
                resolveToDefender(w);
            }
        }
    }

    // ─── resolucion ──────────────────────────────────────────
    /** ¿Hay una guerra activa (no terminada) por este territorio? Lo usa TerritoryManager para no duplicar la cronica de captura. */
    boolean isActiveWar(int territoryId) {
        WarSession w = activeWars.get(territoryId);
        return w != null && w.id >= 0 && !WarSession.ENDED.equals(w.state);
    }

    /** Llamado por TerritoryManager cuando una captura se consuma dentro de la ventana de guerra. */
    void onCaptured(int territoryId, int newOwner) {
        WarSession w = activeWars.get(territoryId);
        if (w == null || w.id < 0 || WarSession.ENDED.equals(w.state)) return;
        if (newOwner == w.attackerFaction) {
            // El atacante (quien financio el bote) capturo: recupera el escrow.
            payout(w, newOwner, "war:payout:capture:" + w.id);
            markEnded(w);
            broadcastResult(w, newOwner, true);
        } else {
            // Una 3ª mafia (o el defensor por presencia) se llevo la zona durante la ventana: NO le pagamos
            // el bote que no financio. Devolvemos el escrow al atacante y cerramos la guerra.
            refund(w, "war:refund:thirdparty:" + w.id);
            markEnded(w);
            broadcastThirdPartyCapture(w, newOwner);
        }
        fireLifecycle(FactionLifecycleEvent.Kind.WAR_ENDED, w.attackerFaction, w.defenderFaction, territoryId);
    }

    /**
     * Una mafia se disolvio a mitad de guerra: cancela TODA guerra activa donde sea atacante o defensora y
     * libera el escrow. Si el atacante sigue existiendo, refund al atacante; si el disuelto ES el atacante,
     * devuelve al DEFENSOR si existe; si ninguno existe, deja el escrow sin pagar y loguea (lo decide
     * {@link #refund}). Se llama desde el listener de FactionLifecycleEvent en el hilo principal.
     */
    void onFactionDisbanded(int disbandedFactionId) {
        for (WarSession w : activeWars.values()) {
            if (w.id < 0 || WarSession.ENDED.equals(w.state)) continue;
            if (w.attackerFaction != disbandedFactionId && w.defenderFaction != disbandedFactionId) continue;
            // refund() valida existencia: si el atacante es el disuelto, redirige al defensor; si ninguno
            // existe, no paga y loguea. Nunca a una cuenta inexistente.
            refund(w, "war:refund:disband:" + disbandedFactionId + ":" + w.id);
            markEnded(w);
            Bukkit.broadcast(LEGACY.deserialize("§4[GUERRA] §7La guerra por §f" + nameOf(w.territoryId)
                    + " §7se cancela: una de las mafias se disolvio. El bote fue liberado."));
            fireLifecycle(FactionLifecycleEvent.Kind.WAR_ENDED, w.attackerFaction, w.defenderFaction, w.territoryId);
        }
    }

    private void resolveToDefender(WarSession w) {
        // La ventana expiro sin que el atacante capturase: el bote es para el defensor.
        TerritoryData t = svc.rawData(w.territoryId);
        int defender = t != null && t.ownerFaction >= 0 ? t.ownerFaction : w.defenderFaction;
        if (defender >= 0) payout(w, defender, "war:payout:defend:" + w.id);
        else refund(w, "war:refund:nodefender:" + w.id); // zona quedo neutral: devuelve al atacante
        markEnded(w);
        broadcastResult(w, defender, false);
        fireLifecycle(FactionLifecycleEvent.Kind.WAR_ENDED, w.attackerFaction, w.defenderFaction, w.territoryId);
    }

    /**
     * Paga el escrow al banco de la faccion ganadora (transfer atomico). Defensa en profundidad: si la
     * ganadora ya NO existe (disuelta a mitad de guerra), redirige el bote al ATACANTE si sigue existiendo;
     * si tampoco, deja el escrow sin pagar y loguea. Nunca transfiere a la cuenta sintetica de una faccion
     * inexistente.
     */
    private void payout(WarSession w, int winnerFaction, String reason) {
        if (w.potCents <= 0 || winnerFaction < 0) return;
        if (factionExists(winnerFaction)) {
            moveEscrow(w, winnerFaction, reason);
        } else if (factionExists(w.attackerFaction)) {
            moveEscrow(w, w.attackerFaction, "war:refund:winner-gone:" + w.id);
        } else {
            logOrphanEscrow(w, "ganadora " + winnerFaction + " inexistente y atacante tampoco");
        }
    }

    /**
     * Devuelve el escrow al atacante (reconciliacion / sin defensor valido). Defensa en profundidad: si el
     * atacante ya NO existe, redirige al DEFENSOR si existe; si ninguno existe, deja el escrow sin pagar y
     * loguea (nunca a una cuenta inexistente).
     */
    private void refund(WarSession w, String reason) {
        if (w.potCents <= 0) return;
        if (factionExists(w.attackerFaction)) {
            moveEscrow(w, w.attackerFaction, reason);
        } else if (factionExists(w.defenderFaction)) {
            moveEscrow(w, w.defenderFaction, "war:refund:attacker-gone:" + w.id);
        } else {
            logOrphanEscrow(w, "atacante " + w.attackerFaction + " y defensor " + w.defenderFaction + " inexistentes");
        }
    }

    /** Mueve el escrow al banco de una faccion ya validada como existente (transfer atomico). */
    private void moveEscrow(WarSession w, int toFaction, String reason) {
        UUID escrow = w.escrowAccount();
        UUID toBank = accountId(toFaction);
        NemelesApi.economy().transfer(escrow, MoneyType.LIMPIO, toBank, MoneyType.LIMPIO,
                money(w.potCents), reason);
    }

    private void logOrphanEscrow(WarSession w, String why) {
        plugin.getLogger().warning("[TERR] Escrow de la guerra " + w.id + " (bote $"
                + money(w.potCents).toPlainString() + ") sin destinatario valido: " + why
                + ". Se deja en la cuenta de escrow (no se paga a cuenta inexistente).");
    }

    private boolean factionExists(int factionId) {
        if (factionId < 0) return false;
        try { return NemelesApi.factions().getFaction(factionId).isPresent(); }
        catch (Throwable t) { return false; }
    }

    private void markEnded(WarSession w) {
        w.state = WarSession.ENDED;
        activeWars.remove(w.territoryId, w);
        persistState(w);
    }

    public void shutdown() {
        // No tocamos el escrow al apagar: las guerras abiertas quedan en BD y se reconcilian al arrancar
        // (refund al atacante). Solo limpiamos la cache en memoria.
        activeWars.clear();
    }

    // ─── broadcast / evento ──────────────────────────────────
    private void broadcastDeclared(WarSession w, String tname) {
        Bukkit.broadcast(LEGACY.deserialize("§4[GUERRA] §e" + tag(w.attackerFaction) + " §cha declarado la GUERRA por §f"
                + tname + " §c(de §e" + tag(w.defenderFaction) + "§c). Bote: §6$" + money(w.potCents).toPlainString()
                + "§c. Preparacion: §f" + cfg.warPrepMinutes + " min§c."));
        com.nemeles.core.api.RpAnnounceEvent.fire("⚔️",
                tag(w.attackerFaction) + " declara la GUERRA por " + tname,
                "**" + tag(w.attackerFaction) + "** le declara la guerra a **" + tag(w.defenderFaction)
                        + "** por el territorio **" + tname + "**. Bote en juego: **$" + money(w.potCents).toPlainString()
                        + "**. Preparacion: " + cfg.warPrepMinutes + " min.",
                "cronica de mafias");
    }

    private void broadcastWindowOpen(WarSession w) {
        Bukkit.broadcast(LEGACY.deserialize("§4[GUERRA] §c¡La ventana por §f" + nameOf(w.territoryId)
                + " §cESTA ABIERTA! §e" + tag(w.attackerFaction) + " §ctiene §f" + cfg.warWindowMinutes
                + " min §cpara capturarla y llevarse el bote de §6$" + money(w.potCents).toPlainString() + "§c."));
        com.nemeles.core.api.RpAnnounceEvent.fire("🔥",
                "¡Empieza la batalla por " + nameOf(w.territoryId) + "!",
                "La ventana de guerra esta ABIERTA. **" + tag(w.attackerFaction) + "** tiene " + cfg.warWindowMinutes
                        + " min para capturar **" + nameOf(w.territoryId) + "** y llevarse el bote de **$" + money(w.potCents).toPlainString() + "**.",
                "cronica de mafias");
    }

    private void broadcastResult(WarSession w, int winnerFaction, boolean attackerWon) {
        String tname = nameOf(w.territoryId);
        if (attackerWon) {
            Bukkit.broadcast(LEGACY.deserialize("§4[GUERRA] §a" + tag(winnerFaction) + " §aGANO la guerra por §f"
                    + tname + " §ay recupera el bote de §6$" + money(w.potCents).toPlainString() + "§a."));
            com.nemeles.core.api.RpAnnounceEvent.fire("🏴",
                    tag(winnerFaction) + " gana la guerra por " + tname,
                    "**" + tag(winnerFaction) + "** capturo **" + tname + "** y se lleva el bote de **$"
                            + money(w.potCents).toPlainString() + "**. El mapa de la ciudad cambia.",
                    "cronica de mafias");
        } else if (winnerFaction >= 0) {
            Bukkit.broadcast(LEGACY.deserialize("§4[GUERRA] §c" + tag(w.attackerFaction) + " §cno capturo §f"
                    + tname + "§c. §a" + tag(winnerFaction) + " §adefiende y se queda el bote de §6$"
                    + money(w.potCents).toPlainString() + "§a."));
            com.nemeles.core.api.RpAnnounceEvent.fire("🛡️",
                    tag(winnerFaction) + " defiende " + tname,
                    "**" + tag(w.attackerFaction) + "** no pudo capturar **" + tname + "**. **" + tag(winnerFaction)
                            + "** aguanta y se queda el bote de **$" + money(w.potCents).toPlainString() + "**.",
                    "cronica de mafias");
        } else {
            Bukkit.broadcast(LEGACY.deserialize("§4[GUERRA] §7La guerra por §f" + tname
                    + " §7termino sin defensor; el bote vuelve a §e" + tag(w.attackerFaction) + "§7."));
        }
    }

    private void broadcastThirdPartyCapture(WarSession w, int captor) {
        String tname = nameOf(w.territoryId);
        Bukkit.broadcast(LEGACY.deserialize("§4[GUERRA] §c" + tag(captor) + " §carrebato §f" + tname
                + " §cdurante la guerra. El bote de §6$" + money(w.potCents).toPlainString()
                + " §cvuelve a §e" + tag(w.attackerFaction) + "§c (no financio el captor)."));
        com.nemeles.core.api.RpAnnounceEvent.fire("🏴",
                tag(captor) + " arrebata " + tname + " en plena guerra",
                "**" + tag(captor) + "** se cuela y captura **" + tname + "** durante la guerra de otras mafias. El bote vuelve a **"
                        + tag(w.attackerFaction) + "** (no lo financio el captor).",
                "cronica de mafias");
    }

    private void fireLifecycle(FactionLifecycleEvent.Kind kind, int facA, int facB, int tid) {
        try { Bukkit.getPluginManager().callEvent(new FactionLifecycleEvent(kind, facA, facB, tid)); }
        catch (Throwable ignored) { }
    }

    // ─── persistencia (async) ────────────────────────────────
    private void persistInsert(WarSession w) {
        dbExecutor.execute(() -> {
            try { dao.insertWar(w.id, w.territoryId, w.attackerFaction, w.defenderFaction, w.potCents,
                    w.prepEndMs, w.windowEndMs, w.state); }
            catch (Exception e) { plugin.getLogger().warning("[TERR] war insert: " + e.getMessage()); }
        });
    }

    private void persistState(WarSession w) {
        final long id = w.id;
        final String state = w.state;
        if (id < 0) return;
        dbExecutor.execute(() -> {
            try { dao.updateWarState(id, state); }
            catch (Exception e) { plugin.getLogger().warning("[TERR] war state: " + e.getMessage()); }
        });
    }

    // ─── helpers ─────────────────────────────────────────────
    private TerritoryData byName(String name) {
        for (TerritoryData t : svc.rawAll()) if (t.name.equalsIgnoreCase(name)) return t;
        return null;
    }

    private String nameOf(int tid) {
        TerritoryData t = svc.rawData(tid);
        return t == null ? "?" : t.name;
    }

    private int factionOf(Player p) {
        try { return NemelesApi.factions().getFactionOf(p.getUniqueId()).map(Faction::id).orElse(-1); }
        catch (Throwable t) { return -1; }
    }

    private boolean hasPerm(Player p, FactionPermission perm) {
        try { return NemelesApi.factions().hasPermission(p.getUniqueId(), perm); }
        catch (Throwable t) { return false; }
    }

    private Relation relation(int a, int b) {
        try { return NemelesApi.factions().relation(a, b); }
        catch (Throwable t) { return Relation.NEUTRAL; }
    }

    private UUID accountId(int factionId) {
        return NemelesApi.factions().accountId(factionId);
    }

    private String tag(int id) {
        try { return NemelesApi.factions().getFaction(id).map(Faction::tag).orElse("?"); }
        catch (Throwable t) { return "?"; }
    }

    private static BigDecimal money(long cents) { return BigDecimal.valueOf(cents).movePointLeft(2); }
}
