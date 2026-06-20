package com.nemeles.combat;

import com.nemeles.core.api.NemelesApi;
import com.nemeles.core.api.combat.DownState;
import com.nemeles.core.api.combat.PlayerDownedEvent;
import com.nemeles.core.api.combat.PlayerRevivedEvent;
import com.nemeles.core.api.economy.MoneyType;
import com.nemeles.core.api.faction.Relation;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Derribo (downed), sangrado, reanimacion EMT, muerte real, permadeath e inmunidad. Corre en hilo principal. */
public final class DownedManager {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private final Plugin plugin;
    private final CombatConfig cfg;

    private final Map<UUID, DownedSession> sessions = new ConcurrentHashMap<>();
    /** Secuela post-revive (la cablea el plugin con el sistema medico): (revivido, estaba-estabilizado). */
    private java.util.function.BiConsumer<Player, Boolean> reviveAftermath;
    public void setReviveAftermath(java.util.function.BiConsumer<Player, Boolean> hook) { this.reviveAftermath = hook; }
    /** Curar el cuerpo entero al rescatar (lo cablea el plugin con BodyManager.healAll). */
    private java.util.function.Consumer<UUID> bodyHealer;
    public void setBodyHealer(java.util.function.Consumer<UUID> hook) { this.bodyHealer = hook; }
    private final Set<UUID> permadeath = ConcurrentHashMap.newKeySet();
    private final Map<UUID, DeathCtx> deathCtx = new ConcurrentHashMap<>();
    private final Map<UUID, Long> immuneUntil = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastDownAt = new ConcurrentHashMap<>();
    private final Map<String, Long> pairCooldown = new ConcurrentHashMap<>();

    public DownedManager(Plugin plugin, CombatConfig cfg) {
        this.plugin = plugin;
        this.cfg = cfg;
    }

    // ─── consultas ───────────────────────────────────────────
    public boolean isDowned(UUID u) { return sessions.containsKey(u); }
    public DownState stateOf(UUID u) { DownedSession s = sessions.get(u); return s == null ? DownState.ALIVE : s.state; }
    public int bleedoutSecondsLeft(UUID u) { DownedSession s = sessions.get(u); return s == null ? -1 : s.bleedoutLeft; }
    public int secondsDown(UUID u) {
        DownedSession s = sessions.get(u);
        return s == null ? -1 : (int) ((System.currentTimeMillis() - s.downedAt) / 1000L);
    }
    public boolean isImmune(UUID u) { Long t = immuneUntil.get(u); return t != null && t > System.currentTimeMillis(); }
    public void grantImmunity(UUID u, int seconds) { immuneUntil.put(u, System.currentTimeMillis() + seconds * 1000L); }
    public java.util.Set<UUID> downedPlayers() { return new java.util.HashSet<>(sessions.keySet()); }

