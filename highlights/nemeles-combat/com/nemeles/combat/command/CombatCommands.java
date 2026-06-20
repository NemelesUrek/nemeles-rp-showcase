package com.nemeles.combat.command;

import com.nemeles.combat.AmmoManager;
import com.nemeles.combat.DownedManager;
import com.nemeles.combat.GunRegistry;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.Locale;
import java.util.UUID;

/** /rendirse, /revivir, /emt, /arma (admin). */
public final class CombatCommands implements CommandExecutor {

    private final Plugin plugin;
    private final DownedManager downed;
    private final GunRegistry guns;
    private final AmmoManager ammo;
    private final java.util.Map<java.util.UUID, Long> surrendered = new java.util.concurrent.ConcurrentHashMap<>(); // /manosarriba: timestamp de fin
    private final java.util.Map<java.util.UUID, Long> robCd = new java.util.concurrent.ConcurrentHashMap<>();        // cooldown de /atracar
    private static final net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer LEGACY =
            net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection();

    public CombatCommands(Plugin plugin, DownedManager downed, GunRegistry guns, AmmoManager ammo) {
        this.plugin = plugin;
        this.downed = downed;
        this.guns = guns;
        this.ammo = ammo;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "rendirse" -> { return rendirse(sender); }
            case "revivir" -> { return revivir(sender); }
            case "emt" -> { return emt(sender); }
            case "manosarriba" -> { return manosArriba(sender); }
            case "atracar" -> { return atracar(sender, args); }
            default -> { return arma(sender, args); }
        }
    }

    private boolean rendirse(CommandSender s) {
        if (!(s instanceof Player p)) { s.sendMessage("Solo jugadores."); return true; }
        if (!downed.isDowned(p.getUniqueId())) { p.sendMessage("§7No estás derribado."); return true; }
        downed.trySurrender(p);   // espera minima: la ventana de rescate es sagrada
        return true;
    }

    private boolean revivir(CommandSender s) {
        if (!(s instanceof Player p)) { s.sendMessage("Solo jugadores."); return true; }
        if (downed.isDowned(p.getUniqueId())) { p.sendMessage("§cEstás derribado, no puedes reanimar."); return true; }
        boolean ok = downed.tryManualRevive(p);
        p.sendMessage(ok ? "§aReanimaste al herido." : "§7Acércate y mira a un jugador derribado (y lleva un botiquín).");
        return true;
    }

    private boolean emt(CommandSender s) {
        if (!(s instanceof Player p)) { s.sendMessage("Solo jugadores."); return true; }
        Location nearest = null;
        double best = Double.MAX_VALUE;
        int total = 0;
        for (UUID u : downed.downedPlayers()) {
            Player d = Bukkit.getPlayer(u);
            if (d == null || !d.getWorld().equals(p.getWorld())) continue;
            total++;
            double dist = d.getLocation().distanceSquared(p.getLocation());
            if (dist < best) { best = dist; nearest = d.getLocation(); }
        }
        if (nearest != null) {
            p.setCompassTarget(nearest);
            p.sendMessage("§a[EMT] Hay §f" + total + "§a herido(s) cerca. Tu brújula apunta al más próximo.");
        } else {
            p.sendMessage("§7[EMT] No hay heridos en tu mundo ahora mismo.");
        }
        return true;
    }

    private boolean arma(CommandSender s, String[] a) {
        if (!s.hasPermission("nemeles.combat.admin")) { s.sendMessage("§cSin permiso."); return true; }
        if (a.length == 0) { help(s); return true; }
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "dar" -> {
                if (a.length < 3) { s.sendMessage("§e/arma dar <jugador> <arma>"); return true; }
                Player t = Bukkit.getPlayerExact(a[1]);
                if (t == null) { s.sendMessage("§cJugador no conectado."); return true; }
                if (guns.def(a[2]) == null) { s.sendMessage("§cArma desconocida: " + a[2]); return true; }
                ItemStack gun = guns.create(a[2]);
                if (gun == null) { s.sendMessage("§cNo se pudo crear el arma."); return true; }
                t.getInventory().addItem(gun);
                s.sendMessage("§aDiste un(a) " + a[2] + " a " + t.getName() + ".");
            }
            case "darskin" -> {
                if (a.length < 4) { s.sendMessage("§e/arma darskin <jugador> <arma> <skin>"); return true; }
                Player t = Bukkit.getPlayerExact(a[1]);
                if (t == null) { s.sendMessage("§cJugador no conectado."); return true; }
                if (guns.def(a[2]) == null) { s.sendMessage("§cArma desconocida: " + a[2]); return true; }
                if (guns.skinDef(a[3]) == null) { s.sendMessage("§cSkin desconocida: " + a[3]); return true; }
                ItemStack gun = guns.createSkinned(a[2], a[3]);
                if (gun == null) { s.sendMessage("§cNo se pudo crear el arma."); return true; }
                t.getInventory().addItem(gun);
                s.sendMessage("§aDiste " + a[2] + " con skin " + a[3] + " a " + t.getName() + ".");
            }
            case "cofreskins", "skincofre", "skins" -> {
                if (a.length < 2) { s.sendMessage("§e/arma cofreskins <jugador> [arma] §7- cofre GUI con todas las skins"); return true; }
                Player t = Bukkit.getPlayerExact(a[1]);
                if (t == null) { s.sendMessage("§cJugador no conectado."); return true; }
                String filtro = a.length >= 3 ? a[2].toLowerCase(Locale.ROOT) : null;
                java.util.List<ItemStack> items = new java.util.ArrayList<>();
                for (com.nemeles.combat.GunSkin gs : guns.allSkins()) {
                    if (filtro != null && !gs.weapon.equalsIgnoreCase(filtro)) continue;
                    ItemStack it = guns.createSkinned(gs.weapon, gs.id);
                    if (it != null) items.add(it);
                }
                if (items.isEmpty()) { s.sendMessage("§cNo hay skins" + (filtro != null ? " para " + filtro : "") + "."); return true; }
                int rows = Math.min(6, Math.max(1, (items.size() + 8) / 9));
                org.bukkit.inventory.Inventory inv = Bukkit.createInventory(null, rows * 9,
                        net.kyori.adventure.text.Component.text("Skins" + (filtro != null ? " - " + filtro : "")));
                for (int i = 0; i < items.size() && i < rows * 9; i++) inv.setItem(i, items.get(i));
                t.openInventory(inv);
                s.sendMessage("§aAbriste el cofre de skins (" + items.size() + ") a " + t.getName()
                        + ". Saca lo que quieras; se regenera al reabrir.");
            }
            case "municion", "ammo" -> {
                if (a.length < 4) { s.sendMessage("§e/arma municion <jugador> <tipo> <cantidad>"); return true; }
                Player t = Bukkit.getPlayerExact(a[1]);
                if (t == null) { s.sendMessage("§cJugador no conectado."); return true; }
                if (guns.ammoItem(a[2]) == null) { s.sendMessage("§cMunición desconocida: " + a[2]); return true; }
                int n;
                try { n = Math.max(1, Integer.parseInt(a[3])); } catch (NumberFormatException ex) { s.sendMessage("§cCantidad inválida."); return true; }
                ammo.giveAmmo(t, a[2], n);
                s.sendMessage("§aDiste " + n + " de munición " + a[2] + " a " + t.getName() + ".");
            }
            case "reload", "recargar" -> {
                plugin.reloadConfig();
                s.sendMessage("§e[Combat] Config recargada (algunos valores requieren reiniciar para aplicarse).");
            }
            default -> help(s);
        }
        return true;
    }

    private void help(CommandSender s) {
        s.sendMessage("§6== /arma (admin) ==");
        s.sendMessage("§e/arma dar <jugador> <arma> §7- p.ej. pistol, smg, rifle, shotgun, knife");
        s.sendMessage("§e/arma darskin <jugador> <arma> <skin> §7- da un arma con skin cosmetica");
        s.sendMessage("§e/arma cofreskins <jugador> [arma] §7- abre un cofre con TODAS las skins (o de un arma)");
        s.sendMessage("§e/arma municion <jugador> <tipo> <n> §7- p.ej. 9mm, rifle, shell");
        s.sendMessage("§e/arma reload §7- recarga la config");
    }

    // ─── ATRACO (stick-up) ───────────────────────────────────
    /** /manosarriba: el jugador se rinde 30s (un atracador puede robarle sin dispararle). */
    private boolean manosArriba(CommandSender s) {
        if (!(s instanceof Player p)) { s.sendMessage("Solo jugadores."); return true; }
        surrendered.put(p.getUniqueId(), System.currentTimeMillis() + 30_000L);
        p.sendActionBar(LEGACY.deserialize("§e§l✋ MANOS ARRIBA §r§7— rendido 30s (te pueden atracar)"));
        try { p.getWorld().playSound(p.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 0.4f, 0.6f); } catch (Throwable ignored) { }
        return true;
    }

    private boolean isSurrendered(UUID id) {
        Long t = surrendered.get(id);
        return t != null && t > System.currentTimeMillis();
    }

    /** /atracar <jugador>: roba EFECTIVO a una víctima RENDIDA o DERRIBADA y cercana, con arma en mano. */
    private boolean atracar(CommandSender s, String[] a) {
        if (!(s instanceof Player robber)) { s.sendMessage("Solo jugadores."); return true; }
        if (a.length == 0) { robber.sendMessage("§e/atracar <jugador> §7(la víctima debe estar rendida o derribada)"); return true; }
        Player t = Bukkit.getPlayerExact(a[0]);
        if (t == null || t.equals(robber)) { robber.sendMessage("§cVíctima no válida o no conectada."); return true; }
        if (!guns.isGun(robber.getInventory().getItemInMainHand())) { robber.sendMessage("§cNecesitas un arma en la mano para atracar."); return true; }
        if (robber.getWorld() == null || !robber.getWorld().equals(t.getWorld())
                || robber.getLocation().distanceSquared(t.getLocation()) > 25) {
            robber.sendMessage("§cAcércate a la víctima (≤5 bloques)."); return true;
        }
        try { if (com.nemeles.core.api.NemelesApi.regions().isSafezone(t.getLocation())) { robber.sendMessage("§cNo puedes atracar en zona segura."); return true; } } catch (Throwable ignored) { }
        boolean subdued = isSurrendered(t.getUniqueId());
        if (!subdued) { try { subdued = com.nemeles.core.api.NemelesApi.combat().isDowned(t.getUniqueId()); } catch (Throwable ignored) { } }
        if (!subdued) { robber.sendMessage("§cLa víctima debe estar §lRENDIDA§r§c (/manosarriba) o §lDERRIBADA§r§c para atracarla."); return true; }
        long now = System.currentTimeMillis();
        Long cd = robCd.get(robber.getUniqueId());
        if (cd != null && cd > now) { robber.sendMessage("§cEspera antes de volver a atracar."); return true; }
        robCd.put(robber.getUniqueId(), now + 30_000L);
        final UUID vid = t.getUniqueId(), rid = robber.getUniqueId();
        final String vname = t.getName();
        final com.nemeles.core.api.economy.MoneyType cash = com.nemeles.core.api.economy.MoneyType.EFECTIVO;
        com.nemeles.core.api.NemelesApi.economy().balance(vid, cash)
                .thenAccept(bal -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (bal == null) return;
                    java.math.BigDecimal amt = bal.multiply(java.math.BigDecimal.valueOf(0.5))
                            .min(java.math.BigDecimal.valueOf(5000)).setScale(2, java.math.RoundingMode.DOWN);
                    if (amt.compareTo(java.math.BigDecimal.ONE) < 0) { robber.sendMessage("§7La víctima no lleva efectivo que valga la pena."); return; }
                    com.nemeles.core.api.NemelesApi.economy().transfer(vid, cash, rid, cash, amt, "atraco")
                            .thenAccept(res -> Bukkit.getScheduler().runTask(plugin, () -> {
                                if (res != null && res.success()) {
                                    robber.sendMessage("§a[Atraco] Le robaste §f$" + amt.toPlainString() + " §aa " + vname + ".");
                                    Player v = Bukkit.getPlayer(vid);
                                    if (v != null) {
                                        v.sendMessage("§c[Atraco] ¡Te robaron §f$" + amt.toPlainString() + " §cen efectivo!");
                                        try { v.sendActionBar(LEGACY.deserialize("§4¡Te atracaron!")); } catch (Throwable ignored) { }
                                    }
                                    try { com.nemeles.core.api.NemelesApi.wanted().addCrime(rid, 30, "atraco", robber.getLocation(), false); } catch (Throwable ignored) { }
                                    try { robber.playSound(robber.getLocation(), org.bukkit.Sound.ENTITY_VILLAGER_NO, 0.7f, 1.2f); } catch (Throwable ignored) { }
                                } else {
                                    robber.sendMessage("§c[Atraco] No se pudo completar el robo.");
                                }
                            }));
                }));
        return true;
    }
}
