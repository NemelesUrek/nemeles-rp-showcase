package com.nemeles.territories;

import com.nemeles.core.api.NemelesApi;
import com.nemeles.core.api.economy.MoneyType;
import com.nemeles.core.api.faction.Faction;
import com.nemeles.core.api.faction.Relation;
import com.nemeles.core.api.territory.TerritoryService;
import com.nemeles.territories.event.TerritoryCapturedEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Logica temporal: contest (captura), renta pasiva, upkeep, BossBars, anti-AFK. Corre en el hilo principal. */
public final class TerritoryManager {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private final Plugin plugin;
    private final TerritoryServiceImpl svc;
    private final TerrConfig cfg;
    private final WarManager wars;

    private final Map<Integer, CaptureSession> activeCaptures = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastActive = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> playerTerritory = new ConcurrentHashMap<>();

    TerritoryManager(Plugin plugin, TerritoryServiceImpl svc, TerrConfig cfg, WarManager wars) {
        this.plugin = plugin;
        this.svc = svc;
        this.cfg = cfg;
        this.wars = wars;
    }

    boolean isContested(int territoryId) { return activeCaptures.containsKey(territoryId); }

    void abortCapture(int territoryId) {
        CaptureSession cs = activeCaptures.remove(territoryId);
        if (cs != null && cs.bar != null) cs.bar.removeAll();
    }

    /** Quita todas las BossBars de captura activas y limpia el estado (al apagar/recargar el plugin,
     *  si no, quedan barras "CAPTURA" fantasma en el cliente hasta reloguear). */
    public void shutdown() {
        for (CaptureSession cs : activeCaptures.values()) if (cs.bar != null) cs.bar.removeAll();
        activeCaptures.clear();
    }

    // ─── movimiento / presencia ──────────────────────────────
    public void onMove(Player p, Location to) {
        lastActive.put(p.getUniqueId(), System.currentTimeMillis());
        if (to.getWorld() == null) return;
        int tid = svc.territoryIdAt(to.getWorld().getName(), TerritoryService.chunkKeyOf(to));
        Integer prev = playerTerritory.get(p.getUniqueId());
        int prevId = prev == null ? -1 : prev;
        if (tid != prevId) {
            playerTerritory.put(p.getUniqueId(), tid);
            if (tid >= 0) {
                TerritoryData t = svc.rawData(tid);
                if (t != null) {
                    String owner = t.ownerFaction < 0 ? "§7sin dueño" : "§e" + tag(t.ownerFaction);
                    boolean mine = t.ownerFaction >= 0 && t.ownerFaction == factionOf(p);
                    p.sendActionBar(LEGACY.deserialize("§6▶ §fEntras en " + t.name + " §7· " + owner
                            + (mine ? " §a· tu territorio (+vida)" : "")));
                }
            }
        }
    }

    public void onQuit(Player p) {
        lastActive.remove(p.getUniqueId());
        playerTerritory.remove(p.getUniqueId());
        for (CaptureSession cs : activeCaptures.values()) if (cs.bar != null) cs.bar.removePlayer(p);
    }

    /** PERK DE TURF: los miembros de la mafia dueña regeneran vida mientras están en su propio territorio. */
    private void applyZoneBuff(TerritoryData t, List<Player> present) {
        if (t.ownerFaction < 0 || present.isEmpty()) return;
        for (Player p : present) {
            if (factionOf(p) != t.ownerFaction) continue;
            if (isDownedSafe(p)) continue;
            try {
                p.addPotionEffect(new org.bukkit.potion.PotionEffect(
                        org.bukkit.potion.PotionEffectType.REGENERATION, 160, 0, true, false, false));
            } catch (Throwable ignored) { }
        }
    }