    // ─── derribo ─────────────────────────────────────────────
    public boolean knockDown(Player p, UUID source, String cause) {
        UUID id = p.getUniqueId();
        if (sessions.containsKey(id)) return false;
        int secs = computeBleedout(p);
        DownedSession s = new DownedSession(id);
        s.source = source;
        s.cause = cause;
        s.bleedoutTotal = secs;
        s.bleedoutLeft = secs;
        s.downedAt = System.currentTimeMillis();
        s.downLoc = p.getLocation().clone();
        s.bar = Bukkit.createBossBar("§4DERRIBADO", BarColor.RED, BarStyle.SEGMENTED_10);
        s.bar.addPlayer(p);
        sessions.put(id, s);
        applyDownedState(p);
        // MEDIO CORAZÓN + INVULNERABLE: nunca llegas a 0 de vida, así el cliente (sobre todo Bedrock)
        // NO muestra la pantalla de muerte con el botón de reaparecer. Quedas "inmortal a 1/2 corazón".
        try { p.setHealth(1.0); } catch (IllegalArgumentException ignored) { }
        p.showTitle(Title.title(LEGACY.deserialize("§4DERRIBADO"), LEGACY.deserialize("§7Pide ayuda (EMT) o §f/rendirse")));
        // MENSAJE de chat (el título/bossbar pueden no verse bien en Bedrock; el chat SÍ): qué pasa y qué hacer.
        p.sendMessage(LEGACY.deserialize("§4§l✖ ESTÁS DERRIBADO §r§c— te desangras (§f" + fmt(secs) + "§c)."));
        p.sendMessage(Component.text("» ").color(NamedTextColor.GRAY)
                .append(Component.text("[Intentar atenderte]").color(NamedTextColor.GREEN)
                        .clickEvent(ClickEvent.runCommand("/cuerpo"))
                        .hoverEvent(HoverEvent.showText(Component.text("Abre tu ficha en el suelo"))))
                .append(Component.text("   "))
                .append(Component.text("[Rendirse / acabar]").color(NamedTextColor.RED)
                        .clickEvent(ClickEvent.runCommand("/rendirse"))
                        .hoverEvent(HoverEvent.showText(Component.text("Te dejas morir del todo")))));
        p.sendMessage(LEGACY.deserialize("§8Si nadie viene en 5 min, una ambulancia te recoge (te cobra y pierdes algún objeto)."));
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_HURT, 1f, 0.6f);
        if (source != null) {
            Player src = Bukkit.getPlayer(source);
            if (src != null) src.sendActionBar(LEGACY.deserialize("§cDerribaste a " + p.getName()));
        }
        try { Bukkit.getPluginManager().callEvent(new PlayerDownedEvent(p, source)); } catch (Throwable ignored) { }
        return true;
    }

    public boolean forceDown(UUID uuid, UUID source, String cause) {
        Player p = Bukkit.getPlayer(uuid);
        return p != null && knockDown(p, source, cause);
    }

    private int computeBleedout(Player p) {
        int secs = cfg.bleedoutSeconds;
        try {
            var at = NemelesApi.territories().territoryAt(p.getLocation());
            if (at.isPresent() && NemelesApi.territories().isContested(at.get().id())) secs = cfg.bleedoutSecondsTurf;
        } catch (Throwable ignored) { }
        Long last = lastDownAt.get(p.getUniqueId());
        if (last != null && System.currentTimeMillis() - last < cfg.reboundWindowSeconds * 1000L) {
            secs = (int) Math.round(secs * cfg.reboundMultiplier);
        }
        lastDownAt.put(p.getUniqueId(), System.currentTimeMillis());
        return Math.max(5, secs);
    }

    private void applyDownedState(Player p) {
        if ("SWIMMING".equalsIgnoreCase(cfg.pose)) p.setSwimming(true);
        p.setSprinting(false);
        p.setWalkSpeed(0f);
        p.setFlySpeed(0f);
        // amplificador 4 (no 250): el walkSpeed manda — asi el ARRASTRE (crawl) es posible
        // cuando keepDownedPose le devuelve algo de velocidad. >=4 marca "slowness de downed".
        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, Integer.MAX_VALUE, 4, false, false, false));
        // CEGUERA del que se desangra (se ve igual en Bedrock via Geyser): la visión se va a negro.
        p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, Integer.MAX_VALUE, 0, false, false, false));
        try { p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, Integer.MAX_VALUE, 0, false, false, false)); } catch (Throwable ignored) { }
        p.setInvulnerable(true);   // nada de daño mientras estás caído (y el cliente no predice tu muerte)
    }

    private void restoreState(Player p, DownedSession s) {
        if (p.getVehicle() instanceof Player carrier) carrier.removePassenger(p);   // bajarlo del hombro
        p.setSwimming(false);
        p.setInvulnerable(false);
        p.removePotionEffect(PotionEffectType.SLOWNESS);
        p.removePotionEffect(PotionEffectType.BLINDNESS);
        try { p.removePotionEffect(PotionEffectType.DARKNESS); } catch (Throwable ignored) { }
        p.setWalkSpeed(0.2f);
        p.setFlySpeed(0.1f);
        if (s != null && s.bar != null) s.bar.removeAll();
    }

    // ─── tick (1s) ───────────────────────────────────────────
    public void tick() {
        // SEGURIDAD: limpia invulnerabilidad "pegada" (un derribado que no se restauró bien dejaba al
        // jugador invulnerable -> "no recibo daño"). Si no está derribado ni inmune ni en creativo, se quita.
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.isInvulnerable() && !sessions.containsKey(p.getUniqueId()) && !isImmune(p.getUniqueId())
                    && p.getGameMode() != org.bukkit.GameMode.CREATIVE && p.getGameMode() != org.bukkit.GameMode.SPECTATOR) {
                p.setInvulnerable(false);
            }
        }
        for (DownedSession s : new ArrayList<>(sessions.values())) {
            Player p = Bukkit.getPlayer(s.player);
            if (p == null) continue;                 // offline -> CombatConnectionListener
            keepDownedPose(p, s);
            spawnBleed(p);
            boolean transfusing = transfuseScan(p, s);
            if (!sessions.containsKey(s.player)) continue;
            boolean channeling = !transfusing && reviveScan(p, s);
            if (!sessions.containsKey(s.player)) continue;   // revivido durante el scan
            if ((!channeling && !transfusing) || !cfg.pauseBleedWhileChannel) {
                if (--s.bleedoutLeft <= 0) { expireBleedout(p, s); continue; }
                if (s.bleedoutLeft <= 5) p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASEDRUM, 1f, 0.5f);
            }
            updateBossbar(s);
        }
    }

    // ─── expiracion del bleedout (5 min sin que nadie te atienda) ──────
    /**
     * Se acabo el tiempo. El destino depende de DONDE estes (diseño del server):
     *  - ZONA NEGRA (permadeath): tu personaje muere PARA SIEMPRE (wipe).
     *  - resto (zona segura/normal): TRASLADO AUTOMATICO al hospital — te cobran un % de tu dinero,
     *    pierdes algunos objetos al azar por las prisas, y despiertas SANO. No mueres de verdad.
     */
    private void expireBleedout(Player p, DownedSession s) {
        boolean perma = false;
        try { perma = NemelesApi.regions().isPermadeathZone(p.getLocation()); } catch (Throwable ignored) { }
        if (perma) {
            markPermadeath(s.player, s.source);
            finishOff(s.player, "BLEEDOUT");
            return;
        }
        hospitalRescue(p, s);
    }

    /** Rescate hospitalario: cobra %, confisca objetos al azar, cura el cuerpo y revive sano. */
    private void hospitalRescue(Player p, DownedSession s) {
        sessions.remove(s.player);
        restoreState(p, s);
        UUID id = p.getUniqueId();

        // 1) factura: % del dinero que lleve encima (EFECTIVO + BANCO)
        if (cfg.rescueFeePercent > 0) {
            for (MoneyType mt : new MoneyType[]{MoneyType.EFECTIVO, MoneyType.BANCO}) {
                try {
                    NemelesApi.economy().balance(id, mt).thenAccept(bal -> {
                        if (bal == null || bal.signum() <= 0) return;
                        BigDecimal fee = bal.multiply(BigDecimal.valueOf(cfg.rescueFeePercent))
                                .movePointLeft(2).setScale(2, java.math.RoundingMode.HALF_UP);
                        if (fee.signum() > 0) {
                            try { NemelesApi.economy().withdraw(id, mt, fee, "hospital:rescue"); } catch (Throwable ignored) { }
                        }
                    });
                } catch (Throwable ignored) { }
            }
        }

        // 2) confiscacion: N objetos al azar del inventario (las prisas del traslado)
        int lost = 0;
        var inv = p.getInventory();
        java.util.List<Integer> withItems = new ArrayList<>();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack it = inv.getItem(i);
            if (it != null && it.getType() != org.bukkit.Material.AIR) withItems.add(i);
        }
        java.util.Collections.shuffle(withItems);
        for (int i = 0; i < Math.min(cfg.rescueItemsLost, withItems.size()); i++) {
            inv.setItem(withItems.get(i), null);
            lost++;
        }

        // 3) curar el cuerpo entero + vida llena + teleport al hospital/spawn
        if (bodyHealer != null) { try { bodyHealer.accept(id); } catch (Throwable ignored) { } }
        try {
            var attr = p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
            p.setHealth(attr != null ? attr.getValue() : 20.0);
            p.setFoodLevel(20);
        } catch (Throwable ignored) { }
        grantImmunity(id, cfg.respawnImmunitySeconds);
        if (cfg.rescueLocation != null) { try { p.teleport(cfg.rescueLocation); } catch (Throwable ignored) { } }
        else { try { p.teleport(p.getWorld().getSpawnLocation()); } catch (Throwable ignored) { } }

        p.playSound(p.getLocation(), Sound.BLOCK_BREWING_STAND_BREW, 1f, 1.2f);
        p.sendTitle("§c✚ Te encontraron a tiempo", "§7Despiertas en el hospital de Bahía Negra", 10, 70, 20);
        p.sendMessage(LEGACY.deserialize("§c[Hospital] §7Una ambulancia te recogió inconsciente. "
                + "Te cobran §f" + (int) Math.round(cfg.rescueFeePercent) + "%§7 de lo que llevabas encima"
                + (lost > 0 ? " y en el traslado se perdieron §f" + lost + "§7 objeto(s)" : "")
                + ". Pero estás vivo y entero."));
        try { Bukkit.getPluginManager().callEvent(new PlayerRevivedEvent(p, p)); } catch (Throwable ignored) { }
    }

    // ─── transfusion (bolsa de sangre): estabiliza y permite TRASLADO ──
    /** Agachado junto al derribado con una BOLSA DE SANGRE en la mano = transfusion. */
    private boolean transfuseScan(Player downed, DownedSession s) {
        if (s.transfusedTotal >= cfg.stabilizeMaxBonusSeconds) return false;
        Player t = findTransfuser(downed, s);
        if (t == null) {
            if (s.transfuser != null) { s.transfuser = null; s.transfuseTicks = 0; }
            return false;
        }
        if (!t.getUniqueId().equals(s.transfuser)) { s.transfuser = t.getUniqueId(); s.transfuseTicks = 0; }
        s.transfuseTicks++;
        int needed = Math.max(1, cfg.transfuseChannelSeconds);
        int pct = (int) Math.min(100, Math.round(100.0 * s.transfuseTicks / needed));
        t.sendActionBar(LEGACY.deserialize("§cTransfusión a " + downed.getName() + "... " + pct + "%"));
        downed.sendActionBar(LEGACY.deserialize("§cTe están poniendo sangre... " + pct + "%"));
        if (s.transfuseTicks >= needed) completeTransfusion(downed, t, s);
        return true;
    }

    private Player findTransfuser(Player downed, DownedSession s) {
        for (Player r : downed.getWorld().getPlayers()) {
            if (r.getUniqueId().equals(s.player)) continue;
            if (sessions.containsKey(r.getUniqueId())) continue;
            if (!r.isSneaking()) continue;
            if (r.getLocation().distanceSquared(downed.getLocation()) > cfg.reviveRadius * cfg.reviveRadius) continue;
            if (!MedItems.is(r.getInventory().getItemInMainHand(), MedItems.SANGRE)) continue;
            return r;
        }
        return null;
    }

    private void completeTransfusion(Player downed, Player t, DownedSession s) {
        s.transfuseTicks = 0;
        s.transfuser = null;
        ItemStack hand = t.getInventory().getItemInMainHand();
        if (MedItems.is(hand, MedItems.SANGRE)) hand.setAmount(hand.getAmount() - 1);
        int bonus = Math.min(cfg.stabilizeBonusSeconds, cfg.stabilizeMaxBonusSeconds - s.transfusedTotal);
        s.transfusedTotal += bonus;
        s.bleedoutLeft += bonus;
        s.bleedoutTotal = Math.max(s.bleedoutTotal, s.bleedoutLeft);
        boolean first = !s.stabilized;
        s.stabilized = true;
        if (s.bar != null) s.bar.setColor(BarColor.YELLOW);
        downed.getWorld().spawnParticle(Particle.HEART, downed.getLocation().add(0, 1, 0), 5, 0.3, 0.3, 0.3, 0.0);
        downed.playSound(downed.getLocation(), Sound.BLOCK_BREWING_STAND_BREW, 1f, 0.8f);
        t.sendActionBar(LEGACY.deserialize("§aTransfusión completada: +" + bonus + "s"));
        downed.sendMessage(LEGACY.deserialize("§e[EMT] " + t.getName() + " te ha puesto sangre: §a+" + bonus + "s§e."
                + (first ? " Estás ESTABLE: puedes arrastrarte para que te trasladen." : "")));
        try { NemelesApi.skills().grantXp(t, "medic", cfg.transfuseXp); } catch (Throwable ignored) { }
    }

    /** /rendirse con espera minima: los primeros segundos son la ventana de rescate. */
    public boolean trySurrender(Player p) {
        DownedSession s = sessions.get(p.getUniqueId());
        if (s == null) return false;
        int down = secondsDown(p.getUniqueId());
        if (down >= 0 && down < cfg.surrenderMinSeconds) {
            p.sendMessage(LEGACY.deserialize("§7Aguanta un poco: podrás rendirte en §f"
                    + (cfg.surrenderMinSeconds - down) + "s§7. Puede que alguien venga a por ti."));
            return true;
        }
        finishOff(p.getUniqueId(), "SURRENDER");
        return true;
    }

    private void keepDownedPose(Player p, DownedSession s) {
        // CARGADO A HOMBROS: va de pasajero de otro jugador -> nada de pose ni confinamiento,
        // y el porteador camina LENTO (cargar un cuerpo no es trotar).
        if (p.getVehicle() instanceof Player carrier) {
            s.downLoc = p.getLocation().clone();
            carrier.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 0, false, false));
            carrier.setSprinting(false);
            return;
        }
        if ("SWIMMING".equalsIgnoreCase(cfg.pose) && !p.isSwimming()) p.setSwimming(true);
        // ARRASTRE (patron FiveM/wasabi): los primeros segundos puedes reptar a cubierto;
        // luego te quedas clavado salvo que te ESTABILICEN con una transfusion (traslado).
        boolean crawl = s.stabilized
                || (System.currentTimeMillis() - s.downedAt) < cfg.crawlWindowSeconds * 1000L;
        float speed = crawl ? cfg.crawlSpeed : 0f;
        if (p.getWalkSpeed() != speed) p.setWalkSpeed(speed);
        if (crawl) {
            s.downLoc = p.getLocation().clone();   // la "posicion fija" te sigue mientras reptas
        } else if (s.downLoc != null && p.getWorld().equals(s.downLoc.getWorld())
                && p.getLocation().distanceSquared(s.downLoc) > cfg.freezeRadius * cfg.freezeRadius) {
            p.teleport(s.downLoc);
        }
    }

    private void spawnBleed(Player p) {
        p.getWorld().spawnParticle(Particle.DUST, p.getLocation().add(0, 0.4, 0), 10, 0.3, 0.1, 0.3,
                new Particle.DustOptions(Color.RED, 1.4f));
    }

    private void updateBossbar(DownedSession s) {
        if (s.bar == null) return;
        double prog = s.bleedoutTotal <= 0 ? 0 : Math.max(0, Math.min(1.0, s.bleedoutLeft / (double) s.bleedoutTotal));
        s.bar.setProgress(prog);
        String tail = s.state == DownState.BEING_REVIVED ? " §a(reanimando)"
                : s.transfuser != null ? " §e(transfusión)"
                : s.stabilized ? " §e(estable: arrástrate)" : " §7sangrando";
        s.bar.setTitle((s.stabilized ? "§6DERRIBADO" : "§4DERRIBADO") + " §7· §c" + fmt(s.bleedoutLeft) + tail);
    }

    // ─── reanimacion ─────────────────────────────────────────
    private boolean reviveScan(Player downed, DownedSession s) {
        Player rev = findReviver(downed, s);
        if (rev == null) {
            if (s.state == DownState.BEING_REVIVED) { s.state = DownState.DOWNED; s.channelTicks = 0; s.reviver = null; }
            return false;
        }
        s.state = DownState.BEING_REVIVED;
        s.reviver = rev.getUniqueId();
        s.channelTicks++;
        int needed = channelTicksFor(rev);
        int pct = (int) Math.min(100, Math.round(100.0 * s.channelTicks / needed));
        rev.sendActionBar(LEGACY.deserialize("§eReanimando a " + downed.getName() + "... " + pct + "%"));
        downed.sendActionBar(LEGACY.deserialize("§aTe están reanimando... " + pct + "%"));
        if (s.channelTicks >= needed) completeRevive(downed, rev, s);
        return true;
    }

    private Player findReviver(Player downed, DownedSession s) {
        for (Player r : downed.getWorld().getPlayers()) {
            if (r.getUniqueId().equals(s.player)) continue;
            if (sessions.containsKey(r.getUniqueId())) continue;
            if (!r.isSneaking()) continue;
            if (r.getLocation().distanceSquared(downed.getLocation()) > cfg.reviveRadius * cfg.reviveRadius) continue;
            if (!isLookingAt(r, downed)) continue;
            if (!canRevive(r, downed)) continue;
            return r;
        }
        return null;
    }

    /** Reanimacion manual por comando: el downed al que mira el reviver. */
    public boolean tryManualRevive(Player r) {
        DownedSession best = null;
        double bestD = Double.MAX_VALUE;
        for (DownedSession s : sessions.values()) {
            Player d = Bukkit.getPlayer(s.player);
            if (d == null || !d.getWorld().equals(r.getWorld())) continue;
            double dist = d.getLocation().distanceSquared(r.getLocation());
            if (dist > cfg.reviveRadius * cfg.reviveRadius) continue;
            if (!isLookingAt(r, d)) continue;
            if (!canRevive(r, d)) continue;
            if (dist < bestD) { bestD = dist; best = s; }
        }
        if (best == null) return false;
        Player d = Bukkit.getPlayer(best.player);
        if (d == null) return false;
        completeRevive(d, r, best);
        return true;
    }

    public boolean revive(UUID target, UUID reviverId) {
        DownedSession s = sessions.get(target);
        if (s == null) return false;
        Player p = Bukkit.getPlayer(target);
        Player r = Bukkit.getPlayer(reviverId);
        if (p == null || r == null) return false;
        completeRevive(p, r, s);
        return true;
    }

    private void completeRevive(Player downed, Player rev, DownedSession s) {
        sessions.remove(s.player);
        restoreState(downed, s);
        double healBonus = 0.0;
        try { healBonus = NemelesApi.skills().perkValue(rev.getUniqueId(), "medic", "revive.heal_bonus"); } catch (Throwable ignored) { }
        double targetHp = Math.max(1.0, Math.min(20.0, cfg.reviveHealth + healBonus));   // perk Primeros Auxilios
        try { downed.setHealth(targetHp); }
        catch (IllegalArgumentException ex) { try { downed.setHealth(1.0); } catch (IllegalArgumentException ignored) { } }
        downed.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 100, 1, false, false, true));
        downed.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 160, 0, false, false, true));
        int immunity = cfg.postReviveImmunitySeconds;
        try { immunity += (int) Math.round(NemelesApi.skills().perkValue(rev.getUniqueId(), "medic", "revive.immunity_bonus")); }
        catch (Throwable ignored) { }    // perk Inmunidad
        immuneUntil.put(downed.getUniqueId(), System.currentTimeMillis() + (long) immunity * 1000L);
        boolean skipKit = false;
        try {
            double noKit = NemelesApi.skills().perkValue(rev.getUniqueId(), "medic", "revive.no_kit_chance");
            skipKit = noKit > 0 && Math.random() < noKit;   // perk Improvisar (no consume botiquín)
        } catch (Throwable ignored) { }
        if (cfg.requireItem && !skipKit) rev.getInventory().removeItem(new ItemStack(cfg.reviveItem, 1));

        String pairKey = rev.getUniqueId() + ":" + downed.getUniqueId();
        Long cd = pairCooldown.get(pairKey);
        boolean payable = (cd == null || cd <= System.currentTimeMillis());
        if (payable && cfg.reviveFeeCents > 0) {
            try {
                NemelesApi.economy().transfer(downed.getUniqueId(), MoneyType.EFECTIVO, rev.getUniqueId(), MoneyType.EFECTIVO,
                        BigDecimal.valueOf(cfg.reviveFeeCents).movePointLeft(2), "emt:fee");
            } catch (Throwable ignored) { }
            pairCooldown.put(pairKey, System.currentTimeMillis() + cfg.cooldownPairSeconds * 1000L);
        }
        downed.getWorld().spawnParticle(Particle.HEART, downed.getLocation().add(0, 1, 0), 8, 0.4, 0.4, 0.4, 0.0);
        downed.playSound(downed.getLocation(), Sound.BLOCK_BREWING_STAND_BREW, 1f, 1.4f);
        rev.sendActionBar(LEGACY.deserialize("§aReanimaste a " + downed.getName()));
        downed.sendMessage(LEGACY.deserialize("§a[EMT] " + rev.getName() + " te ha reanimado."));
        try { NemelesApi.skills().grantXp(rev, "medic", cfg.reviveXp); } catch (Throwable ignored) { }
        // SECUELA (wasabi-style): revivir SIN estabilizar con sangre deja el cuerpo tocado
        if (reviveAftermath != null) {
            try { reviveAftermath.accept(downed, s.stabilized); } catch (Throwable ignored) { }
        }
        try { Bukkit.getPluginManager().callEvent(new PlayerRevivedEvent(rev, downed)); } catch (Throwable ignored) { }
    }

    private boolean canRevive(Player r, Player downed) {
        if (cfg.requireItem && !r.getInventory().contains(cfg.reviveItem)) return false;
        if (cfg.sameFactionOrAllyOnly) {
            try {
                int fr = NemelesApi.factions().getFactionOf(r.getUniqueId()).map(f -> f.id()).orElse(-1);
                int fd = NemelesApi.factions().getFactionOf(downed.getUniqueId()).map(f -> f.id()).orElse(-1);
                if (fr >= 0 && fd >= 0 && fr != fd && NemelesApi.factions().relation(fr, fd) == Relation.ENEMY) return false;
            } catch (Throwable ignored) { }
        }
        return true;
    }

    private boolean isLookingAt(Player r, Player target) {
        Vector dir = r.getEyeLocation().getDirection().normalize();
        Vector to = target.getLocation().toVector().subtract(r.getEyeLocation().toVector());
        if (to.lengthSquared() < 1e-4) return true;
        return dir.dot(to.normalize()) > 0.55;
    }

    /** Tiempo de reanimacion (en ticks de 1s) segun el nivel de Medicina del reviver: base -> floor. */
    private int channelTicksFor(Player rev) {
        int lvl = 1;
        try { lvl = NemelesApi.skills().getLevel(rev.getUniqueId(), "medic"); } catch (Throwable ignored) { }
        int span = cfg.channelSeconds - cfg.channelFloorSeconds;
        int reduced = span <= 0 ? 0 : (int) Math.round(span * Math.min(100, Math.max(1, lvl)) / 100.0);
        int base = cfg.channelSeconds - reduced;
        try { base -= (int) Math.round(NemelesApi.skills().perkValue(rev.getUniqueId(), "medic", "revive.channel_bonus")); }
        catch (Throwable ignored) { }   // perk Manos Rápidas
        return Math.max(cfg.channelFloorSeconds, base);
    }

    // ─── muerte real ─────────────────────────────────────────
    public void finishOff(UUID uuid, String cause) {
        DownedSession s = sessions.remove(uuid);
        if (s == null) return;
        Player p = Bukkit.getPlayer(uuid);
        if (p == null) return;
        restoreState(p, s);
        deathCtx.put(uuid, new DeathCtx(s.source, cause, s.masked));
        try { p.setHealth(0.0); } catch (IllegalArgumentException ignored) { }
    }

    public void markPermadeath(UUID uuid, UUID source) {
        permadeath.add(uuid);
        deathCtx.put(uuid, new DeathCtx(source, "PERMADEATH", false));
    }

    public boolean consumePermadeath(UUID uuid) { return permadeath.remove(uuid); }
    public DeathCtx consumeDeathCtx(UUID uuid) { return deathCtx.remove(uuid); }

    /** Limpia restos de muerte pendiente (flag permadeath + contexto). Red de seguridad anti flag pegado. */
    public void clearPending(UUID uuid) { permadeath.remove(uuid); deathCtx.remove(uuid); }

    /** Restaura el estado de movimiento de un jugador (anti estado-pegado por quit/join estando derribado).
     *  Solo quita la RALENTIZACION del downed (amplificador >=4; el sistema medico usa 0-2), no pociones normales. */
    public void restorePlayer(Player p) {
        if (p == null) return;
        p.setSwimming(false);
        p.setInvulnerable(false);
        PotionEffect se = p.getPotionEffect(PotionEffectType.SLOWNESS);
        if (se != null && se.getAmplifier() >= 4) p.removePotionEffect(PotionEffectType.SLOWNESS);
        p.removePotionEffect(PotionEffectType.BLINDNESS);
        try { p.removePotionEffect(PotionEffectType.DARKNESS); } catch (Throwable ignored) { }
        if (p.getWalkSpeed() <= 0.1f) p.setWalkSpeed(0.2f);
        if (p.getFlySpeed() == 0f) p.setFlySpeed(0.1f);
    }

    /** Saca a un jugador del estado derribado y le restaura movimiento/visión (comando admin "sanar"). */
    public void clearDowned(Player p) {
        if (p == null) return;
        DownedSession s = sessions.remove(p.getUniqueId());
        if (s != null && s.bar != null) s.bar.removeAll();
        restoreState(p, s);
        clearPending(p.getUniqueId());
        immuneUntil.remove(p.getUniqueId());
        lastDownAt.remove(p.getUniqueId());
    }

    /** Jugadores online actualmente derribados (para penalizar en el apagado del servidor). */
    public java.util.List<Player> onlineDowned() {
        java.util.List<Player> out = new ArrayList<>();
        for (DownedSession s : sessions.values()) {
            Player p = Bukkit.getPlayer(s.player);
            if (p != null) out.add(p);
        }
        return out;
    }

    public DownedSession removeSession(UUID uuid) {
        DownedSession s = sessions.remove(uuid);
        if (s != null && s.bar != null) s.bar.removeAll();
        return s;
    }

    public void restoreAll() {
        for (DownedSession s : sessions.values()) {
            Player p = Bukkit.getPlayer(s.player);
            if (p != null) restoreState(p, s);
            else if (s.bar != null) s.bar.removeAll();
        }
        sessions.clear();
    }

    private static String fmt(int seconds) {
        int m = Math.max(0, seconds) / 60, s = Math.max(0, seconds) % 60;
        return m + ":" + (s < 10 ? "0" + s : s);
    }

    /** Contexto de muerte consumido por el CombatDeathListener. */
    public static final class DeathCtx {
        public final UUID source;
        public final String cause;
        public final boolean masked;
        public DeathCtx(UUID source, String cause, boolean masked) {
            this.source = source;
            this.cause = cause;
            this.masked = masked;
        }
    }
}
