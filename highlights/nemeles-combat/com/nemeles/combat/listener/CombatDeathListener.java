package com.nemeles.combat.listener;

import com.nemeles.combat.CombatConfig;
import com.nemeles.combat.CombatKeys;
import com.nemeles.combat.CombatTagManager;
import com.nemeles.combat.Contraband;
import com.nemeles.combat.DownedManager;
import com.nemeles.combat.GraveManager;
import com.nemeles.combat.db.DeathDao;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import com.nemeles.core.api.NemelesApi;
import com.nemeles.core.api.combat.PlayerKilledEvent;
import com.nemeles.core.api.economy.MoneyType;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;
import java.util.concurrent.Executor;

/** Muerte real: pierde % de SUCIO (banco a salvo), contrabando, wanted contextual al matador, log. */
public final class CombatDeathListener implements Listener {

    private final org.bukkit.plugin.Plugin plugin;
    private final CombatConfig cfg;
    private final CombatKeys keys;
    private final DownedManager downed;
    private final CombatTagManager tags;
    private final DeathDao deathDao;
    private final Executor dbExecutor;
    private final GraveManager grave;

    private com.nemeles.combat.render.GunModelService gunModel;   // modelo 3D del arma (opcional, soft-dep)
    public void setGunModel(com.nemeles.combat.render.GunModelService gunModel) { this.gunModel = gunModel; }

    public CombatDeathListener(org.bukkit.plugin.Plugin plugin, CombatConfig cfg, CombatKeys keys, DownedManager downed,
                               CombatTagManager tags, DeathDao deathDao, Executor dbExecutor, GraveManager grave) {
        this.plugin = plugin;
        this.cfg = cfg;
        this.keys = keys;
        this.downed = downed;
        this.tags = tags;
        this.deathDao = deathDao;
        this.dbExecutor = dbExecutor;
        this.grave = grave;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        if (gunModel != null) gunModel.cleanup(p);   // el arma se va a la tumba / se pierde: quitar su modelo 3D
        UUID id = p.getUniqueId();
        DownedManager.DeathCtx ctx = downed.consumeDeathCtx(id);
        boolean perma = downed.consumePermadeath(id);
        int pct = perma ? cfg.blackzoneDirtyPct : cfg.deathDirtyPct;
        Location loc = p.getLocation();

        // perdida de SUCIO (banco/limpio a salvo)
        try {
            NemelesApi.economy().balance(id, MoneyType.SUCIO).thenAccept(bal -> {
                long lostCents = 0;
                if (bal != null && bal.signum() > 0 && pct > 0) {
                    BigDecimal lose = bal.multiply(BigDecimal.valueOf(pct)).movePointLeft(2).setScale(2, RoundingMode.HALF_UP);
                    if (lose.signum() > 0) {
                        NemelesApi.economy().withdraw(id, MoneyType.SUCIO, lose, perma ? "death:blackzone" : "death:loss");
                        lostCents = lose.movePointRight(2).longValue();
                    }
                }
                insertLog(id, ctx, perma, lostCents, loc);
            });
        } catch (Throwable t) {
            insertLog(id, ctx, perma, 0, loc);
        }

        // inventario + contrabando
        String policy = perma ? cfg.blackzoneContrabandPolicy : cfg.contrabandPolicy;
        boolean wipe = perma && "WIPE_INVENTORY".equalsIgnoreCase(cfg.permadeathAction);
        if (wipe) {
            // zona negra = muerte permanente: se arrasa todo el inventario
            e.getDrops().clear();
            e.setKeepInventory(false);
            p.getInventory().clear();
        } else {
            // muerte normal: el contrabando es la penalización (sink/drop) y NO va a la tumba.
            boolean drop = "DROP".equalsIgnoreCase(policy);
            Contraband.strip(p, keys.contraband, drop);
            e.getDrops().clear();
            e.setKeepInventory(true);   // que vanilla no suelte ni despawnee nada
            // TUMBA: el resto del inventario va a un COFRE temporal en el sitio de la muerte; reapareces
            // vacío y lo recuperas (el cofre desaparece al vaciarlo). Así los items no se pierden/despawnean.
            if (grave != null) {
                java.util.List<ItemStack> loot = new java.util.ArrayList<>();
                var inv = p.getInventory();
                for (ItemStack it : inv.getStorageContents()) if (it != null && it.getType() != Material.AIR) loot.add(it.clone());
                for (ItemStack it : inv.getArmorContents()) if (it != null && it.getType() != Material.AIR) loot.add(it.clone());
                ItemStack off = inv.getItemInOffHand();
                if (off != null && off.getType() != Material.AIR) loot.add(off.clone());
                if (!loot.isEmpty()) {
                    grave.createGrave(loc, p.getName(), loot);
                    inv.clear();
                    inv.setArmorContents(null);
                    inv.setItemInOffHand(null);
                    p.sendMessage(org.bukkit.ChatColor.GRAY + "Tus cosas quedaron en una "
                            + org.bukkit.ChatColor.GOLD + "tumba" + org.bukkit.ChatColor.GRAY + " donde caíste. Ve a recuperarlas antes de que alguien las encuentre.");
                }
            }
        }

        awardWantedToKiller(p, ctx, perma);

        try {
            Bukkit.getPluginManager().callEvent(new PlayerKilledEvent(p,
                    ctx != null ? ctx.source : null, ctx != null ? ctx.cause : "DEATH", perma));
        } catch (Throwable ignored) { }
    }

