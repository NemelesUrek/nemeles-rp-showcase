package com.nemeles.territories;

import com.nemeles.core.api.NemelesApi;
import com.nemeles.core.api.economy.MoneyType;
import com.nemeles.core.api.faction.Faction;
import com.nemeles.core.api.faction.Relation;
import com.nemeles.core.api.territory.TerritoryResult;
import com.nemeles.core.api.territory.TerritoryService;
import com.nemeles.core.api.territory.TerritorySnapshot;
import com.nemeles.territories.db.TerritoriesDao;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class TerritoryServiceImpl implements TerritoryService {

    private final Plugin plugin;
    private final TerritoriesDao dao;
    private final Executor dbExecutor;
    private final TerrConfig cfg;

    private final java.util.Map<Integer, TerritoryData> territories = new ConcurrentHashMap<>();
    // world -> (chunkKey -> territoryId) : lookup O(1) en el hot-path
    private final java.util.Map<String, java.util.Map<Long, Integer>> chunkOwner = new ConcurrentHashMap<>();
    private final AtomicInteger nextId = new AtomicInteger(0);
    private final AtomicLong nextHistoryId = new AtomicLong(0);

    private TerritoryManager manager;

    public TerritoryServiceImpl(Plugin plugin, TerritoriesDao dao, Executor dbExecutor, TerrConfig cfg) {
        this.plugin = plugin;
        this.dao = dao;
        this.dbExecutor = dbExecutor;
        this.cfg = cfg;
    }

    void setManager(TerritoryManager manager) { this.manager = manager; }

    public void load() {
        dbExecutor.execute(() -> {
            try {
                java.util.Map<Integer, TerritoryData> loaded = dao.loadAll();
                territories.putAll(loaded);
                for (TerritoryData t : loaded.values()) {
                    java.util.Map<Long, Integer> wc = chunkOwner.computeIfAbsent(t.world, k -> new ConcurrentHashMap<>());
                    for (long key : t.chunks) wc.put(key, t.id);
                }
                nextId.set(dao.maxId());
                nextHistoryId.set(dao.maxHistoryId());
                plugin.getLogger().info("[TERR] Cargados " + territories.size() + " territorios.");
            } catch (Exception e) {
                plugin.getLogger().warning("[TERR] Error cargando territorios: " + e.getMessage());
            }
        });
    }

    /** Persiste todos los metadatos al apagar. SINCRONO a proposito: en onDisable el dbExecutor puede
     *  morir antes de vaciar la cola async, asi que escribimos directo para no perder el ultimo estado. */
    public void flushAll() {
        for (TerritoryData t : territories.values()) {
            try { dao.updateMeta(t); }
            catch (Exception e) { plugin.getLogger().warning("[TERR] flush meta: " + e.getMessage()); }
        }
    }

    // ─── lecturas ────────────────────────────────────────────
    @Override
    public Optional<TerritorySnapshot> territoryAt(Location loc) {
        TerritoryData t = dataAt(loc);
        return t == null ? Optional.empty() : Optional.of(t.snapshot());
    }

    @Override
    public Optional<TerritorySnapshot> getTerritory(int territoryId) {
        TerritoryData t = territories.get(territoryId);
        return t == null ? Optional.empty() : Optional.of(t.snapshot());
    }

    @Override
    public Optional<TerritorySnapshot> getTerritoryByName(String name) {
        if (name == null) return Optional.empty();
        for (TerritoryData t : territories.values()) {
            if (t.name.equalsIgnoreCase(name)) return Optional.of(t.snapshot());
        }
        return Optional.empty();
    }

    @Override
    public int ownerFactionAt(Location loc) {
        TerritoryData t = dataAt(loc);
        return t == null ? -1 : t.ownerFaction;
    }

    @Override
    public Collection<TerritorySnapshot> all() {
        Collection<TerritorySnapshot> out = new ArrayList<>();
        for (TerritoryData t : territories.values()) out.add(t.snapshot());
        return out;
    }

    @Override
    public Collection<TerritorySnapshot> ownedBy(int factionId) {
        Collection<TerritorySnapshot> out = new ArrayList<>();
        for (TerritoryData t : territories.values()) if (t.ownerFaction == factionId) out.add(t.snapshot());
        return out;
    }

    @Override
    public int countOwnedBy(int factionId) {
        if (factionId < 0) return 0;
        int n = 0;
        for (TerritoryData t : territories.values()) if (t.ownerFaction == factionId) n++;
        return n;
    }

    @Override
    public boolean isContested(int territoryId) { return manager != null && manager.isContested(territoryId); }

    @Override
    public boolean isShielded(int territoryId) {
        TerritoryData t = territories.get(territoryId);
        return t != null && t.isShielded(System.currentTimeMillis());
    }

    // ─── hook economico ──────────────────────────────────────
    @Override
    public long applyDrugTax(UUID seller, Location loc, long grossDirtyCents) {
        try {
            if (loc == null || loc.getWorld() == null || grossDirtyCents <= 0) return grossDirtyCents;
            TerritoryData t = dataAt(loc);
            if (t == null || t.ownerFaction < 0 || !TerritoryData.ACTIVE.equals(t.state)) return grossDirtyCents;
            int owner = t.ownerFaction;
            double rate = cfg.taxWeed;
            try {
                Faction sf = NemelesApi.factions().getFactionOf(seller).orElse(null);
                int sellerFac = sf == null ? -1 : sf.id();
                if (sellerFac == owner) rate = cfg.taxWeedMember;
                else if (sellerFac >= 0) {
                    Relation rel = NemelesApi.factions().relation(sellerFac, owner);
                    rate = rel == Relation.ALLY ? cfg.taxWeedAlly
                            : rel == Relation.ENEMY ? cfg.taxWeedEnemy : cfg.taxWeed;
                }
            } catch (Throwable ignored) { rate = cfg.taxWeed; }
            if (rate <= 0) return grossDirtyCents;
            if (rate > 0.95) rate = 0.95; // clamp defensivo: nunca confiscar el 100% (el vendedor siempre cobra algo)
            long tax = Math.round(grossDirtyCents * rate);
            if (tax > grossDirtyCents - 1) tax = grossDirtyCents - 1; // deja >=1 centimo al vendedor
            if (tax <= 0) return grossDirtyCents;
            UUID account = NemelesApi.factions().accountId(owner);
            BigDecimal taxAmt = BigDecimal.valueOf(tax).movePointLeft(2);
            // TRANSFER (no deposit): mueve el peaje DEL VENDEDOR (que ya cobro el bruto) al banco de la
            // mafia. Es atomico -> nunca crea dinero aunque algo falle. Requiere que el vendedor ya tenga
            // el bruto: WeedManager.sell() llama a este metodo SOLO tras confirmar el deposito de la venta.
            NemelesApi.economy().transfer(seller, MoneyType.SUCIO, account, MoneyType.SUCIO, taxAmt, "territory:tax:" + t.id);
            return grossDirtyCents - tax;
        } catch (Throwable t) {
            return grossDirtyCents;
        }
    }

    // ─── escrituras ──────────────────────────────────────────
    @Override
    public TerritoryResult define(String name, World world, int tier, Set<Long> chunkKeys) {
        if (name == null || name.isBlank()) return TerritoryResult.fail("NOMBRE_INVALIDO");
        if (world == null) return TerritoryResult.fail("MUNDO_INVALIDO");
        if (getTerritoryByName(name).isPresent()) return TerritoryResult.fail("NOMBRE_EN_USO");
        if (chunkKeys == null || chunkKeys.isEmpty()) return TerritoryResult.fail("SIN_CHUNKS");

        java.util.Map<Long, Integer> wc = chunkOwner.computeIfAbsent(world.getName(), k -> new ConcurrentHashMap<>());
        Set<Long> free = new HashSet<>();
        for (long k : chunkKeys) if (!wc.containsKey(k)) free.add(k);
        if (free.isEmpty()) return TerritoryResult.fail("CHUNKS_OCUPADOS");

        int id = nextId.incrementAndGet();
        long now = System.currentTimeMillis();
        TerritoryData t = new TerritoryData(id, name, world.getName(), clampTier(tier), -1,
                cfg.incomeBaseCents, TerritoryData.ACTIVE, 0, 0, now, now);
        t.chunks.addAll(free);
        territories.put(id, t);
        for (long k : free) wc.put(k, id);

        final String w = world.getName();
        dbExecutor.execute(() -> {
            try { dao.insertTerritory(t); dao.insertChunks(id, w, free); }
            catch (Exception e) { plugin.getLogger().warning("[TERR] define save: " + e.getMessage()); }
        });
        return TerritoryResult.ok(id);
    }

    @Override
    public TerritoryResult addChunks(int territoryId, World world, Set<Long> chunkKeys) {
        TerritoryData t = territories.get(territoryId);
        if (t == null) return TerritoryResult.fail("NO_EXISTE");
        if (world == null || !t.world.equals(world.getName())) return TerritoryResult.fail("OTRO_MUNDO");
        if (chunkKeys == null || chunkKeys.isEmpty()) return TerritoryResult.fail("SIN_CHUNKS");
        java.util.Map<Long, Integer> wc = chunkOwner.computeIfAbsent(t.world, k -> new ConcurrentHashMap<>());
        Set<Long> free = new HashSet<>();
        for (long k : chunkKeys) if (!wc.containsKey(k)) free.add(k);
        if (free.isEmpty()) return TerritoryResult.fail("CHUNKS_OCUPADOS");
        t.chunks.addAll(free);
        for (long k : free) wc.put(k, territoryId);
        final String w = t.world;
        dbExecutor.execute(() -> {
            try { dao.insertChunks(territoryId, w, free); }
            catch (Exception e) { plugin.getLogger().warning("[TERR] addChunks save: " + e.getMessage()); }
        });
        return TerritoryResult.ok(territoryId);
    }

    @Override
    public TerritoryResult dissolve(int territoryId) {
        TerritoryData t = territories.remove(territoryId);
        if (t == null) return TerritoryResult.fail("NO_EXISTE");
        java.util.Map<Long, Integer> wc = chunkOwner.get(t.world);
        if (wc != null) for (long k : t.chunks) wc.remove(k, territoryId);
        if (manager != null) manager.abortCapture(territoryId);
        dbExecutor.execute(() -> {
            try { dao.deleteTerritory(territoryId); }
            catch (Exception e) { plugin.getLogger().warning("[TERR] dissolve save: " + e.getMessage()); }
        });
        return TerritoryResult.ok(territoryId);
    }

    @Override
    public TerritoryResult setOwner(int territoryId, int factionId, String reason) {
        TerritoryData t = territories.get(territoryId);
        if (t == null) return TerritoryResult.fail("NO_EXISTE");
        int from = t.ownerFaction;
        long now = System.currentTimeMillis();
        t.ownerFaction = factionId;
        if (factionId >= 0) {
            t.capturedAt = now;
            t.lastIncomeAt = now;
            t.lastUpkeepAt = now;
            t.state = TerritoryData.ACTIVE;
        }
        persist(t);
        writeHistory(territoryId, from, factionId, reason, now);
        return TerritoryResult.ok(territoryId);
    }

    @Override
    public TerritoryResult setIncome(int territoryId, long incomeCents) {
        TerritoryData t = territories.get(territoryId);
        if (t == null) return TerritoryResult.fail("NO_EXISTE");
        t.incomeCents = Math.max(0, incomeCents);
        persist(t);
        return TerritoryResult.ok(territoryId);
    }

    @Override
    public TerritoryResult setTier(int territoryId, int tier) {
        TerritoryData t = territories.get(territoryId);
        if (t == null) return TerritoryResult.fail("NO_EXISTE");
        t.tier = clampTier(tier);
        persist(t);
        return TerritoryResult.ok(territoryId);
    }

    // ─── helpers para el manager (mismo paquete) ─────────────
    TerritoryData rawData(int id) { return territories.get(id); }

    Collection<TerritoryData> rawAll() { return territories.values(); }

    int territoryIdAt(String world, long chunkKey) {
        java.util.Map<Long, Integer> wc = chunkOwner.get(world);
        if (wc == null) return -1;
        Integer id = wc.get(chunkKey);
        return id == null ? -1 : id;
    }

    /** Captura consumada: cambia dueno, fija escudo, reinicia relojes, escribe historial. */
    void completeCapture(int territoryId, int newOwner, long shieldMs) {
        TerritoryData t = territories.get(territoryId);
        if (t == null) return;
        int from = t.ownerFaction;
        long now = System.currentTimeMillis();
        t.ownerFaction = newOwner;
        t.capturedAt = now;
        t.shieldUntil = now + shieldMs;
        t.lastIncomeAt = now;
        t.lastUpkeepAt = now;
        t.state = TerritoryData.ACTIVE;
        t.influence = 0;   // el nuevo dueno empieza su lealtad desde cero
        persist(t);
        writeHistory(territoryId, from, newOwner, "capture", now);
    }

    void markIncomePaid(int territoryId, long ts) {
        TerritoryData t = territories.get(territoryId);
        if (t == null) return;
        t.lastIncomeAt = ts;
        persist(t);
    }

    void markUpkeepPaid(int territoryId, long ts) {
        TerritoryData t = territories.get(territoryId);
        if (t == null) return;
        t.lastUpkeepAt = ts;
        if (TerritoryData.DECAY.equals(t.state)) t.state = TerritoryData.ACTIVE;
        persist(t);
    }

    void setState(int territoryId, String state) {
        TerritoryData t = territories.get(territoryId);
        if (t == null || state.equals(t.state)) return;
        t.state = state;
        persist(t);
    }

    TerrConfig cfg() { return cfg; }

    // ─── internos ────────────────────────────────────────────
    private TerritoryData dataAt(Location loc) {
        if (loc == null || loc.getWorld() == null) return null;
        java.util.Map<Long, Integer> wc = chunkOwner.get(loc.getWorld().getName());
        if (wc == null) return null;
        Integer id = wc.get(TerritoryService.chunkKeyOf(loc));
        return id == null ? null : territories.get(id);
    }

    private void persist(TerritoryData t) {
        dbExecutor.execute(() -> {
            try { dao.updateMeta(t); }
            catch (Exception e) { plugin.getLogger().warning("[TERR] save meta: " + e.getMessage()); }
        });
    }

    private void writeHistory(int territoryId, int from, int to, String reason, long at) {
        long hid = nextHistoryId.incrementAndGet();
        dbExecutor.execute(() -> {
            try { dao.insertHistory(hid, territoryId, from, to, reason, at); }
            catch (Exception e) { plugin.getLogger().warning("[TERR] save history: " + e.getMessage()); }
        });
    }

    private static int clampTier(int tier) { return Math.max(1, Math.min(3, tier)); }
}
