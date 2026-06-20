package com.nemeles.jobs.weed;

import com.nemeles.core.api.NemelesApi;
import com.nemeles.core.api.economy.MoneyType;
import com.nemeles.jobs.JobManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * Marihuana: plantar (solo en zonas), crecer ~3 min por timestamp (sobrevive reinicios), morir si se
 * descuida, cosechar (1-3 cogollos + XP de Agricultura), procesar y vender (SUCIO). Deteccion policial.
 */
public final class WeedManager {

    private static final Material PLANT = Material.SWEET_BERRY_BUSH;

    private final Plugin plugin;
    private final WeedDao dao;
    private final Executor dbExecutor;
    private final WeedItems items;
    private final JobManager jobs;
    private final Random random = new Random();

    private final List<String> zones;
    private final boolean requireZone;
    private final int unlockLevel;
    private final long growSeconds;
    private final long neglectSeconds;
    private final int budsMin;
    private final int budsMax;
    private final int processRatio;
    private final double bagPrice;
    private final double seedPrice;
    private final int detectRadius;
    private final int harvestXpPerBud;

    private final Map<String, WeedPlant> byId = new ConcurrentHashMap<>();
    private final Map<String, WeedPlant> byLoc = new ConcurrentHashMap<>();
    private BukkitTask task;

    public WeedManager(Plugin plugin, WeedDao dao, Executor dbExecutor, WeedItems items, JobManager jobs, FileConfiguration cfg) {
        this.plugin = plugin;
        this.dao = dao;
        this.dbExecutor = dbExecutor;
        this.items = items;
        this.jobs = jobs;
        this.zones = cfg.getStringList("weed.zones");
        this.requireZone = cfg.getBoolean("weed.require-zone", true);
        this.unlockLevel = cfg.getInt("weed.unlock-level", 40);
        this.growSeconds = cfg.getLong("weed.grow-seconds", 180);
        this.neglectSeconds = cfg.getLong("weed.neglect-seconds", 600);
        this.budsMin = cfg.getInt("weed.buds-min", 1);
        this.budsMax = cfg.getInt("weed.buds-max", 3);
        this.processRatio = cfg.getInt("weed.process-ratio", 3);
        this.bagPrice = cfg.getDouble("weed.bag-price", 250.0);
        this.seedPrice = cfg.getDouble("weed.seed-price", 50.0);
        this.detectRadius = cfg.getInt("weed.detect-radius", 40);
        this.harvestXpPerBud = cfg.getInt("weed.harvest-xp-per-bud", 12);
        this.rotChance = Math.max(0, Math.min(1, cfg.getDouble("weed.care.rot-chance", 0.6)));
        this.limitRadius = Math.max(4, cfg.getInt("weed.limit.radius", 16));
        this.limitBase = Math.max(1, cfg.getInt("weed.limit.base", 2));
        this.limitPerLevels = Math.max(1, cfg.getInt("weed.limit.per-levels", 10));
    }

    private final double rotChance;
    private final int limitRadius, limitBase, limitPerLevels;
    private final java.util.Set<String> caring = ConcurrentHashMap.newKeySet();   // plantas con minijuego en curso

    public WeedItems items() { return items; }
    public int unlockLevel() { return unlockLevel; }