    private void awardWantedToKiller(Player victim, DownedManager.DeathCtx ctx, boolean perma) {
        if (ctx == null || ctx.source == null || perma) return;     // zona negra = sin ley
        Player killer = Bukkit.getPlayer(ctx.source);
        if (killer == null) return;
        UUID vid = victim.getUniqueId();
        Location loc = victim.getLocation();

        // turf war activa entre mafias rivales -> 0
        try {
            var at = NemelesApi.territories().territoryAt(loc);
            if (at.isPresent() && NemelesApi.territories().isContested(at.get().id())) {
                int kf = factionId(killer.getUniqueId());
                int vf = factionId(vid);
                if (kf >= 0 && vf >= 0 && kf != vf) return;
            }
        } catch (Throwable ignored) { }

        // defensa propia: la victima fue el agresor -> 0
        if (tags.wasAggressor(vid, killer.getUniqueId())) return;

        // policia mata a buscado -> 0 (+ bounty opcional)
        boolean police = killer.hasPermission("nemeles.police.jail") || killer.hasPermission("nemeles.police.track");
        try {
            if (police && NemelesApi.wanted().getStars(vid) >= cfg.policeLegalKillStars) {
                if (cfg.bountyCents > 0) {
                    NemelesApi.economy().deposit(killer.getUniqueId(), MoneyType.EFECTIVO,
                            BigDecimal.valueOf(cfg.bountyCents).movePointLeft(2), "bounty:legal-kill");
                }
                return;
            }
        } catch (Throwable ignored) { }

        // crimen contextual
        try {
            int pts;
            String crime;
            if (NemelesApi.wanted().isWanted(vid)) {
                pts = cfg.crimeHomicideVigilante; crime = "homicide:vigilante";
                payWantedBounty(killer, vid);   // cazarrecompensas: sale del BOLSILLO del muerto (anti-grifo)
            }
            else if (isPolice(victim)) { pts = cfg.crimeHomicidePolice; crime = "homicide:police"; }
            else { pts = cfg.crimeHomicideCivilian; crime = "homicide:civilian"; }
            NemelesApi.wanted().addCrime(ctx.source, pts, crime, loc, ctx.masked);
        } catch (Throwable ignored) { }
    }

    private boolean isPolice(Player victim) {
        return victim.hasPermission("nemeles.police.jail") || victim.hasPermission("nemeles.police.track");
    }