    // ─── contest (cada tick-seconds) ─────────────────────────
    public void contestTick() {
        long now = System.currentTimeMillis();
        if (wars != null) wars.warTick(now); // avanza guerras: abre ventana / resuelve al expirar
        Map<Integer, List<Player>> byTerr = new HashMap<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getGameMode() == GameMode.SPECTATOR) continue;
            if (p.getWorld() == null) continue;
            int tid = svc.territoryIdAt(p.getWorld().getName(), TerritoryService.chunkKeyOf(p.getLocation()));
            if (tid >= 0) byTerr.computeIfAbsent(tid, k -> new ArrayList<>()).add(p);
        }
        Set<Integer> toProcess = new HashSet<>(byTerr.keySet());
        toProcess.addAll(activeCaptures.keySet());
        for (int tid : toProcess) {
            TerritoryData t = svc.rawData(tid);
            if (t == null) { abortCapture(tid); continue; }
            processTerritory(t, byTerr.getOrDefault(tid, List.of()), now);
        }
    }

    private void processTerritory(TerritoryData t, List<Player> present, long now) {
        int owner = t.ownerFaction;
        applyZoneBuff(t, present);   // perk de turf: los miembros del dueño regeneran en su propia zona
        // ── gating de guerra ─────────────────────────────────
        if (wars != null) {
            if (wars.isInPrep(t.id, now)) { abortCapture(t.id); return; } // en preparacion no se captura
            // Zona CON dueno: la captura por presencia SOLO procede dentro de una ventana de guerra activa.
            // (Las zonas neutrales se siguen capturando por presencia / con /turf atacar.)
            if (owner >= 0 && !wars.isInWindow(t.id, now)) { abortCapture(t.id); return; }
        }
        Map<Integer, Integer> attackers = new HashMap<>();
        int defenders = 0;
        for (Player p : present) {
            if (!isActive(p, now)) continue;
            if (isDownedSafe(p)) continue;          // un derribado no defiende ni captura: gana quien quede en pie
            int fac = factionOf(p);
            if (fac < 0) continue;
            if (owner >= 0) {
                if (fac == owner) { defenders++; continue; }
                if (relation(fac, owner) == Relation.ALLY) { defenders++; continue; }
            }
            attackers.merge(fac, 1, Integer::sum);
        }
        int defCount = Math.min(defenders, cfg.defendersCap);
        CaptureSession cs = activeCaptures.get(t.id);
        if (cs == null) {
            if (defCount > 0) return;
            if (t.isShielded(now)) return;
            int attFac = dominant(attackers);
            if (attFac < 0) return;
            if (svc.countOwnedBy(attFac) >= maxZonesPerFaction()) return;
            if (cfg.respectSafezone && isSafezone(t)) return;
            cs = startCapture(t, attFac, now);
        }
        advanceCapture(t, cs, attackers, defCount, present, now);
    }

    private CaptureSession startCapture(TerritoryData t, int attFac, long now) {
        CaptureSession cs = new CaptureSession(t.id, attFac, now);
        cs.goalSeconds = effectiveCaptureSeconds(t);   // fija la dificultad AL INICIAR: la lealtad no sube a mitad del asalto
        cs.bar = Bukkit.createBossBar("§cCAPTURA §f" + t.name, BarColor.RED, BarStyle.SEGMENTED_10);
        activeCaptures.put(t.id, cs);
        String def = t.ownerFaction < 0 ? "zona neutral" : tag(t.ownerFaction);
        Bukkit.broadcast(LEGACY.deserialize("§c[Territorios] §e" + tag(attFac) + " §7intenta capturar §f" + t.name + " §7(" + def + ")"));
        return cs;
    }

    private void advanceCapture(TerritoryData t, CaptureSession cs, Map<Integer, Integer> attackers,
                                int defCount, List<Player> present, long now) {
        int attCount = Math.min(attackers.getOrDefault(cs.attackerFaction, 0), cfg.attackersCap);
        boolean warning = now < cs.startedAt + (long) cfg.warnSeconds * 1000L;
        boolean frozen = false;
        if (attCount <= 0) {
            cs.progressSeconds -= cfg.tickSeconds;
            if (cs.progressSeconds <= 0) { abortCapture(t.id); return; }
        } else if (defCount > 0) {
            frozen = true; // contested-freeze: la pelea decide, no progresa
        } else if (!warning) {
            cs.progressSeconds += cfg.tickSeconds;
            if (cs.progressSeconds >= cs.goalSeconds) { consumeCapture(t, cs); return; }
        }
        updateCaptureBar(t, cs, present, warning, frozen);
    }

    /** Segundos de captura efectivos: una zona con mucha LEALTAD (influencia) tarda mas en capturarse (hasta 1+bonus x). */
    private int effectiveCaptureSeconds(TerritoryData t) {
        if (cfg.influenceMaxUnits <= 0 || cfg.influenceCaptureBonus <= 0 || t.influence <= 0) return cfg.captureSeconds;
        double frac = Math.min(1.0, (double) t.influence / cfg.influenceMaxUnits);
        return Math.max(cfg.captureSeconds, (int) Math.round(cfg.captureSeconds * (1.0 + frac * cfg.influenceCaptureBonus)));
    }

    private void consumeCapture(TerritoryData t, CaptureSession cs) {
        int from = t.ownerFaction;
        int to = cs.attackerFaction;
        svc.completeCapture(t.id, to, cfg.shieldMs);
        String atk = tag(to);
        String old = from < 0 ? "neutral" : tag(from);
        Bukkit.broadcast(LEGACY.deserialize("§6[Territorios] §e" + atk + " §aha capturado §f" + t.name + " §7a §c" + old));
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getWorld() == null) continue;
            if (svc.territoryIdAt(p.getWorld().getName(), TerritoryService.chunkKeyOf(p.getLocation())) == t.id) {
                p.showTitle(Title.title(LEGACY.deserialize("§6" + t.name), LEGACY.deserialize("§ecapturada por " + atk)));
                p.playSound(p.getLocation(), Sound.ENTITY_WITHER_SPAWN, 0.6f, 1.2f);
            }
        }
        abortCapture(t.id);
        // Cronica a Discord SOLO si NO es captura de guerra (esas las anuncia el resultado de la guerra, con el bote).
        boolean inWar = wars != null && wars.isActiveWar(t.id);
        if (!inWar) {
            com.nemeles.core.api.RpAnnounceEvent.fire("🏴",
                    atk + " captura " + t.name,
                    "**" + atk + "** se queda con el territorio **" + t.name + "** (antes de **" + old + "**). Banderas nuevas en la zona.",
                    "cronica de mafias");
        }
        // si esta captura cierra una guerra (ventana activa), el atacante recupera el bote
        if (wars != null) { try { wars.onCaptured(t.id, to); } catch (Throwable ignored) { } }
        try { Bukkit.getPluginManager().callEvent(new TerritoryCapturedEvent(t.id, from, to)); }
        catch (Throwable ignored) { }
    }

    private void updateCaptureBar(TerritoryData t, CaptureSession cs, List<Player> present, boolean warning, boolean frozen) {
        BossBar bar = cs.bar;
        if (bar == null) return;
        double prog = Math.max(0.0, Math.min(1.0, cs.progressSeconds / (double) Math.max(1, cs.goalSeconds)));
        bar.setProgress(prog);
        int pct = (int) Math.round(prog * 100);
        String def = t.ownerFaction < 0 ? "NEUTRAL" : tag(t.ownerFaction);
        String status = warning ? " §e(aviso)" : frozen ? " §b(en disputa)" : "";
        bar.setColor(frozen ? BarColor.BLUE : BarColor.RED);
        bar.setTitle("§cCAPTURA §f" + t.name + " §7· §c" + tag(cs.attackerFaction) + " §7vs §a" + def + " §7" + pct + "%" + status);
        bar.removeAll();
        for (Player p : present) bar.addPlayer(p);
    }

    // ─── economia (cada 60s) ─────────────────────────────────
    public void economyTick() {
        long now = System.currentTimeMillis();
        Map<Integer, Boolean> ownerPresent = cfg.incomeOnlineGated ? computeOwnerPresence(now) : Map.of();
        for (TerritoryData t : svc.rawAll()) {
            if (t.ownerFaction < 0) continue;
            // influencia/lealtad: sube mientras la mafia controla la zona (en memoria; se persiste con los relojes).
            if (cfg.influencePerTick > 0 && TerritoryData.ACTIVE.equals(t.state) && t.influence < cfg.influenceMaxUnits) {
                t.influence = Math.min(cfg.influenceMaxUnits, t.influence + cfg.influencePerTick);
                // Cronica (El Faro): hito al CRUZAR la lealtad maxima por primera vez (el `< max` de arriba lo hace una sola vez).
                if (t.influence >= cfg.influenceMaxUnits) {
                    com.nemeles.core.api.RpAnnounceEvent.fire("👑",
                            tag(t.ownerFaction) + " convierte " + t.name + " en su bastion",
                            "**" + t.name + "** alcanzo la LEALTAD MAXIMA bajo control de **" + tag(t.ownerFaction)
                                    + "**. Arrancarles esa zona ahora costara sangre: la captura tarda mucho mas.",
                            "cronica de mafias");
                }
            }
            // renta pasiva (por delta, no retroactiva)
            if (t.lastIncomeAt <= 0) {
                svc.markIncomePaid(t.id, now);
            } else if (TerritoryData.ACTIVE.equals(t.state) && now - t.lastIncomeAt >= (long) cfg.payoutIntervalSeconds * 1000L) {
                boolean present = !cfg.incomeOnlineGated || Boolean.TRUE.equals(ownerPresent.get(t.id));
                if (present) payIncome(t);
                svc.markIncomePaid(t.id, now);
            }
            // upkeep diario
            if (t.lastUpkeepAt <= 0) {
                svc.markUpkeepPaid(t.id, now);
            } else if (now - t.lastUpkeepAt >= (long) cfg.upkeepIntervalHours * 3600_000L) {
                chargeUpkeep(t);
            }
        }
    }

    private void payIncome(TerritoryData t) {
        int n = svc.countOwnedBy(t.ownerFaction);
        double dim = Math.pow(cfg.zoneDiminishing, Math.max(0, n - 1));
        long base = t.incomeCents > 0 ? t.incomeCents : cfg.incomeBaseCents;
        long amt = Math.round(base * cfg.tierMult(t.tier) * dim);
        if (cfg.incomeMaxCents > 0 && amt > cfg.incomeMaxCents) amt = cfg.incomeMaxCents; // tope anti-abuso por periodo
        if (amt <= 0) return;
        try {
            UUID acc = NemelesApi.factions().accountId(t.ownerFaction);
            NemelesApi.economy().deposit(acc, MoneyType.LIMPIO, money(amt), "territory:income:" + t.id);
        } catch (Throwable ignored) { }
    }

    private void chargeUpkeep(TerritoryData t) {
        int n = svc.countOwnedBy(t.ownerFaction);
        long upkeep = Math.round(cfg.upkeepBaseCents * Math.pow(Math.max(1, n), cfg.upkeepExponent));
        final int tid = t.id;
        final long lastUpkeep = t.lastUpkeepAt;
        final long now = System.currentTimeMillis();
        if (upkeep <= 0) { svc.markUpkeepPaid(tid, now); return; }
        // marca optimista SINCRONA del cobro ANTES del withdraw async: cierra la ventana de doble-cobro
        // si el callback se retrasa mas de un economyTick (BD saturada). Se revierte en el fallo para
        // que el cobro se reintente el proximo tick y siga creciendo la gracia de decay.
        svc.markUpkeepPaid(tid, now);
        try {
            UUID acc = NemelesApi.factions().accountId(t.ownerFaction);
            NemelesApi.economy().withdraw(acc, MoneyType.LIMPIO, money(upkeep), "territory:upkeep:" + tid)
                .thenAccept(res -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (res != null && res.success()) {
                        // ya marcado de forma optimista; nada mas que hacer
                    } else {
                        svc.markUpkeepPaid(tid, lastUpkeep); // revertir: reintentar el cobro el proximo tick
                        svc.setState(tid, TerritoryData.DECAY);
                        long elapsed = now - lastUpkeep;
                        if (elapsed >= (long) cfg.upkeepIntervalHours * 3600_000L + (long) cfg.decayGraceDays * 86_400_000L) {
                            String name = nameOf(tid);
                            svc.setOwner(tid, -1, "decay");
                            abortCapture(tid);
                            Bukkit.broadcast(LEGACY.deserialize("§8[Territorios] §7" + name + " ha quedado liberado por impago del upkeep."));
                        }
                    }
                }));
        } catch (Throwable ignored) {
            svc.markUpkeepPaid(tid, lastUpkeep); // revertir la marca optimista si no se llego a lanzar el withdraw
        }
    }

    private Map<Integer, Boolean> computeOwnerPresence(long now) {
        Map<Integer, Boolean> out = new HashMap<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getGameMode() == GameMode.SPECTATOR || p.getWorld() == null) continue;
            if (!isActive(p, now)) continue;
            int tid = svc.territoryIdAt(p.getWorld().getName(), TerritoryService.chunkKeyOf(p.getLocation()));
            if (tid < 0) continue;
            TerritoryData t = svc.rawData(tid);
            if (t == null || t.ownerFaction < 0) continue;
            if (factionOf(p) == t.ownerFaction) out.put(tid, true);
        }
        return out;
    }

    // ─── /turf atacar ────────────────────────────────────────
    /** Devuelve un mensaje de error (empieza por §c/§e) o null si la declaracion procede. */
    public String attemptDeclare(Player p) {
        int fac = factionOf(p);
        if (fac < 0) return "§cNo estas en ninguna mafia.";
        int rank = rankOf(p);
        if (rank < 0 || rank > 2) return "§cSolo Jefe, Oficial o Miembro pueden declarar una captura.";
        if (p.getWorld() == null) return "§cUbicacion invalida.";
        int tid = svc.territoryIdAt(p.getWorld().getName(), TerritoryService.chunkKeyOf(p.getLocation()));
        if (tid < 0) return "§cNo estas dentro de un territorio.";
        TerritoryData t = svc.rawData(tid);
        if (t == null) return "§cTerritorio invalido.";
        if (t.ownerFaction == fac) return "§eEste territorio ya es de tu mafia.";
        // /turf atacar es SOLO para zonas neutrales. Para arrebatar una zona con dueno hay que declarar
        // una GUERRA (con bote): /turf guerra declarar <territorio>.
        if (t.ownerFaction >= 0)
            return "§cEsa zona tiene dueno. Declara una guerra: §f/turf guerra declarar " + t.name + "§c.";
        long now = System.currentTimeMillis();
        if (t.isShielded(now)) return "§cEsta zona esta protegida tras una captura reciente.";
        if (svc.countOwnedBy(fac) >= maxZonesPerFaction()) return "§cTu mafia ya controla el maximo de territorios.";
        if (cfg.respectSafezone && isSafezone(t)) return "§cUna zona segura no puede capturarse.";
        if (activeCaptures.containsKey(tid)) return "§eYa hay una captura en curso aqui.";

        if (cfg.captureCostCents > 0) {
            final int ftid = tid, ffac = fac;
            NemelesApi.economy().withdraw(p.getUniqueId(), MoneyType.EFECTIVO, money(cfg.captureCostCents), "territory:declare")
                .thenAccept(res -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (res != null && res.success()) {
                        TerritoryData tt = svc.rawData(ftid);
                        if (tt != null && !activeCaptures.containsKey(ftid)) {
                            startCapture(tt, ffac, System.currentTimeMillis());
                            p.sendMessage("§a[Territorios] Has declarado la captura de §f" + tt.name + "§a. ¡Mantén la zona!");
                        }
                    } else {
                        p.sendMessage("§cNecesitas §f$" + money(cfg.captureCostCents).toPlainString() + "§c en efectivo para declarar.");
                    }
                }));
            return null;
        }
        startCapture(t, fac, now);
        p.sendMessage("§a[Territorios] Has declarado la captura de §f" + t.name + "§a. ¡Mantén la zona!");
        return null;
    }

    // ─── disolucion de mafia (desde FactionLifecycleEvent) ───
    /**
     * Una mafia se disolvio: cancela sus guerras (liberando el escrow) y pone NEUTRAL todos sus territorios
     * (con lo que deja de cobrar renta automaticamente, porque economyTick ignora ownerFaction &lt; 0).
     * Corre en el hilo principal (llamado desde el listener del plugin).
     */
    public void handleFactionDisbanded(int factionId) {
        if (factionId < 0) return;
        // 1) cancelar guerras donde la disuelta sea atacante o defensora (refund del escrow)
        if (wars != null) { try { wars.onFactionDisbanded(factionId); } catch (Throwable ignored) { } }
        // 2) liberar sus territorios: NEUTRAL + abortar capturas en curso (deja de pagarse renta)
        List<Integer> owned = new ArrayList<>();
        for (TerritoryData t : svc.rawAll()) if (t.ownerFaction == factionId) owned.add(t.id);
        for (int tid : owned) {
            svc.setOwner(tid, -1, "disband:" + factionId);
            abortCapture(tid);
        }
        if (!owned.isEmpty())
            plugin.getLogger().info("[TERR] Mafia " + factionId + " disuelta: " + owned.size()
                    + " territorio(s) puestos en NEUTRAL.");
    }

    // ─── /turf guerra (delegacion al WarManager) ─────────────
    /** Declara una guerra de territorio. Devuelve mensaje de error (§c/§e) o null si procede (escrow async). */
    public String declareWar(Player p, String territoryName) {
        if (wars == null) return "§cEl sistema de guerras no esta disponible.";
        return wars.declare(p, territoryName);
    }

    /** Lineas de estado de las guerras activas para §e/turf guerra estado. */
    public List<String> warStatusLines() {
        List<String> out = new ArrayList<>();
        if (wars == null) return out;
        long now = System.currentTimeMillis();
        for (TerritoryData t : svc.rawAll()) {
            WarSession w = wars.war(t.id);
            if (w == null || w.id < 0 || WarSession.ENDED.equals(w.state)) continue;
            String phase; long remainingMs;
            if (w.inPrep(now)) { phase = "§ePREPARACION"; remainingMs = w.prepEndMs - now; }
            else { phase = "§cVENTANA ABIERTA"; remainingMs = w.windowEndMs - now; }
            long mins = Math.max(0, remainingMs / 60000L);
            out.add("§7- §f" + t.name + " §7· " + phase + " §7(" + mins + " min) §7· §e"
                    + tag(w.attackerFaction) + " §7vs §a" + tag(w.defenderFaction)
                    + " §7· bote §6$" + money(w.potCents).toPlainString());
        }
        return out;
    }

    public int maxZonesPerFaction() {
        return Math.max(1, Bukkit.getOnlinePlayers().size() / cfg.maxPerFactionDivisor);
    }

    // ─── helpers ─────────────────────────────────────────────
    private boolean isActive(Player p, long now) {
        Long la = lastActive.get(p.getUniqueId());
        return la != null && now - la <= (long) cfg.afkSeconds * 1000L;
    }

    private boolean isDownedSafe(Player p) {
        try { return NemelesApi.combat().isDowned(p.getUniqueId()); }
        catch (Throwable t) { return false; }
    }

    private int factionOf(Player p) {
        try { return NemelesApi.factions().getFactionOf(p.getUniqueId()).map(Faction::id).orElse(-1); }
        catch (Throwable t) { return -1; }
    }

    private int rankOf(Player p) {
        try { return NemelesApi.factions().rankPriorityOf(p.getUniqueId()); }
        catch (Throwable t) { return -1; }
    }

    private Relation relation(int a, int b) {
        try { return NemelesApi.factions().relation(a, b); }
        catch (Throwable t) { return Relation.NEUTRAL; }
    }

    private String tag(int id) {
        try { return NemelesApi.factions().getFaction(id).map(Faction::tag).orElse("?"); }
        catch (Throwable t) { return "?"; }
    }

    private boolean isSafezone(TerritoryData t) {
        try {
            if (t.chunks.isEmpty()) return false;
            long k = t.chunks.iterator().next();
            int cx = (int) k, cz = (int) (k >> 32);
            World w = Bukkit.getWorld(t.world);
            if (w == null) return false;
            return NemelesApi.regions().isSafezone(new Location(w, cx * 16 + 8, 64, cz * 16 + 8));
        } catch (Throwable ex) { return false; }
    }

    private String nameOf(int tid) {
        TerritoryData t = svc.rawData(tid);
        return t == null ? "?" : t.name;
    }

    private static int dominant(Map<Integer, Integer> m) {
        int best = -1, bestN = 0;
        for (Map.Entry<Integer, Integer> e : m.entrySet()) {
            if (e.getValue() > bestN) { bestN = e.getValue(); best = e.getKey(); }
        }
        return best;
    }

    private static BigDecimal money(long cents) { return BigDecimal.valueOf(cents).movePointLeft(2); }
}