    // ─── ciclo de vida ───────────────────────────────────────
    public void load() {
        dbExecutor.execute(() -> {
            try {
                for (WeedPlant p : dao.loadActive()) {
                    byId.put(p.id, p);
                    byLoc.put(p.locKey(), p);
                }
                plugin.getLogger().info("[WEED] Plantas vivas cargadas: " + byId.size());
            } catch (Exception e) {
                plugin.getLogger().warning("[WEED] Error cargando plantas: " + e.getMessage());
            }
        });
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 200L, 200L); // cada 10s
    }

    public void stop() {
        if (task != null) task.cancel();
    }

    private long stageMs() { return growSeconds * 1000L / 3L; }
    private long deathAt(WeedPlant p) { return p.plantedAt + (growSeconds + neglectSeconds) * 1000L; }
    private int computeStage(WeedPlant p, long now) { return (int) Math.min(3, (now - p.plantedAt) / stageMs()); }
    public boolean isMature(WeedPlant p) { return computeStage(p, System.currentTimeMillis()) >= 3; }

    private void tick() {
        long now = System.currentTimeMillis();
        for (WeedPlant p : new ArrayList<>(byId.values())) {
            if (!"GROWING".equals(p.state)) continue;
            if (now > deathAt(p)) { kill(p); continue; }
            int stage = computeStage(p, now);
            if (stage != p.stage) {
                p.stage = stage;
                final WeedPlant fp = p;
                dbExecutor.execute(() -> { try { dao.updateState(fp.id, fp.stage, "GROWING"); } catch (Exception ignored) {} });
            }
            World w = Bukkit.getWorld(p.world);
            if (w == null || !w.isChunkLoaded(p.x >> 4, p.z >> 4)) continue;
            Block b = w.getBlockAt(p.x, p.y, p.z);
            Material t = b.getType();
            if (stage >= 3 && t == PLANT) {
                // MADURA: se convierte en una mata frondosa (helecho grande = aspecto de maria, y sin
                // interaccion vanilla -> imposible "ordenar" bayas). Si no cabe el doble, helecho simple.
                Block up = b.getRelative(0, 1, 0);
                if (up.getType() == Material.AIR || up.getType() == Material.CAVE_AIR) {
                    b.setType(Material.LARGE_FERN, false);
                    if (b.getBlockData() instanceof org.bukkit.block.data.Bisected lower) {
                        lower.setHalf(org.bukkit.block.data.Bisected.Half.BOTTOM);
                        b.setBlockData(lower, false);
                    }
                    up.setType(Material.LARGE_FERN, false);
                    if (up.getBlockData() instanceof org.bukkit.block.data.Bisected upper) {
                        upper.setHalf(org.bukkit.block.data.Bisected.Half.TOP);
                        up.setBlockData(upper, false);
                    }
                } else {
                    b.setType(Material.FERN, false);
                }
                t = b.getType();
            } else if (t == PLANT) {
                BlockData bd = b.getBlockData();
                if (bd instanceof Ageable age && age.getAge() != stage) {
                    age.setAge(Math.min(age.getMaximumAge(), Math.min(2, stage)));
                    b.setBlockData(age, false);
                }
            }
            if (t == PLANT || t == Material.FERN || t == Material.LARGE_FERN) {
                // particulas pasivas (la policia "huele"): mas en floracion/cosecha
                w.spawnParticle(Particle.COMPOSTER, b.getLocation().add(0.5, 0.4, 0.5), 3 + stage * 3, 0.25, 0.3, 0.25, 0.0);
            }
        }
    }

    private void kill(WeedPlant p) {
        byId.remove(p.id);
        byLoc.remove(p.locKey());
        p.state = "DEAD";
        Bukkit.getScheduler().runTask(plugin, () -> {
            World w = Bukkit.getWorld(p.world);
            if (w != null && w.isChunkLoaded(p.x >> 4, p.z >> 4)) {
                Block b = w.getBlockAt(p.x, p.y, p.z);
                Material t = b.getType();
                if (t == PLANT || t == Material.FERN || t == Material.LARGE_FERN) {
                    Block up = b.getRelative(0, 1, 0);
                    if (up.getType() == Material.LARGE_FERN) up.setType(Material.AIR, false);
                    b.setType(Material.DEAD_BUSH, false);
                }
            }
        });
        dbExecutor.execute(() -> { try { dao.updateState(p.id, p.stage, "DEAD"); } catch (Exception ignored) {} });
    }

    // ─── plantar ─────────────────────────────────────────────
    public boolean plant(Player player, Block clicked) {
        if (jobs.getLevel(player.getUniqueId(), "farmer") < unlockLevel) {
            player.sendMessage(ChatColor.RED + "[Marihuana] Necesitas Agricultura nivel " + unlockLevel + " para cultivar esto.");
            return false;
        }
        Block target = clicked.getRelative(0, 1, 0);
        if (target.getType() != Material.AIR && target.getType() != Material.CAVE_AIR) {
            player.sendMessage(ChatColor.RED + "[Marihuana] No hay espacio para plantar aqui.");
            return false;
        }
        // Solo en TIERRA de verdad (nada de apilar plantas ni cultivar sobre bloques raros).
        Material soil = clicked.getType();
        if (soil != Material.GRASS_BLOCK && soil != Material.DIRT && soil != Material.COARSE_DIRT
                && soil != Material.PODZOL && soil != Material.ROOTED_DIRT && soil != Material.FARMLAND) {
            player.sendMessage(ChatColor.RED + "[Marihuana] Esto solo agarra en tierra. Busca un buen suelo.");
            return false;
        }
        // Espaciado: nada de plantar pegado a otra planta (anti-stack y anti-granjas compactas).
        for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
            String k = target.getWorld().getName() + ":" + (target.getX() + dx) + ":" + (target.getY() + dy) + ":" + (target.getZ() + dz);
            if (byLoc.containsKey(k)) {
                player.sendMessage(ChatColor.RED + "[Marihuana] Demasiado pegada a otra planta. Dales aire, que se ahogan.");
                return false;
            }
        }
        // Limite de plantas tuyas en la zona segun tu nivel de Agricultura.
        int level = jobs.getLevel(player.getUniqueId(), "farmer");
        int maxNearby = limitBase + (level / limitPerLevels);
        int mine = 0;
        String myId = player.getUniqueId().toString();
        for (WeedPlant other : byId.values()) {
            if (!"GROWING".equals(other.state) || !other.owner.equals(myId)) continue;
            if (!other.world.equals(target.getWorld().getName())) continue;
            long ddx = other.x - target.getX(), ddz = other.z - target.getZ();
            if (ddx * ddx + ddz * ddz <= (long) limitRadius * limitRadius) mine++;
        }
        if (mine >= maxNearby) {
            player.sendMessage(ChatColor.RED + "[Marihuana] Ya tienes " + mine + " plantas por aqui. Con Agricultura "
                    + level + " tu plantacion da para " + maxNearby + " por zona. Sube de nivel o cultiva mas lejos.");
            return false;
        }
        if (!canPlantAt(target.getLocation())) {
            player.sendMessage(ChatColor.RED + "[Marihuana] Aqui no puedes cultivar. Busca una zona de cultivo (es arriesgado llegar).");
            return false;
        }
        target.setType(PLANT, false);
        BlockData bd = target.getBlockData();
        if (bd instanceof Ageable age) { age.setAge(0); target.setBlockData(age, false); }

        WeedPlant p = new WeedPlant(UUID.randomUUID().toString(), player.getUniqueId().toString(),
                target.getWorld().getName(), target.getX(), target.getY(), target.getZ(),
                System.currentTimeMillis(), 0, "GROWING");
        byId.put(p.id, p);
        byLoc.put(p.locKey(), p);
        dbExecutor.execute(() -> { try { dao.insert(p); } catch (Exception e) { plugin.getLogger().warning("[WEED] insert: " + e.getMessage()); } });

        player.sendMessage(ChatColor.GREEN + "[Marihuana] Plantada. Madura en ~" + (growSeconds / 60) + " min. ¡Cuidado con la policia!");
        return true;
    }

    private boolean canPlantAt(Location loc) {
        if (!requireZone && zones.isEmpty()) return true;
        try {
            java.util.Set<String> ids = NemelesApi.regions().regionIdsAt(loc);
            for (String z : zones) if (ids.contains(z)) return true;
            return false;
        } catch (Throwable t) {
            return !requireZone;
        }
    }

    // ─── cosechar (al romper el bloque) ──────────────────────
    public boolean isPlant(Location loc) { return byLoc.containsKey(WeedPlant.locKey(loc)); }

    public void harvest(Player breaker, Block block) {
        WeedPlant p = byLoc.remove(WeedPlant.locKey(block.getLocation()));
        if (p == null) return;
        byId.remove(p.id);
        caring.remove(p.id);
        dbExecutor.execute(() -> { try { dao.delete(p.id); } catch (Exception ignored) {} });
        // si era la mata doble, limpiar la mitad de arriba
        Block up = block.getRelative(0, 1, 0);
        if (up.getType() == Material.LARGE_FERN) up.setType(Material.AIR, false);
        if (isMature(p)) {
            // Sin cuidados, la planta puede estar PODRIDA por dentro (riesgo real de no fertilizar).
            if (!p.cared && random.nextDouble() < rotChance) {
                breaker.sendMessage(ChatColor.DARK_RED + "[Marihuana] La abres y... podrida por dentro. "
                        + "Nadie la fertilizó. El campo no perdona a los vagos.");
                return;
            }
            int buds = budsMin + random.nextInt(Math.max(1, budsMax - budsMin + 1));
            if (p.quality == 1) {
                buds += 1;   // bien fertilizada: cosecha gorda
            } else if (p.quality == -1 && random.nextDouble() < 0.7) {
                buds = 1;    // mezcla fallida: casi todo se echo a perder
            }
            try {
                double extra = NemelesApi.skills().perkValue(breaker.getUniqueId(), "farmer", "weed.bonus_buds");
                if (extra > 0 && random.nextDouble() < extra) buds += 1;   // perk Cosecha Premium
            } catch (Throwable ignored) { }
            breaker.getInventory().addItem(items.bud(buds));
            jobs.grantXp(breaker, "farmer", harvestXpPerBud * buds);
            String note = p.quality == 1 ? " La fertilizaste bien y se nota." :
                          p.quality == -1 ? " La mezcla fallida pasó factura." : "";
            breaker.sendMessage(ChatColor.GREEN + "[Marihuana] Cosechaste " + buds + " cogollo(s)." + ChatColor.GRAY + note);
        } else {
            breaker.sendMessage(ChatColor.GRAY + "[Marihuana] La planta no estaba madura; se perdio.");
        }
    }

    // ─── cuidado: fertilizante con MINIJUEGO ─────────────────
    /**
     * Clic derecho con POLVO DE HUESO sobre tu planta: empiezas a mezclar el fertilizante y en
     * unos segundos llega el "¡AHORA! ¡AGACHATE!". Si te agachas a tiempo: +1 cogollo asegurado.
     * Si fallas: la mezcla se estropea (probablemente 1 solo cogollo). Sin fertilizar: puede pudrirse.
     */
    public void care(Player player, Block block) {
        WeedPlant p = byLoc.get(WeedPlant.locKey(block.getLocation()));
        if (p == null || !"GROWING".equals(p.state)) return;
        if (!p.owner.equals(player.getUniqueId().toString())) {
            player.sendMessage(ChatColor.RED + "[Marihuana] Esa planta no es tuya. Tocar la mercancia ajena acorta la vida.");
            return;
        }
        if (p.cared) { player.sendMessage(ChatColor.GRAY + "[Marihuana] Esa ya está fertilizada. No la agobies."); return; }
        if (isMature(p)) { player.sendMessage(ChatColor.GRAY + "[Marihuana] Ya está madura: cosecha y punto."); return; }
        if (!caring.add(p.id)) return;   // minijuego ya en curso

        // consumir 1 polvo de hueso (el listener ya comprobo que lo lleva en la mano)
        org.bukkit.inventory.ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getType() != Material.BONE_MEAL) { caring.remove(p.id); return; }
        hand.setAmount(hand.getAmount() - 1);

        player.sendMessage(ChatColor.YELLOW + "[Marihuana] Mezclas el fertilizante... no le quites el ojo. "
                + ChatColor.GOLD + "Cuando grite ¡AHORA!, AGÁCHATE.");
        try { player.playSound(player.getLocation(), "item.bone_meal.use", 1f, 0.9f); } catch (Throwable ignored) { }

        long delay = 30L + random.nextInt(50);   // 1.5s a 4s: que no sea mecanico
        final String pid = p.id;
        final java.util.UUID uid = player.getUniqueId();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player pl = Bukkit.getPlayer(uid);
            if (pl == null) { caring.remove(pid); return; }
            pl.sendMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "¡AHORA! ¡AGÁCHATE!");
            try { pl.playSound(pl.getLocation(), "block.note_block.pling", 1f, 1.9f); } catch (Throwable ignored) { }
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                caring.remove(pid);
                WeedPlant plant = byId.get(pid);
                Player pl2 = Bukkit.getPlayer(uid);
                if (plant == null || pl2 == null) return;
                boolean ok = pl2.isSneaking();
                plant.cared = true;
                plant.quality = ok ? 1 : -1;
                dbExecutor.execute(() -> { try { dao.updateCare(pid, true, ok ? 1 : -1); } catch (Exception ignored) { } });
                if (ok) {
                    pl2.sendMessage(ChatColor.GREEN + "[Marihuana] Mezcla perfecta. La planta lo agradece: cosecha gorda asegurada.");
                    try { pl2.playSound(pl2.getLocation(), "entity.player.levelup", 0.7f, 1.5f); } catch (Throwable ignored) { }
                } else {
                    pl2.sendMessage(ChatColor.RED + "[Marihuana] Tarde. La mezcla se te fue de las manos: esa planta dará pena.");
                    try { pl2.playSound(pl2.getLocation(), "block.note_block.bass", 0.8f, 0.5f); } catch (Throwable ignored) { }
                }
            }, 16L);   // ~0.8s de ventana para agacharse
        }, delay);
    }

    // ─── procesar / vender / comprar ─────────────────────────
    public void process(Player player) {
        int buds = count(player, "bud");
        int bags = buds / processRatio;
        if (bags < 1) {
            player.sendMessage(ChatColor.RED + "[Marihuana] Necesitas al menos " + processRatio + " cogollos para 1 bolsa.");
            return;
        }
        remove(player, "bud", bags * processRatio);
        player.getInventory().addItem(items.bag(bags));
        player.sendMessage(ChatColor.GREEN + "[Marihuana] Procesaste " + bags + " bolsa(s).");
    }

    public void sell(Player player) {
        int bags = count(player, "bag");
        if (bags < 1) {
            player.sendMessage(ChatColor.RED + "[Marihuana] No tienes bolsas que vender.");
            return;
        }
        remove(player, "bag", bags);
        // PRECIO DINÁMICO: el mercado server-wide de maría satura con cada venta del día (modula el
        // precio base; nunca lo sustituye). Se aplica ANTES del peaje territorial.
        double mult = 1.0;
        try { mult = NemelesApi.heat().priceMultiplier("weed"); } catch (Throwable ignored) { }
        long grossCents = Math.round(bags * bagPrice * mult * 100.0);
        final BigDecimal grossAmt = BigDecimal.valueOf(grossCents).movePointLeft(2);
        final double fmult = mult;
        final int fbags = bags;
        final org.bukkit.Location loc = player.getLocation();
        // 1) cobra el BRUTO al vendedor. 2) SOLO si tiene exito, aplica el peaje de territorio (transfer
        //    atomico del vendedor al banco de la mafia duena). Asi el peaje NUNCA se cobra sin venta
        //    confirmada (antes era fire-and-forget antes del deposito -> podia crear dinero).
        NemelesApi.economy().deposit(player.getUniqueId(), MoneyType.SUCIO, grossAmt, "weed:sell")
                .thenAccept(res -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (res != null && res.success()) {
                        long net = grossCents;
                        try {
                            net = NemelesApi.territories().applyDrugTax(player.getUniqueId(), loc, grossCents);
                        } catch (Throwable ignored) {
                            // nemeles-territories no cargado: venta sin peaje
                        }
                        long taxedCents = grossCents - net;
                        String extra = taxedCents > 0
                                ? ChatColor.GRAY + " (peaje de territorio: $" + BigDecimal.valueOf(taxedCents).movePointLeft(2).toPlainString() + ")"
                                : "";
                        player.sendMessage(ChatColor.GREEN + "[Mercado negro] Vendiste " + fbags + " bolsa(s) por "
                                + ChatColor.RED + "$" + BigDecimal.valueOf(net).movePointLeft(2).toPlainString() + " sucios" + extra
                                + ChatColor.GREEN + ". Lávalo para gastarlo.");
                        if (fmult < 0.85) {
                            player.sendMessage(ChatColor.GRAY + "[Mercado negro] La calle está inundada de maría: "
                                    + "hoy pagan menos. Deja que baje la oferta y vuelve mañana.");
                        }
                        try {
                            // vender droga "quema": sube el nivel de busqueda (si el modulo policia esta cargado)
                            NemelesApi.wanted().addCrime(player.getUniqueId(), 5 * fbags, "weed:sell", loc, false);
                        } catch (Throwable ignored) {
                            // nemeles-police no cargado: la venta sigue funcionando sin subir wanted
                        }
                        // ...y deja CALOR de fondo + SATURA el mercado server-wide (modulo heat opcional)
                        try { NemelesApi.heat().onDrugSale(player.getUniqueId(), "weed", fbags); } catch (Throwable ignored) { }
                    } else {
                        player.getInventory().addItem(items.bag(fbags)); // devolver si fallo
                        player.sendMessage(ChatColor.RED + "[Mercado negro] La venta fallo, te devuelvo la mercancia.");
                    }
                }));
    }

    public void buySeed(Player player, int n) {
        int amount = Math.max(1, n);
        double cost = amount * seedPrice;
        NemelesApi.economy().withdraw(player.getUniqueId(), MoneyType.EFECTIVO, BigDecimal.valueOf(cost), "weed:buy-seed")
                .thenAccept(res -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (res != null && res.success()) {
                        player.getInventory().addItem(items.seed(amount));
                        player.sendMessage(ChatColor.GREEN + "[Mercado negro] Compraste " + amount + " semilla(s) por $" + cost + ".");
                    } else {
                        player.sendMessage(ChatColor.RED + "[Mercado negro] Fondos insuficientes (necesitas $" + cost + " en efectivo).");
                    }
                }));
    }

    // ─── deteccion policial ──────────────────────────────────
    public void detect(CommandSender sender, Location center) {
        int found = 0;
        org.bukkit.World cw = center.getWorld();
        if (cw == null) { sender.sendMessage(ChatColor.RED + "Mundo no disponible para el rastreo."); return; }
        String cwName = cw.getName();
        sender.sendMessage(ChatColor.AQUA + "== Rastreo de plantaciones (radio " + detectRadius + ") ==");
        for (WeedPlant p : byId.values()) {
            if (!p.world.equals(cwName)) continue;
            double dx = p.x - center.getX(), dz = p.z - center.getZ();
            double dist = Math.sqrt(dx * dx + dz * dz);
            double mult = 0.0;
            try { mult = NemelesApi.skills().perkValue(java.util.UUID.fromString(p.owner), "farmer", "weed.detect_radius_mult"); }
            catch (Throwable ignored) { }
            double eff = detectRadius * (1.0 + Math.max(mult, -0.5)); // perk Cultivo Camuflado (cap -50%)
            if (dist <= eff) {
                sender.sendMessage(ChatColor.GRAY + " - " + ChatColor.WHITE + "x" + p.x + " y" + p.y + " z" + p.z
                        + ChatColor.GRAY + " (" + (int) dist + "m, fase " + computeStage(p, System.currentTimeMillis()) + "/3)");
                found++;
            }
        }
        if (found == 0) sender.sendMessage(ChatColor.GRAY + "No se detectan plantaciones cerca.");
    }

    // ─── helpers de inventario ───────────────────────────────
    private int count(Player player, String type) {
        int total = 0;
        for (ItemStack it : player.getInventory().getContents()) {
            if (items.is(it, type)) total += it.getAmount();
        }
        return total;
    }

    private void remove(Player player, String type, int amount) {
        int left = amount;
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length && left > 0; i++) {
            ItemStack it = contents[i];
            if (!items.is(it, type)) continue;
            int take = Math.min(left, it.getAmount());
            it.setAmount(it.getAmount() - take);
            left -= take;
            if (it.getAmount() <= 0) player.getInventory().setItem(i, null);
        }
    }
}