    private int factionId(UUID u) {
        try { return NemelesApi.factions().getFactionOf(u).map(f -> f.id()).orElse(-1); }
        catch (Throwable t) { return -1; }
    }

    /**
     * Cazarrecompensas: paga al matador por eliminar a un buscado. Sale del BOLSILLO del muerto (su
     * EFECTIVO, hasta lo que tenga) via TRANSFER -> NO crea dinero (anti-grifo) y coludir solo mueve
     * dinero entre los dos colegas (no se puede granjear). No aplica entre misma faccion ni aliados.
     */
    private void payWantedBounty(Player killer, UUID vid) {
        if (cfg.bountyPerStarCents <= 0) return;
        final UUID killerId = killer.getUniqueId();
        if (sameFactionOrAlly(killerId, vid)) return;
        int stars;
        try { stars = NemelesApi.wanted().getStars(vid); } catch (Throwable t) { return; }
        if (stars < 1) return;
        final long want = cfg.bountyPerStarCents * stars;
        if (want <= 0) return;
        try {
            NemelesApi.economy().balance(vid, MoneyType.EFECTIVO).thenAccept(bal -> {
                long avail = (bal == null) ? 0L : bal.movePointRight(2).longValue();
                long pay = Math.min(want, avail);
                if (pay <= 0L) return;
                final BigDecimal money = BigDecimal.valueOf(pay).movePointLeft(2);
                NemelesApi.economy().transfer(vid, MoneyType.EFECTIVO, killerId, MoneyType.EFECTIVO, money, "bounty:wanted-kill")
                        .thenAccept(r -> {
                            if (r != null && r.success()) {
                                Bukkit.getScheduler().runTask(plugin, () -> {
                                    Player k = Bukkit.getPlayer(killerId);
                                    if (k != null) k.sendMessage(org.bukkit.ChatColor.GREEN + "[Recompensa] "
                                            + org.bukkit.ChatColor.WHITE + "+$" + money.toPlainString()
                                            + org.bukkit.ChatColor.GREEN + " del bolsillo del buscado que cazaste.");
                                    // Cronica a Discord (El Faro) para capturas notables (2+ estrellas).
                                    if (stars >= 2) {
                                        String hunter = (k != null) ? k.getName() : "Un cazarrecompensas";
                                        String prey = Bukkit.getOfflinePlayer(vid).getName();
                                        if (prey == null || prey.isBlank()) prey = "un buscado";
                                        com.nemeles.core.api.RpAnnounceEvent.fire("💰",
                                                hunter + " cobra una recompensa",
                                                "**" + hunter + "** cazo a **" + prey + "**, un buscado de **" + stars
                                                        + "★**, y se llevo la recompensa.",
                                                "cronica de mafias");
                                    }
                                });
                            }
                        });
            });
        } catch (Throwable ignored) { }
    }

    private boolean sameFactionOrAlly(UUID a, UUID b) {
        int fa = factionId(a), fb = factionId(b);
        if (fa < 0 || fb < 0) return false;
        if (fa == fb) return true;
        try { return NemelesApi.factions().relation(fa, fb) == com.nemeles.core.api.faction.Relation.ALLY; }
        catch (Throwable t) { return false; }
    }

    private void insertLog(UUID victim, DownedManager.DeathCtx ctx, boolean perma, long lostCents, Location loc) {
        UUID killer = ctx != null ? ctx.source : null;
        String cause = ctx != null ? ctx.cause : "DEATH";
        String world = loc.getWorld() != null ? loc.getWorld().getName() : null;
        int x = loc.getBlockX(), y = loc.getBlockY(), z = loc.getBlockZ();
        long at = System.currentTimeMillis();
        dbExecutor.execute(() -> {
            try { deathDao.insert(victim, killer, cause, perma, lostCents, world, x, y, z, at); }
            catch (Exception ignored) { }
        });
    }
}
